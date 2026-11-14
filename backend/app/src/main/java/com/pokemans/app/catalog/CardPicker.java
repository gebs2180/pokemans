package com.pokemans.app.catalog;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * The collector's card picker: search the catalog as they type, see which prints the card came in,
 * and store their pick as a proposed identity. A proposal is never an identification: the admin
 * still confirms it, and only a confirmed identity is priced, flagged or verified.
 *
 * <p>Seam for the next step (photo-first "Is this your card?"): a {@link CardReader} reading of the
 * front photo can be turned into the same {@link Pick}s, and the collector's choice stored with
 * {@link #propose} like a typed pick.
 */
@Service
class CardPicker {

    /** Results the picker shows at most. */
    static final int MAX_RESULTS = 10;
    private static final String FIRST_EDITION_STAMP = "1st-edition";

    /** A search result, with only what the picker shows. */
    record Pick(String externalId, String name, String setName, String number, Integer year, String language, String imageUrl) {
    }

    /**
     * A card's prints as the catalog lists them. The picker asks only about what has more than one
     * option, and never guesses.
     */
    record PickDetail(String externalId, String name, String setName, String number, Integer year, String language,
                      String imageUrl, List<Edition> editions, List<Variant> variants, List<String> stamps, boolean promo) {
    }

    /**
     * What the collector chose. Null edition or variant: not sure, left for review. A null stamp is
     * "no stamp" unless {@code stampUnsure}.
     */
    record Proposal(String externalId, String language, Edition edition, Variant variant, String stamp, boolean stampUnsure) {
    }

    /** A collector number in the query: "4/102", "#4", "004", "TG01/TG30", "SV-P 001"'s "001". */
    private static final Pattern NUMBER = Pattern.compile("(?i)#?[a-z]{0,4}-?\\d+[a-z]?(/[a-z0-9-]+)?");

    private final Optional<CatalogProvider> provider;
    private final IdentityProposalRepository proposals;
    private final CardItems cardItems;
    private final CurrentUser currentUser;
    private final CatalogRateLimiter limiter;

    CardPicker(Optional<CatalogProvider> provider, IdentityProposalRepository proposals, CardItems cardItems,
               CurrentUser currentUser, CatalogProperties properties) {
        this.provider = provider;
        this.proposals = proposals;
        this.cardItems = cardItems;
        this.currentUser = currentUser;
        this.limiter = new CatalogRateLimiter(properties.picker().perMinute(), Duration.ofMinutes(1), Clock.systemUTC());
    }

    /** Up to {@link #MAX_RESULTS} catalog cards matching what the collector typed: a name, and maybe a number. */
    List<Pick> search(String query, String language) {
        limiter.acquire(caller());
        Query q = parse(query);
        if (q.name().length() < 2) {
            return List.of();
        }
        CatalogProvider p = requireProvider();
        List<CatalogProvider.Hit> hits = call(() -> p.search(q.name(), q.number(), language));
        Map<String, Integer> years = new HashMap<>();
        List<Pick> picks = new ArrayList<>();
        for (CatalogProvider.Hit h : hits.subList(0, Math.min(MAX_RESULTS, hits.size()))) {
            Integer year = h.setId() == null ? null : years.computeIfAbsent(h.setId(), id -> year(p, id, language));
            picks.add(new Pick(h.externalId(), h.name(), h.setName(), printed(h.number(), h.setTotal()), year, language, h.imageUrl()));
        }
        return picks;
    }

    /** The card's prints, for the variant buttons. */
    PickDetail detail(String externalId, String language) {
        limiter.acquire(caller());
        CatalogProvider.ProviderCard c = providerCard(externalId, language);
        return new PickDetail(c.externalId(), c.name(), c.setName(), printed(c.number(), c.setOfficialCount()),
                c.setReleaseDate() == null ? null : c.setReleaseDate().getYear(), c.language(), c.imageUrl(),
                c.editions(), c.variants(), stamps(c), c.promo());
    }

    /**
     * The stamps to ask about. TCGdex also marks 1st Edition prints with a "1st-edition" stamp, which
     * the edition already asks, so it isn't asked twice.
     */
    static List<String> stamps(CatalogProvider.ProviderCard c) {
        return c.stamps().stream().filter(s -> !(FIRST_EDITION_STAMP.equals(s) && c.editions().contains(Edition.FIRST_EDITION))).toList();
    }

    /**
     * Stores the collector's pick for their card. Every choice must be one the catalog lists; where it
     * lists only one, that one is taken. Nothing else changes: the card waits for the admin as before.
     */
    @Transactional
    IdentityProposal propose(UUID orgId, UUID cardId, Proposal p) {
        CardItems.CardItemRef item = cardItems.requireAccessible(orgId, cardId);
        if (item.lot()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lots aren't identified card by card");
        }
        limiter.acquire(caller());
        CatalogProvider.ProviderCard c = providerCard(p.externalId(), p.language());
        Edition edition = choose(p.edition(), c.editions(), "edition");
        Variant variant = choose(p.variant(), c.variants(), "variant");
        String stamp = p.stamp() == null || p.stamp().isBlank() ? null : p.stamp().trim();
        List<String> stamps = stamps(c);
        if (stamp != null && !stamps.contains(stamp)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The catalog doesn't list that stamp for this card");
        }
        boolean stampUnsure = !stamps.isEmpty() && stamp == null && p.stampUnsure();
        return proposals.save(new IdentityProposal(item.orgId(), cardId, c, edition, variant, stamp, stampUnsure, currentUser.id()));
    }

    @Transactional(readOnly = true)
    Optional<IdentityProposal> latest(UUID cardId) {
        return proposals.findFirstByCardItemIdOrderByCreatedAtDesc(cardId);
    }

    /** The collector's choice if the catalog lists it, the only option if there's one, else null (not sure). */
    private static <T> T choose(T chosen, List<T> listed, String what) {
        if (chosen != null) {
            if (!listed.contains(chosen)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The catalog doesn't list that " + what + " for this card");
            }
            return chosen;
        }
        return listed.size() == 1 ? listed.getFirst() : null;
    }

    record Query(String name, String number) {
    }

    /** Splits what was typed into a name and the last thing that looks like a collector number. */
    static Query parse(String query) {
        List<String> words = new ArrayList<>(List.of(query.trim().split("\\s+")));
        String number = null;
        for (int i = words.size() - 1; i >= 0; i--) {
            String w = words.get(i);
            if (w.chars().anyMatch(Character::isDigit) && NUMBER.matcher(w).matches()) {
                number = w.startsWith("#") ? w.substring(1) : w;
                words.remove(i);
                break;
            }
        }
        return new Query(String.join(" ", words).trim(), number);
    }

    /** "4" in a set of 102 reads "4/102", as printed; other numbers as the catalog has them. */
    static String printed(String number, Integer setTotal) {
        if (number == null) {
            return null;
        }
        return setTotal != null && number.matches("\\d+") ? number + "/" + setTotal : number;
    }

    private static Integer year(CatalogProvider p, String setId, String language) {
        try {
            return p.setReleaseDate(setId, language).map(LocalDate::getYear).orElse(null);
        } catch (RuntimeException e) {
            return null; // the year is a help, not a need
        }
    }

    private CatalogProvider.ProviderCard providerCard(String externalId, String language) {
        return call(() -> requireProvider().card(externalId, language))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found in the catalog"));
    }

    private CatalogProvider requireProvider() {
        return provider.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Card search isn't available right now. Describe the card instead."));
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Card search didn't answer. Try again, or describe the card instead.", e);
        }
    }

    private static String caller() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "anonymous" : auth.getName().toLowerCase(Locale.ROOT);
    }
}

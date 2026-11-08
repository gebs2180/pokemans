package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only (by path): which PokeTrace card a catalog card is. Search shows PokeTrace's candidates
 * with where each disagrees with our card; the admin picks one (with a reason if it disagrees).
 */
@RestController
@RequestMapping("/api/admin/catalog-cards/{catalogCardId}/poketrace")
class PokeTraceController {

    record MapRequest(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9-]+") String poketraceId,
                      @Size(max = 1000) String overrideReason) {
    }

    record Candidate(String poketraceId, String name, String cardNumber, String variant, String setName, List<String> disagreements) {
    }

    /** @param poketraceId null when never mapped or unmapped */
    record MappingView(boolean enabled, String poketraceId, Map<String, Object> poketraceCard, String overrideReason,
                       UUID mappedBy, Instant mappedAt) {
    }

    /** What a pull stored; {@code repriced} false when another refresh held the lock (the comps are stored either way). */
    record PullView(int fetched, int inserted, int duplicates, int rejected, List<String> errors, boolean repriced) {
    }

    private final ObjectProvider<PokeTraceApi> api;
    private final PokeTraceMappings mappings;
    private final PricingRefresh refresh;
    private final SoldCompRepository comps;
    private final PricingProperties properties;
    private final Catalog catalog;
    private final CurrentUser currentUser;

    PokeTraceController(ObjectProvider<PokeTraceApi> api, PokeTraceMappings mappings, PricingRefresh refresh, SoldCompRepository comps,
                        PricingProperties properties, Catalog catalog, CurrentUser currentUser) {
        this.api = api;
        this.mappings = mappings;
        this.refresh = refresh;
        this.comps = comps;
        this.properties = properties;
        this.catalog = catalog;
        this.currentUser = currentUser;
    }

    /**
     * Pulls this mapped card's PokeTrace sales now instead of at the nightly run, then re-prices.
     * The first pull of a card asks for a year of history, so it can take a minute.
     */
    @PostMapping("/pull")
    PullView pull(@PathVariable UUID catalogCardId) {
        Catalog.CardSummary card = card(catalogCardId);
        PokeTraceSource source = new PokeTraceSource(api(), mappings, comps, properties);
        if (mappings.poketraceId(catalogCardId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Map the card to a PokeTrace card first");
        }
        PricingRefresh.Pull pull = refresh.pullOne(source, card, LocalDate.now(ZoneOffset.UTC));
        CompIngestRun run = pull.run();
        return new PullView(run.getFetched(), run.getInserted(), run.getDuplicates(), run.getRejected(),
                run.getErrors() == null ? List.of() : run.getErrors(), pull.repriced());
    }

    @GetMapping
    MappingView mapping(@PathVariable UUID catalogCardId) {
        card(catalogCardId);
        return view(catalogCardId);
    }

    /**
     * PokeTrace's single cards with the card's name, narrowed to our number and then our set when any
     * match, so a common name (a dozen Victinis) shows only the card itself. With no match at a step
     * the wider list stays, and each row's disagreements say why it isn't ours.
     */
    @GetMapping("/candidates")
    List<Candidate> candidates(@PathVariable UUID catalogCardId) {
        Catalog.CardSummary card = card(catalogCardId);
        List<Candidate> all = api().search(card.name()).stream()
                .filter(c -> c.get("id") != null && "single".equals(String.valueOf(c.getOrDefault("productType", "single"))))
                .map(c -> new Candidate(c.get("id").toString(), str(c.get("name")), str(c.get("cardNumber")), str(c.get("variant")),
                        c.get("set") instanceof Map<?, ?> s ? str(s.get("name")) : null, PokeTraceMappings.disagreements(card, c)))
                .toList();
        return narrow(narrow(all, "number "), "set ").stream()
                .sorted(Comparator.comparingInt((Candidate c) -> c.disagreements().size()))
                .limit(25)
                .toList();
    }

    /** The candidates without a disagreement of this kind, or all of them when every one has it. */
    private static List<Candidate> narrow(List<Candidate> candidates, String kind) {
        List<Candidate> matching = candidates.stream()
                .filter(c -> c.disagreements().stream().noneMatch(d -> d.startsWith(kind)))
                .toList();
        return matching.isEmpty() ? candidates : matching;
    }

    @PutMapping
    MappingView map(@PathVariable UUID catalogCardId, @Valid @RequestBody MapRequest request) {
        Catalog.CardSummary card = card(catalogCardId);
        Map<String, Object> detail = api().card(request.poketraceId());
        if (detail.isEmpty() || !request.poketraceId().equals(str(detail.get("id")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PokeTrace has no card " + request.poketraceId());
        }
        try {
            mappings.map(card, request.poketraceId(), detail, request.overrideReason(), currentUser.id());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return view(catalogCardId);
    }

    @DeleteMapping
    MappingView unmap(@PathVariable UUID catalogCardId) {
        card(catalogCardId);
        if (mappings.poketraceId(catalogCardId).isPresent()) {
            mappings.unmap(catalogCardId, currentUser.id());
        }
        return view(catalogCardId);
    }

    private MappingView view(UUID catalogCardId) {
        boolean enabled = api.getIfAvailable() != null;
        return mappings.current(catalogCardId).filter(m -> m.getPoketraceId() != null)
                .map(m -> new MappingView(enabled, m.getPoketraceId(), m.getPoketraceCard(), m.getOverrideReason(), m.getMappedBy(),
                        m.getCreatedAt()))
                .orElse(new MappingView(enabled, null, null, null, null, null));
    }

    private PokeTraceApi api() {
        PokeTraceApi a = api.getIfAvailable();
        if (a == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PokeTrace isn't enabled on this server");
        }
        return a;
    }

    private Catalog.CardSummary card(UUID catalogCardId) {
        return catalog.cards(List.of(catalogCardId)).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No catalog card " + catalogCardId));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

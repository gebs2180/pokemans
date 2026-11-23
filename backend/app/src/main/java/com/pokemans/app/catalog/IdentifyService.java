package com.pokemans.app.catalog;

import com.pokemans.app.intake.CardItems;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The collector's side of identifying a card added photos first: "Is this your card?" with the
 * reader's suggestions, or the search when the reader can't help ({@link IdentifyPolicy} decides
 * which). Only reads: the answer is stored by {@link CardPicker#propose}.
 */
@Service
class IdentifyService {

    private static final Logger log = LoggerFactory.getLogger(IdentifyService.class);

    /** Suggestions shown at most: the best one, and two close alternatives. */
    static final int MAX_SUGGESTIONS = 3;

    /**
     * A reader's suggestion as the collector sees it.
     *
     * @param suggestionId what to send back to confirm it
     * @param card         the catalog card and the prints it came in, to ask only what has more than one option
     * @param edition      the edition the reader saw, if it could tell: a starting choice, never taken without asking
     * @param variant      the finish the reader saw, likewise
     */
    record SuggestedCard(UUID suggestionId, CardPicker.PickDetail card, Edition edition, Variant variant,
                         BigDecimal confidence) {
    }

    /**
     * @param why         why the collector searches, on {@link IdentifyPolicy.Step#PICK}
     * @param readQuery   what the reader saw, name and number, to start the search with; null if nothing was read
     * @param readLanguage the language the reader saw, if any
     * @param suggestions on {@link IdentifyPolicy.Step#SUGGEST}, best first
     * @param proposal    the collector's latest answer, if any
     */
    record IdentifyView(IdentifyPolicy.Step step, IdentifyPolicy.Why why, UUID readingId, String readQuery,
                        String readLanguage, List<SuggestedCard> suggestions, CatalogController.ProposalView proposal) {
    }

    private final CardItems cardItems;
    private final RecognitionService recognition;
    private final IdentityProposalRepository proposals;
    private final CatalogCardRepository catalogCards;
    private final CardIdentificationRepository identifications;
    private final Optional<CatalogProvider> provider;

    IdentifyService(CardItems cardItems, RecognitionService recognition,
                    IdentityProposalRepository proposals, CatalogCardRepository catalogCards,
                    CardIdentificationRepository identifications, Optional<CatalogProvider> provider) {
        this.identifications = identifications;
        this.cardItems = cardItems;
        this.recognition = recognition;
        this.proposals = proposals;
        this.catalogCards = catalogCards;
        this.provider = provider;
    }

    /** @param again leave out the collector's earlier answer: they are changing it */
    IdentifyView view(UUID orgId, UUID cardId, boolean again) {
        CardItems.CardItemRef item = cardItems.requireAccessible(orgId, cardId);
        if (item.lot()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lots aren't identified card by card");
        }
        Optional<IdentityProposal> proposal = proposals.findFirstByCardItemIdOrderByCreatedAtDesc(cardId);
        Optional<CardReading> reading = recognition.latestReading(cardId);
        List<CardIdentification> suggested = reading.map(r -> recognition.suggestions(r.getId())).orElse(List.of());
        IdentifyPolicy.Decision decision = decide(cardId, proposal.isPresent() && !again, reading, suggested);

        String readQuery = null;
        String readLanguage = null;
        if (reading.isPresent() && reading.get().getStatus() == CardReading.Status.DONE) {
            CardReader.Reading read = reading.get().reading();
            readQuery = String.join(" ", java.util.stream.Stream.of(read.name(), CardPicker.printed(read.number(), read.setTotal()))
                    .filter(v -> v != null && !v.isBlank()).toList());
            readQuery = readQuery.isBlank() ? null : readQuery;
            readLanguage = read.language();
        }
        List<SuggestedCard> suggestions = decision.step() == IdentifyPolicy.Step.SUGGEST ? suggestions(suggested) : List.of();
        return new IdentifyView(decision.step(), decision.why(), reading.map(CardReading::getId).orElse(null), readQuery,
                readLanguage, suggestions, proposal.map(CatalogController::view).orElse(null));
    }

    /**
     * Where identifying the card stands, without fetching the suggestions' details: for the home page, which only needs
     * to know whether the collector's answer is awaited. No access check.
     */
    IdentifyPolicy.Step step(UUID cardId) {
        Optional<CardReading> reading = recognition.latestReading(cardId);
        List<CardIdentification> suggested = reading.map(r -> recognition.suggestions(r.getId())).orElse(List.of());
        boolean proposed = proposals.findFirstByCardItemIdOrderByCreatedAtDesc(cardId).isPresent();
        return decide(cardId, proposed, reading, suggested).step();
    }

    private IdentifyPolicy.Decision decide(UUID cardId, boolean proposed, Optional<CardReading> reading, List<CardIdentification> suggested) {
        boolean identified = identifications.findByCardItemIdOrderByCreatedAtDesc(cardId).stream()
                .anyMatch(i -> i.getStatus() == CardIdentification.Status.CONFIRMED);
        List<CardItems.PhotoRef> photos = cardItems.passedPhotos(cardId);
        boolean captureComplete = photos.stream().anyMatch(p -> "FRONT".equals(p.side()))
                && photos.stream().anyMatch(p -> "BACK".equals(p.side()));
        Instant capturedAt = photos.stream().map(CardItems.PhotoRef::uploadedAt).filter(t -> t != null)
                .max(Comparator.naturalOrder()).orElse(null);
        IdentifyPolicy.ReadingState state = reading.map(r -> new IdentifyPolicy.ReadingState(r.getStatus(), r.getCreatedAt(),
                r.getFinishedAt(), r.getStatus() == CardReading.Status.DONE && r.getLastError() != null, suggested.size()))
                .orElse(null);
        return IdentifyPolicy.decide(identified, proposed, captureComplete, capturedAt, recognition.enabled(), provider.isPresent(),
                state, Instant.now());
    }

    /** One per catalog card, best first: the reader may suggest two prints of the same card. */
    private List<SuggestedCard> suggestions(List<CardIdentification> suggested) {
        Set<String> seen = new LinkedHashSet<>();
        List<SuggestedCard> shown = new ArrayList<>();
        for (CardIdentification s : suggested) {
            CatalogCard c = catalogCards.findById(s.getCatalogCardId()).orElse(null);
            if (c == null || c.getExternalId() == null || !seen.add(c.getLanguage() + "/" + c.getExternalId())) {
                continue;
            }
            shown.add(new SuggestedCard(s.getId(), detail(c), c.getEdition(), c.getVariant(), s.getConfidence()));
            if (shown.size() == MAX_SUGGESTIONS) {
                break;
            }
        }
        return shown;
    }

    /**
     * The card's prints from the catalog, to ask only what has more than one option. If the catalog
     * doesn't answer, what we stored of the suggestion stands in; confirming needs the catalog anyway.
     */
    private CardPicker.PickDetail detail(CatalogCard c) {
        if (provider.isPresent()) {
            try {
                Optional<CatalogProvider.ProviderCard> found = provider.get().card(c.getExternalId(), c.getLanguage());
                if (found.isPresent()) {
                    CatalogProvider.ProviderCard p = found.get();
                    return new CardPicker.PickDetail(p.externalId(), p.name(), p.setName(),
                            CardPicker.printed(p.number(), p.setOfficialCount()), year(p.setReleaseDate()), p.language(),
                            p.imageUrl(), p.editions(), p.variants(), CardPicker.stamps(p), p.promo());
                }
            } catch (RuntimeException e) {
                log.warn("Catalog card {} couldn't be fetched for a suggestion; showing what was stored", c.getExternalId(), e);
            }
        }
        return new CardPicker.PickDetail(c.getExternalId(), c.getName(), c.getSetName(), c.getNumber(),
                year(c.getSetReleaseDate()), c.getLanguage(), c.getImageUrl(),
                c.getEdition() == null ? List.of() : List.of(c.getEdition()),
                c.getVariant() == null ? List.of() : List.of(c.getVariant()), List.of(), c.isPromo());
    }

    private static Integer year(LocalDate date) {
        return date == null ? null : date.getYear();
    }
}

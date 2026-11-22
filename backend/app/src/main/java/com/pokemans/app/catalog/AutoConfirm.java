package com.pokemans.app.catalog;

import com.pokemans.app.intake.CardItems;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirms a card's identity without the admin when the collector and the reader agree and nothing is
 * left to guess (docs/offers.md "Automatic gates"; a default to confirm in the methodology doc, 2026-11-22).
 * Everything else waits for the admin, as before. The confirmation is an ordinary CONFIRMED row with
 * source SYSTEM, the rule version, the proposal and the suggestion; the admin overrides it by
 * identifying the card again. Off unless {@code pokemans.catalog.auto-confirm.enabled}.
 *
 * <p>The rule exists to keep the one identity error that must never happen out: pricing a 1st Edition
 * from Unlimited sales. So the edition, finish and stamp must be settled by the catalog or the collector,
 * and where the reader saw an edition or finish it must agree with the collector's.
 */
@Service
class AutoConfirm {

    private static final Logger log = LoggerFactory.getLogger(AutoConfirm.class);

    /** Stored on every automatic confirmation. Change it whenever the rule below changes. */
    static final String RULE_VERSION = "auto-confirm-1 (2026-11-22)";

    /** Published by {@link CardPicker#propose} for a catalog card the collector picked; handled after commit. */
    record ProposalStored(UUID proposalId, CatalogProvider.ProviderCard card) {
    }

    /** Why a proposal was left for the admin. */
    enum Refusal {
        LOT, ALREADY_CONFIRMED, NOT_A_SUGGESTION, NOT_LATEST_READING, NOT_TOP_SUGGESTION, TOP_TIED, NO_CONFIDENCE,
        BELOW_THRESHOLD, EDITION_UNSETTLED, FINISH_UNSETTLED, STAMP_UNSETTLED, READER_EDITION_DIFFERS, READER_FINISH_DIFFERS
    }

    /** A reader's suggestion as the rule sees it. */
    record Ranked(UUID id, BigDecimal confidence) {
    }

    /**
     * What the rule looks at.
     *
     * @param chosenSuggestionId the suggestion the collector confirmed; null unless the proposal's source is SUGGESTION
     * @param suggestionReadingId the reading that suggestion came from
     * @param latestReadingId    the card's latest reading
     * @param suggestions        that reading's suggestions, in any order
     * @param readerEdition      the print the reader's suggestion named, or null if it named none
     * @param stampUnsure        the collector said "Not sure" to the stamp
     */
    record Inputs(boolean lot, boolean alreadyConfirmed, IdentityProposal.Source source, UUID chosenSuggestionId,
                  UUID suggestionReadingId, UUID latestReadingId, List<Ranked> suggestions, Edition edition, Variant variant,
                  boolean stampUnsure, Edition readerEdition, Variant readerVariant, BigDecimal minConfidence) {
    }

    /** Empty to confirm; else the first reason the admin decides it. */
    static Optional<Refusal> decide(Inputs in) {
        if (in.lot()) {
            return Optional.of(Refusal.LOT);
        }
        if (in.alreadyConfirmed()) {
            return Optional.of(Refusal.ALREADY_CONFIRMED);
        }
        if (in.source() != IdentityProposal.Source.SUGGESTION || in.chosenSuggestionId() == null) {
            return Optional.of(Refusal.NOT_A_SUGGESTION);
        }
        if (in.suggestionReadingId() == null || !in.suggestionReadingId().equals(in.latestReadingId())) {
            return Optional.of(Refusal.NOT_LATEST_READING);
        }
        BigDecimal top = in.suggestions().stream().map(Ranked::confidence).filter(Objects::nonNull)
                .max(BigDecimal::compareTo).orElse(null);
        Ranked chosen = in.suggestions().stream().filter(s -> s.id().equals(in.chosenSuggestionId())).findFirst().orElse(null);
        if (chosen == null) {
            return Optional.of(Refusal.NOT_TOP_SUGGESTION);
        }
        if (chosen.confidence() == null) {
            return Optional.of(Refusal.NO_CONFIDENCE);
        }
        if (chosen.confidence().compareTo(top) < 0) {
            return Optional.of(Refusal.NOT_TOP_SUGGESTION);
        }
        if (in.suggestions().stream().filter(s -> s.confidence() != null && s.confidence().compareTo(top) == 0).count() > 1) {
            return Optional.of(Refusal.TOP_TIED);
        }
        if (chosen.confidence().compareTo(in.minConfidence()) < 0) {
            return Optional.of(Refusal.BELOW_THRESHOLD);
        }
        // Null means the catalog listed several and the collector said "Not sure" (CardPicker#choose).
        if (in.edition() == null) {
            return Optional.of(Refusal.EDITION_UNSETTLED);
        }
        if (in.variant() == null) {
            return Optional.of(Refusal.FINISH_UNSETTLED);
        }
        if (in.stampUnsure()) {
            return Optional.of(Refusal.STAMP_UNSETTLED);
        }
        if (in.readerEdition() != null && in.readerEdition() != in.edition()) {
            return Optional.of(Refusal.READER_EDITION_DIFFERS);
        }
        if (in.readerVariant() != null && in.readerVariant() != in.variant()) {
            return Optional.of(Refusal.READER_FINISH_DIFFERS);
        }
        return Optional.empty();
    }

    private final CatalogProperties properties;
    private final IdentityProposalRepository proposals;
    private final CardIdentificationRepository identifications;
    private final CatalogCardRepository catalogCards;
    private final CardReadingRepository readings;
    private final IdentificationService identification;
    private final CardItems cardItems;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    AutoConfirm(CatalogProperties properties, IdentityProposalRepository proposals, CardIdentificationRepository identifications,
                CatalogCardRepository catalogCards, CardReadingRepository readings, IdentificationService identification,
                CardItems cardItems, ApplicationEventPublisher events, PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.proposals = proposals;
        this.identifications = identifications;
        this.catalogCards = catalogCards;
        this.readings = readings;
        this.identification = identification;
        this.cardItems = cardItems;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        // After the proposal's commit its resources are still bound: a new transaction, or this would join the old one.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Considers the collector's proposal once it has committed. A failure leaves the card for the admin. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onProposal(ProposalStored event) {
        if (!properties.autoConfirm().enabled()) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> consider(event));
        } catch (RuntimeException e) {
            log.warn("Automatic identity confirmation of proposal {} failed; the admin confirms it", event.proposalId(), e);
        }
    }

    /** Confirms the proposal if the rule allows; returns the new identification, if any. */
    Optional<CardIdentification> consider(ProposalStored event) {
        IdentityProposal proposal = proposals.findById(event.proposalId()).orElseThrow();
        UUID cardId = proposal.getCardItemId();
        boolean latest = proposals.findFirstByCardItemIdOrderByCreatedAtDesc(cardId)
                .map(p -> p.getId().equals(proposal.getId())).orElse(false);
        if (!latest) {
            return Optional.empty(); // the collector has changed their answer since; that one is considered on its own
        }
        CardItems.CardItemRef item = cardItems.get(cardId);
        boolean confirmed = identifications.findByCardItemIdOrderByCreatedAtDesc(cardId).stream()
                .anyMatch(i -> i.getStatus() == CardIdentification.Status.CONFIRMED);
        CardIdentification suggestion = proposal.getSuggestionId() == null ? null
                : identifications.findById(proposal.getSuggestionId()).orElse(null);
        List<Ranked> ranked = suggestion == null || suggestion.getReadingId() == null ? List.of()
                : identifications.findByReadingIdOrderByConfidenceDesc(suggestion.getReadingId()).stream()
                        .filter(s -> s.getStatus() == CardIdentification.Status.SUGGESTED)
                        .map(s -> new Ranked(s.getId(), s.getConfidence()))
                        .toList();
        UUID latestReading = readings.findByCardItemIdOrderByCreatedAtDesc(cardId).stream().findFirst()
                .map(CardReading::getId).orElse(null);
        CatalogCard suggested = suggestion == null ? null : catalogCards.findById(suggestion.getCatalogCardId()).orElse(null);
        BigDecimal threshold = properties.autoConfirm().minConfidence();
        Optional<Refusal> refusal = decide(new Inputs(item.lot(), confirmed, proposal.getSource(),
                suggestion == null ? null : suggestion.getId(), suggestion == null ? null : suggestion.getReadingId(),
                latestReading, ranked, proposal.getEdition(), proposal.getVariant(), Boolean.TRUE.equals(proposal.getStampUnsure()),
                suggested == null ? null : suggested.getEdition(), suggested == null ? null : suggested.getVariant(), threshold));
        if (refusal.isPresent()) {
            log.info("Card {} left for the admin to identify: {} ({})", cardId, refusal.get(), RULE_VERSION);
            return Optional.empty();
        }
        CatalogProvider.ProviderCard card = event.card();
        if (!card.externalId().equals(suggested.getExternalId()) || !card.language().equals(suggested.getLanguage())) {
            log.info("Card {} left for the admin: the proposal isn't the suggested card", cardId);
            return Optional.empty();
        }
        CatalogCard catalogCard = identification.findOrCreate(IdentificationService.identity(card, proposal.getEdition(),
                proposal.getVariant(), proposal.getStamp(), null), card.payload(), null);
        CardIdentification saved = identifications.save(CardIdentification.confirmedBySystem(item.orgId(), cardId,
                catalogCard.getId(), RULE_VERSION, proposal.getId(), suggestion,
                "Confirmed automatically: the collector chose the reader's top suggestion (confidence "
                        + suggestion.getConfidence().toPlainString() + ", threshold " + threshold.toPlainString() + ")"));
        events.publishEvent(new Catalog.CardIdentified(catalogCard.getId(), item.orgId(), cardId, true));
        log.info("Card {} identified automatically as {} ({})", cardId, catalogCard.getId(), RULE_VERSION);
        return Optional.of(saved);
    }
}

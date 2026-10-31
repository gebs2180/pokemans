package com.pokemans.app.condition;

import com.pokemans.app.catalog.AuthenticityRisk;
import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.catalog.Variant;
import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.GradeDistribution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

/** Prediction requests and condition assessments for physical cards. */
@Service
class ConditionService {

    private static final Logger log = LoggerFactory.getLogger(ConditionService.class);
    /** Advisory lock key for {@link #sweepMissedCaptures}. */
    private static final long SWEEP_LOCK = 0x706f6b65_636f6e64L;

    private final ConditionProperties properties;
    private final List<String> enabledProviders;
    private final PredictionRequestRepository requests;
    private final ProviderPredictionRepository predictions;
    private final ConditionAssessmentRepository assessments;
    private final CardItems cardItems;
    private final Catalog catalog;
    private final CurrentUser currentUser;
    private final DistributionBuilder builder;
    private final ApplicationEventPublisher events;

    /** Prediction requests were queued; once that commits, the worker sends them instead of waiting for its next poll. */
    record PredictionsQueued(UUID cardItemId) {
    }

    ConditionService(List<PreGradeProvider> providers, PredictionRequestRepository requests, ProviderPredictionRepository predictions,
                     ConditionAssessmentRepository assessments, CardItems cardItems, Catalog catalog, CurrentUser currentUser,
                     ConditionProperties properties, ApplicationEventPublisher events) {
        this.properties = properties;
        this.enabledProviders = providers.stream().map(PreGradeProvider::name).sorted().toList();
        this.requests = requests;
        this.predictions = predictions;
        this.assessments = assessments;
        this.cardItems = cardItems;
        this.catalog = catalog;
        this.currentUser = currentUser;
        this.builder = new DistributionBuilder(properties);
        this.events = events;
    }

    List<String> enabledProviders() {
        return enabledProviders;
    }

    /** A card's capture just completed: ask every enabled provider about it. Lots aren't graded. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onCaptureCompleted(CardItems.CaptureCompleted event) {
        CardItems.CardItemRef card = cardItems.get(event.cardItemId());
        if (!card.lot()) {
            enqueue(card, null, enabledProviders);
        }
    }

    /**
     * A photo was retaken: measure centering again, since the seller is shown it as measured from their
     * photos. The paid providers wait for "Ask again". Lots aren't graded.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onPhotoRetaken(CardItems.PhotoRetaken event) {
        CardItems.CardItemRef card = cardItems.get(event.cardItemId());
        if (!card.lot() && enabledProviders.contains(CenteringPreGradeProvider.NAME)) {
            enqueue(card, null, List.of(CenteringPreGradeProvider.NAME));
        }
    }

    /**
     * Queues one request per enabled provider that has nothing open for this card, using the latest
     * passing front and back. Returns the requests created (empty if none are enabled).
     */
    @Transactional
    List<PredictionRequest> requestPredictions(UUID orgId, UUID cardId) {
        CardItems.CardItemRef card = requireCard(orgId, cardId);
        if (card.lot()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lots aren't pre-graded");
        }
        return enqueue(card, currentUser.id(), enabledProviders);
    }

    private List<PredictionRequest> enqueue(CardItems.CardItemRef card, UUID requestedBy, List<String> providers) {
        List<CardItems.PhotoRef> photos = cardItems.passedPhotos(card.id());
        Optional<CardItems.PhotoRef> front = latest(photos, "FRONT");
        Optional<CardItems.PhotoRef> back = latest(photos, "BACK");
        if (front.isEmpty() || back.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The card needs a passing front and back first");
        }
        List<String> open = requests.findByCardItemIdOrderByCreatedAtDesc(card.id()).stream()
                .filter(PredictionRequest::isOpen).map(PredictionRequest::getProvider).toList();
        List<PredictionRequest> created = new ArrayList<>();
        try {
            for (String provider : providers) {
                if (!open.contains(provider)) {
                    created.add(requests.saveAndFlush(new PredictionRequest(card.orgId(), card.id(), provider,
                            List.of(front.get().id(), back.get().id()), requestedBy)));
                }
            }
        } catch (DataIntegrityViolationException e) {
            // Another instance or click queued the same provider meanwhile (prediction_request_open_idx).
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A prediction request for this card is already open");
        }
        if (!created.isEmpty()) {
            events.publishEvent(new PredictionsQueued(card.id()));
        }
        return created;
    }

    /**
     * Catches completed captures whose {@link CardItems.CaptureCompleted} was lost (a crash between
     * the photo check committing and the listener running): queues predictions for single cards whose
     * capture completed in the sweep window and that have never had a request. Cards younger than the
     * minimum age are left to the listener. One sweep at a time across instances.
     *
     * @return how many cards were queued
     */
    @Transactional
    int sweepMissedCaptures(Instant now) {
        if (enabledProviders.isEmpty() || !requests.tryTransactionLock(SWEEP_LOCK)) {
            return 0;
        }
        ConditionProperties.Worker worker = properties.worker();
        int queued = 0;
        for (CardItems.CardItemRef card : cardItems.captureCompletedBetween(now.minus(worker.sweepWindow()), now.minus(worker.sweepMinAge()))) {
            if (!card.lot() && !requests.existsByCardItemId(card.id())) {
                log.warn("Card {} completed capture but has no prediction requests; queuing them now", card.id());
                enqueue(card, null, enabledProviders);
                queued++;
            }
        }
        return queued;
    }

    /**
     * Builds a new system assessment from the latest answer of each provider. Leaves the card alone
     * when a reviewer's assessment is current (theirs wins until they choose to rebuild) or when no
     * provider answered (the reviewer enters it by hand).
     *
     * @return the new current assessment, or empty if none was made
     */
    @Transactional
    Optional<ConditionAssessment> rebuildSystemAssessment(UUID cardId) {
        Optional<ConditionAssessment> current = assessments.findByCardItemIdAndIsCurrentTrue(cardId);
        if (current.isPresent() && current.get().getAuthorType() == ConditionAssessment.AuthorType.REVIEWER) {
            return Optional.empty();
        }
        return build(cardId, current.orElse(null));
    }

    /** Admin's explicit rebuild from predictions; replaces a reviewer assessment too. */
    @Transactional
    ConditionAssessment rebuildNow(UUID orgId, UUID cardId) {
        requireCard(orgId, cardId);
        return build(cardId, assessments.findByCardItemIdAndIsCurrentTrue(cardId).orElse(null))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No provider has graded this card yet"));
    }

    private Optional<ConditionAssessment> build(UUID cardId, ConditionAssessment current) {
        CardItems.CardItemRef card = cardItems.get(cardId);
        Map<String, ProviderPrediction> latest = latestSuccessful(cardId);
        Map<String, Double> grades = new LinkedHashMap<>();
        latest.forEach((provider, p) -> grades.put(provider, p.gradeForDistribution()));
        grades.values().removeIf(Objects::isNull);
        Integer centeringCap = latest.values().stream().map(ProviderPrediction::centeringCap).filter(Objects::nonNull)
                .min(Integer::compare).orElse(null);

        List<CardItems.PhotoRef> photos = cardItems.passedPhotos(cardId);
        boolean wholeCard = latest(photos, "FRONT").map(p -> p.cropKey() != null).orElse(false)
                && latest(photos, "BACK").map(p -> p.cropKey() != null).orElse(false);
        Optional<Catalog.IdentifiedCard> identity = catalog.current(cardId);
        // Unidentified cards get the holo surface shift too: unknown finish, so conservative.
        boolean modernHolo = identity.map(ConditionService::isModernHolo).orElse(true);

        DistributionBuilder.Result result = builder.build(new DistributionBuilder.Input(grades, wholeCard, modernHolo, centeringCap));
        if (result == null) {
            return Optional.empty();
        }
        Map<String, Object> basis = new LinkedHashMap<>(result.basis());
        basis.put("predictionIds", latest.values().stream().map(p -> p.getId().toString()).toList());
        basis.put("identified", identity.isPresent());
        String reason = "Built from " + String.join(" and ", grades.keySet())
                + (centeringCap == null ? "" : ", centering caps it at " + centeringCap);
        return Optional.of(save(card, current, result.distribution(), result.confidence(), ConditionAssessment.AuthorType.SYSTEM,
                null, reason, basis));
    }

    /** The reviewer's own distribution: a new current version, with the change from the previous one recorded. */
    @Transactional
    ConditionAssessment reviewerAssessment(UUID orgId, UUID cardId, GradeDistribution distribution, Confidence confidence, String reason) {
        CardItems.CardItemRef card = requireCard(orgId, cardId);
        ConditionAssessment current = assessments.findByCardItemIdAndIsCurrentTrue(cardId).orElse(null);
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("previousVersion", current == null ? null : current.getVersion());
        basis.put("predictionIds", latestSuccessful(cardId).values().stream().map(p -> p.getId().toString()).toList());
        return save(card, current, distribution, confidence, ConditionAssessment.AuthorType.REVIEWER, currentUser.id(), reason, basis);
    }

    private ConditionAssessment save(CardItems.CardItemRef card, ConditionAssessment current, GradeDistribution distribution,
                                     Confidence confidence, ConditionAssessment.AuthorType type, UUID author, String reason,
                                     Map<String, Object> basis) {
        if (current != null) {
            current.supersede();
            assessments.saveAndFlush(current);
        }
        return assessments.save(new ConditionAssessment(card.orgId(), card.id(), current, distribution, confidence, type, author, reason, basis));
    }

    @Transactional(readOnly = true)
    List<ProviderPrediction> predictions(UUID cardId) {
        return predictions.findByCardItemIdOrderByCreatedAtDesc(cardId);
    }

    @Transactional(readOnly = true)
    List<PredictionRequest> requests(UUID cardId) {
        return requests.findByCardItemIdOrderByCreatedAtDesc(cardId);
    }

    @Transactional(readOnly = true)
    List<ConditionAssessment> assessments(UUID cardId) {
        return assessments.findByCardItemIdOrderByVersionDesc(cardId);
    }

    /** A card in the given org, for admin endpoints whose path names both. */
    CardItems.CardItemRef requireCard(UUID orgId, UUID cardId) {
        CardItems.CardItemRef card = cardItems.get(cardId);
        if (!card.orgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found");
        }
        return card;
    }

    private Map<String, ProviderPrediction> latestSuccessful(UUID cardId) {
        return predictions.findByCardItemIdOrderByCreatedAtDesc(cardId).stream()
                .filter(ProviderPrediction::isSucceeded)
                .collect(Collectors.toMap(ProviderPrediction::getProvider, p -> p, (newer, older) -> newer, LinkedHashMap::new));
    }

    static Optional<CardItems.PhotoRef> latest(List<CardItems.PhotoRef> photos, String side) {
        return photos.stream().filter(p -> p.side().equals(side)).max(Comparator.comparing(CardItems.PhotoRef::uploadedAt));
    }

    /** Holo or reverse holo printed after the WotC era (unknown release date counts as modern). */
    static boolean isModernHolo(Catalog.IdentifiedCard card) {
        boolean holo = card.variant() == Variant.HOLO || card.variant() == Variant.REVERSE_HOLO;
        return holo && (card.setReleaseDate() == null || !card.setReleaseDate().isBefore(AuthenticityRisk.END_OF_WOTC_ERA));
    }
}

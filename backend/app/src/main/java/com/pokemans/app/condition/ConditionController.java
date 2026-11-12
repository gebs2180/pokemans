package com.pokemans.app.condition;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.identity.Directory;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.pricing.Prices;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only (by path): the review queue, provider predictions and condition assessments. */
@RestController
@RequestMapping("/api/admin")
@EnableConfigurationProperties(ConditionProperties.class)
class ConditionController {

    /**
     * @param distribution outcome ("U", "1" … "10") to probability; must sum to 1
     * @param overrideCenteringCap true to save a distribution above the measured centering; optional
     */
    record AssessRequest(@NotEmpty Map<String, BigDecimal> distribution, @NotNull Confidence confidence,
                         @NotBlank @Size(max = 2000) String reason, Boolean overrideCenteringCap) {
    }

    /**
     * @param assessment NONE, SYSTEM, REVIEWER, PROVIDER_RETIRED when a system assessment took in a provider we no
     *                   longer use, or PHOTOS_CHANGED when the front or back was retaken
     *                   since the current one: it no longer counts until the card is assessed again
     * @param orgName whose card it is, so the reviewer can tell collectors apart
     * @param priced  true once its catalog card has a PSA price table: raw cards are valued on PSA's table only
     */
    record QueueItem(UUID cardId, UUID orgId, String label, Instant createdAt, boolean identified, String identifiedAs,
                     String assessment, Confidence confidence, int openRequests, String orgName, boolean priced) {
    }

    record PhotoLink(UUID id, String side, URI url, URI cropUrl, Instant capturedAt) {
    }

    record RequestView(UUID id, String provider, PredictionRequest.Status status, int attempts, String lastError,
                       Instant createdAt, Instant finishedAt) {
    }

    record PredictionView(UUID id, String provider, String modelVersion, boolean succeeded, Double overallGrade, Double psaGrade,
                          Double centering, Double corners, Double edges, Double surface, Map<String, Object> details,
                          String error, Long latencyMs, Instant createdAt) {
    }

    record AssessmentView(UUID id, int version, boolean current, Map<String, BigDecimal> distribution, String median,
                          Confidence confidence, ConditionAssessment.AuthorType authorType, UUID authorId, String reason,
                          Map<String, BigDecimal> adjustments, Map<String, Object> basis, Instant createdAt) {
    }

    record CardConditionView(UUID cardId, UUID orgId, String label, List<String> enabledProviders, List<PhotoLink> photos,
                             Catalog.IdentifiedCard identification, List<RequestView> requests,
                             List<PredictionView> predictions, List<AssessmentView> assessments, Integer centeringCap,
                             boolean photosChanged, boolean providerRetired) {
    }

    private final ConditionService condition;
    private final ConditionAssessmentRepository assessmentRepository;
    private final PredictionRequestRepository requestRepository;
    private final CardItems cardItems;
    private final Catalog catalog;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final Directory directory;
    private final Prices prices;
    private final Assessments assessments;
    private final PreGradeFailures preGradeFailures;

    ConditionController(ConditionService condition, ConditionAssessmentRepository assessmentRepository,
                        PredictionRequestRepository requestRepository, CardItems cardItems, Catalog catalog, BlobStore blobs,
                        StorageProperties storage, Directory directory, Prices prices, Assessments assessments,
                        PreGradeFailures preGradeFailures) {
        this.preGradeFailures = preGradeFailures;
        this.assessments = assessments;
        this.condition = condition;
        this.assessmentRepository = assessmentRepository;
        this.requestRepository = requestRepository;
        this.cardItems = cardItems;
        this.catalog = catalog;
        this.blobs = blobs;
        this.storage = storage;
        this.directory = directory;
        this.prices = prices;
    }

    /**
     * Single cards whose capture is complete, oldest first, with what each still needs: an
     * identification, a reviewer's look at the system's distribution, and sold comps.
     */
    @GetMapping("/review-queue")
    List<QueueItem> reviewQueue() {
        List<CardItems.CardItemRef> cards = cardItems.captureComplete().stream().filter(c -> !c.lot()).toList();
        List<UUID> ids = cards.stream().map(CardItems.CardItemRef::id).toList();
        Map<UUID, Catalog.IdentifiedCard> identities = catalog.current(ids);
        Map<UUID, ConditionAssessment> current = ids.isEmpty() ? Map.of()
                : assessmentRepository.findByCardItemIdInAndIsCurrentTrue(ids).stream()
                .collect(Collectors.toMap(ConditionAssessment::getCardItemId, Function.identity()));
        Map<UUID, Long> open = ids.isEmpty() ? Map.of()
                : requestRepository.findByCardItemIdIn(ids).stream().filter(PredictionRequest::isOpen)
                .collect(Collectors.groupingBy(PredictionRequest::getCardItemId, Collectors.counting()));
        Map<UUID, String> orgNames = new java.util.HashMap<>();
        cards.forEach(c -> orgNames.computeIfAbsent(c.orgId(), directory::orgName));
        return cards.stream().map(c -> {
            Catalog.IdentifiedCard id = identities.get(c.id());
            ConditionAssessment a = current.get(c.id());
            return new QueueItem(c.id(), c.orgId(), c.label(), c.createdAt(), id != null,
                    id == null ? null : id.name() + " · " + id.setName() + " " + id.number(),
                    a == null ? "NONE" : assessments.photosChanged(a) ? "PHOTOS_CHANGED"
                            : Assessments.builtOnRetiredProvider(a) ? "PROVIDER_RETIRED" : a.getAuthorType().name(),
                    a == null ? null : a.getConfidence(),
                    open.getOrDefault(c.id(), 0L).intValue(), orgNames.get(c.orgId()),
                    id != null && prices.latest(id.catalogCardId(), "PSA").isPresent());
        }).toList();
    }

    /**
     * Cards in the review queue left unassessed because the automatic pre-grade failed: they wait for Ask again or
     * a reviewer. {@code outOfCredits} is true when CardGrade ran out of credits, which hits every card at once;
     * {@code submitsResumeAt} is when sending resumes, if known.
     */
    @GetMapping("/pregrade-failures")
    PreGradeFailures.Summary preGradeFailures() {
        return preGradeFailures.summary(cardItems.captureComplete().stream().filter(c -> !c.lot()).map(CardItems.CardItemRef::id).toList());
    }

    @GetMapping("/orgs/{orgId}/cards/{cardId}/condition")
    CardConditionView card(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        CardItems.CardItemRef card = condition.requireCard(orgId, cardId);
        List<PhotoLink> photos = cardItems.currentPhotos(cardId).stream()
                .map(p -> new PhotoLink(p.id(), p.side(), blobs.presignDownload(storage.photoBucket(), p.storageKey()),
                        p.cropKey() == null ? null : blobs.presignDownload(storage.photoBucket(), p.cropKey()), p.capturedAt()))
                .toList();
        java.util.Optional<ConditionAssessment> current = assessmentRepository.findByCardItemIdAndIsCurrentTrue(cardId);
        return new CardConditionView(card.id(), card.orgId(), card.label(), condition.enabledProviders(), photos,
                catalog.current(cardId).orElse(null),
                condition.requests(cardId).stream().map(ConditionController::view).toList(),
                condition.predictions(cardId).stream().map(ConditionController::view).toList(),
                condition.assessments(cardId).stream().map(ConditionController::view).toList(), condition.centeringCap(cardId),
                current.map(assessments::photosChanged).orElse(false),
                current.map(Assessments::builtOnRetiredProvider).orElse(false));
    }

    /** Asks every enabled provider (again) about this card. */
    @PostMapping("/orgs/{orgId}/cards/{cardId}/prediction-requests")
    @ResponseStatus(HttpStatus.CREATED)
    List<RequestView> requestPredictions(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        return condition.requestPredictions(orgId, cardId).stream().map(ConditionController::view).toList();
    }

    @PostMapping("/orgs/{orgId}/cards/{cardId}/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    AssessmentView assess(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody AssessRequest request) {
        return view(condition.reviewerAssessment(orgId, cardId, distribution(request.distribution()), request.confidence(),
                request.reason().trim(), Boolean.TRUE.equals(request.overrideCenteringCap())));
    }

    /** Replaces the current assessment with a fresh one built from the latest predictions. */
    @PostMapping("/orgs/{orgId}/cards/{cardId}/assessments/rebuild")
    @ResponseStatus(HttpStatus.CREATED)
    AssessmentView rebuild(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        return view(condition.rebuildNow(orgId, cardId));
    }

    private static GradeDistribution distribution(Map<String, BigDecimal> json) {
        EnumMap<Grade, BigDecimal> map = new EnumMap<>(Grade.class);
        try {
            json.forEach((key, p) -> map.put("U".equalsIgnoreCase(key) ? Grade.U : Grade.of(Integer.parseInt(key)), p));
            return GradeDistribution.of(map);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static RequestView view(PredictionRequest r) {
        return new RequestView(r.getId(), r.getProvider(), r.getStatus(), r.getAttempts(), r.getLastError(), r.getCreatedAt(),
                r.getFinishedAt());
    }

    private static PredictionView view(ProviderPrediction p) {
        return new PredictionView(p.getId(), p.getProvider(), p.getModelVersion(), p.isSucceeded(), p.getOverallGrade(),
                p.getPsaGrade(), p.getCentering(), p.getCorners(), p.getEdges(), p.getSurface(), p.getDetails(), p.getError(),
                p.getLatencyMs(), p.getCreatedAt());
    }

    private static AssessmentView view(ConditionAssessment a) {
        return new AssessmentView(a.getId(), a.getVersion(), a.isCurrent(), a.getDistribution(), a.distribution().median().toString(),
                a.getConfidence(), a.getAuthorType(), a.getAuthorId(), a.getReason(), a.getAdjustments(), a.getBasis(),
                a.getCreatedAt());
    }
}

package com.pokemans.app.condition;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A provider's answer for one card, as received and as we read it. Append-only training and
 * calibration data: kept whether it turns out right or wrong, and also when the provider refused.
 */
@Entity
@Table(name = "provider_prediction")
class ProviderPrediction extends AssignedIdEntity<UUID> {

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    private UUID requestId;
    private String provider;
    private String modelVersion;
    private String externalRequestId;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<UUID> photoIds;
    private boolean succeeded;
    private Double overallGrade;
    private Double psaGrade;
    private Double centering;
    private Double corners;
    private Double edges;
    private Double surface;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;
    private String error;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> raw;
    private Long latencyMs;
    private Instant createdAt;

    protected ProviderPrediction() {
    }

    private ProviderPrediction(PredictionRequest request, long latencyMs) {
        this.id = UUID.randomUUID();
        this.orgId = request.getOrgId();
        this.cardItemId = request.getCardItemId();
        this.requestId = request.getId();
        this.provider = request.getProvider();
        this.externalRequestId = request.getExternalRequestId();
        this.photoIds = request.getPhotoIds();
        this.latencyMs = latencyMs;
        this.createdAt = Instant.now();
    }

    static ProviderPrediction succeeded(PredictionRequest request, PreGradeProvider.Reading reading, Map<String, Object> raw, long latencyMs) {
        ProviderPrediction p = new ProviderPrediction(request, latencyMs);
        p.succeeded = true;
        p.modelVersion = reading.modelVersion();
        p.overallGrade = reading.overallGrade();
        p.psaGrade = reading.psaGrade();
        p.centering = reading.centering();
        p.corners = reading.corners();
        p.edges = reading.edges();
        p.surface = reading.surface();
        p.details = reading.details();
        p.raw = raw;
        return p;
    }

    static ProviderPrediction rejected(PredictionRequest request, String reason, Map<String, Object> raw, long latencyMs) {
        ProviderPrediction p = new ProviderPrediction(request, latencyMs);
        p.succeeded = false;
        p.error = reason;
        p.raw = raw;
        return p;
    }

    /** The grade used for our distribution: the PSA-specific one when given, else the overall. */
    Double gradeForDistribution() {
        return psaGrade != null ? psaGrade : overallGrade;
    }

    /**
     * Providers we've stopped using. Their predictions stay stored (calibration data) and on the review
     * page, but never build a distribution or reach the seller again. Ximilar was removed on 2026-11-10
     * because its grades were too inaccurate (Zach).
     */
    static final java.util.Set<String> RETIRED = java.util.Set.of("XIMILAR");

    /** Each provider's newest successful answer, from predictions newest first, leaving out retired providers. */
    static Map<String, ProviderPrediction> latestSucceeded(List<ProviderPrediction> newestFirst) {
        Map<String, ProviderPrediction> latest = new LinkedHashMap<>();
        newestFirst.stream().filter(ProviderPrediction::isSucceeded).filter(p -> !RETIRED.contains(p.provider))
                .forEach(p -> latest.putIfAbsent(p.provider, p));
        return latest;
    }

    /**
     * The best grade the centering allows, from predictions newest first: the newest measurement's cap,
     * held down by the last measurement of each side, so a side a retake couldn't measure keeps its
     * limit. A photo that can't be measured says nothing about the card.
     *
     * @return the cap, or null with nothing measured
     */
    static Integer centeringCap(List<ProviderPrediction> newestFirst) {
        List<ProviderPrediction> measured = newestFirst.stream().filter(p -> p.centeringCap() != null).toList();
        if (measured.isEmpty()) {
            return null;
        }
        return Stream.of(measured.getFirst().centeringCap(), lastSideCap(measured, "frontCentering"), lastSideCap(measured, "backCentering"))
                .filter(Objects::nonNull).min(Integer::compare).orElseThrow();
    }

    private static Integer lastSideCap(List<ProviderPrediction> newestFirst, String side) {
        return newestFirst.stream().map(p -> p.sideCap(side)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * Most a stored {@code worstShare} can sit below the measured share: it is rounded to 0.1. Added back
     * when a side's cap has to be recomputed, so rounding can only tighten a cap, never loosen it.
     */
    static final double STORED_SHARE_ROUNDING = 0.05;

    /**
     * What one side of this measurement allows on its own, null if it wasn't measured: the cap stored
     * with the side. Measurements stored before that only have the share rounded to 0.1, so the cap is
     * recomputed from the highest share that could have rounded to it (54.0 is read as 54.05): never
     * looser than the measurement, at worst a grade tighter right at a boundary.
     */
    private Integer sideCap(String side) {
        if (details == null || !(details.get(side) instanceof Map<?, ?> measured)) {
            return null;
        }
        if (measured.get(CenteringPreGradeProvider.SIDE_CAP) instanceof Number cap) {
            return cap.intValue();
        }
        if (measured.get("worstShare") instanceof Number rounded && details.get("measurementMargin") instanceof Number margin) {
            double share = rounded.doubleValue() + STORED_SHARE_ROUNDING;
            return side.equals("frontCentering") ? PsaCentering.cap(share, null, margin.doubleValue())
                    : PsaCentering.cap(null, share, margin.doubleValue());
        }
        return null;
    }

    /** Our own centering measurement, as opposed to a provider's opinion of the card. */
    boolean isOwnMeasurement() {
        return CenteringPreGradeProvider.NAME.equals(provider);
    }

    /**
     * The best grade this reading's centering allows. Only our own measurement gives one: anyone
     * else's centering is a sub-grade, already part of its overall grade, even if that overall is missing.
     */
    Integer centeringCap() {
        return succeeded && isOwnMeasurement() && centering != null ? (int) Math.floor(centering) : null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    List<UUID> getPhotoIds() {
        return photoIds;
    }

    String getProvider() {
        return provider;
    }

    String getModelVersion() {
        return modelVersion;
    }

    boolean isSucceeded() {
        return succeeded;
    }

    Double getOverallGrade() {
        return overallGrade;
    }

    Double getPsaGrade() {
        return psaGrade;
    }

    Double getCentering() {
        return centering;
    }

    Double getCorners() {
        return corners;
    }

    Double getEdges() {
        return edges;
    }

    Double getSurface() {
        return surface;
    }

    Map<String, Object> getDetails() {
        return details;
    }

    String getError() {
        return error;
    }

    Long getLatencyMs() {
        return latencyMs;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

package com.pokemans.app.condition;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A provider's answer for one card, as received and as we read it. Append-only training and
 * calibration data: kept whether it turns out right or wrong, and also when the provider refused.
 */
@Entity
@Table(name = "provider_prediction")
class ProviderPrediction {

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

    /** Each provider's newest successful answer, from predictions newest first. */
    static Map<String, ProviderPrediction> latestSucceeded(List<ProviderPrediction> newestFirst) {
        Map<String, ProviderPrediction> latest = new LinkedHashMap<>();
        newestFirst.stream().filter(ProviderPrediction::isSucceeded).forEach(p -> latest.putIfAbsent(p.provider, p));
        return latest;
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

    UUID getId() {
        return id;
    }

    UUID getCardItemId() {
        return cardItemId;
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

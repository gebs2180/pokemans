package com.pokemans.app.condition;

import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Our condition distribution for one card (CLAUDE.md pipeline step 2). Versioned: every change is a
 * new row, the previous one stays, and exactly one is current. Reviewer changes record the
 * per-outcome difference and a reason; each is training data for the photo model.
 */
@Entity
@Table(name = "condition_assessment")
class ConditionAssessment {

    enum AuthorType { SYSTEM, REVIEWER }

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    private int version;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, BigDecimal> distribution;
    @Enumerated(EnumType.STRING)
    private Confidence confidence;
    @Enumerated(EnumType.STRING)
    private AuthorType authorType;
    private UUID authorId;
    private String reason;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, BigDecimal> adjustments;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> basis;
    private boolean isCurrent;
    private Instant createdAt;

    protected ConditionAssessment() {
    }

    /**
     * @param previous the current assessment this replaces, or null for the first
     */
    ConditionAssessment(UUID orgId, UUID cardItemId, ConditionAssessment previous, GradeDistribution distribution,
                        Confidence confidence, AuthorType authorType, UUID authorId, String reason, Map<String, Object> basis) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.version = previous == null ? 1 : previous.version + 1;
        this.distribution = toJson(distribution);
        this.confidence = confidence;
        this.authorType = authorType;
        this.authorId = authorId;
        this.reason = reason;
        this.adjustments = previous == null ? null : difference(previous.distribution(), distribution);
        this.basis = basis;
        this.isCurrent = true;
        this.createdAt = Instant.now();
    }

    void supersede() {
        this.isCurrent = false;
    }

    GradeDistribution distribution() {
        EnumMap<Grade, BigDecimal> map = new EnumMap<>(Grade.class);
        distribution.forEach((key, p) -> map.put(key.equals("U") ? Grade.U : Grade.of(Integer.parseInt(key)), p));
        return GradeDistribution.of(map);
    }

    static Map<String, BigDecimal> toJson(GradeDistribution distribution) {
        Map<String, BigDecimal> json = new LinkedHashMap<>();
        distribution.asMap().forEach((grade, p) -> json.put(grade.toString(), p));
        return json;
    }

    /** Non-zero per-outcome changes, new minus old. */
    private static Map<String, BigDecimal> difference(GradeDistribution before, GradeDistribution after) {
        Map<String, BigDecimal> diff = new LinkedHashMap<>();
        for (Grade grade : Grade.values()) {
            BigDecimal change = after.p(grade).subtract(before.p(grade));
            if (change.signum() != 0) {
                diff.put(grade.toString(), change);
            }
        }
        return diff;
    }

    UUID getId() {
        return id;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    int getVersion() {
        return version;
    }

    Map<String, BigDecimal> getDistribution() {
        return distribution;
    }

    Confidence getConfidence() {
        return confidence;
    }

    AuthorType getAuthorType() {
        return authorType;
    }

    UUID getAuthorId() {
        return authorId;
    }

    String getReason() {
        return reason;
    }

    Map<String, BigDecimal> getAdjustments() {
        return adjustments;
    }

    Map<String, Object> getBasis() {
        return basis;
    }

    boolean isCurrent() {
        return isCurrent;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

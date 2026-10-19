package com.pokemans.app.valuation;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One card item's line in a valuation run, valued or not. Append-only. */
@Entity
@Table(name = "card_valuation")
class CardValuationRecord {

    enum Status { VALUED, UNVALUED }

    @Id
    private UUID id;
    private UUID runId;
    private UUID orgId;
    private UUID cardItemId;
    private int position;
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;
    private String unvaluedReason;
    private UUID catalogCardId;
    private UUID assessmentId;
    private Integer assessmentVersion;
    private UUID snapshotSetId;
    private boolean graded;
    /** {@link CardInputs} as canonical JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String inputs;
    private BigDecimal fmv;
    private BigDecimal rv;
    private BigDecimal qsv;
    private BigDecimal stressedFmv;
    private BigDecimal flv;
    private String confidence;
    private boolean eligible;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> exclusionReasons;
    private BigDecimal contribution;
    private BigDecimal insuredRv;
    private String insurerNote;
    private BigDecimal flvGraded;
    private BigDecimal flvRawForLift;
    private BigDecimal gradingCost;
    private BigDecimal lift;
    private Integer gradeFirstRank;
    /** Trace steps as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String trace;

    protected CardValuationRecord() {
    }

    CardValuationRecord(UUID runId, UUID orgId, int position, ValuationEngine.CardResult result, String inputsJson, String traceJson) {
        CardInputs in = result.inputs();
        this.id = UUID.randomUUID();
        this.runId = runId;
        this.orgId = orgId;
        this.cardItemId = in.cardItemId();
        this.position = position;
        this.status = result.valuation() == null ? Status.UNVALUED : Status.VALUED;
        this.unvaluedReason = in.unvaluedReason();
        this.catalogCardId = in.identity() == null ? null : in.identity().catalogCardId();
        this.assessmentId = in.assessment() == null ? null : in.assessment().assessmentId();
        this.assessmentVersion = in.assessment() == null ? null : in.assessment().version();
        this.snapshotSetId = in.prices() == null ? null : in.prices().snapshotSetId();
        this.graded = in.graded() != null;
        this.inputs = inputsJson;
        if (result.valuation() != null) {
            this.fmv = result.valuation().fmv();
            this.rv = result.valuation().rv();
            this.qsv = result.valuation().qsv();
            this.stressedFmv = result.valuation().stressedFmv();
            this.flv = result.valuation().flv();
            this.confidence = result.valuation().confidence().name();
            this.eligible = result.contribution().eligible();
            this.exclusionReasons = result.contribution().exclusionReasons();
            this.contribution = result.contribution().contribution();
        } else {
            this.exclusionReasons = List.of(in.unvaluedReason());
            this.contribution = BigDecimal.ZERO;
        }
        this.insuredRv = result.insuredRv();
        this.insurerNote = result.insurerNote();
        if (result.gradeFirst() != null) {
            this.flvGraded = result.gradeFirst().flvGraded();
            this.flvRawForLift = result.gradeFirst().flvRaw();
            this.gradingCost = result.gradeFirst().cost();
            this.lift = result.gradeFirst().lift();
            this.gradeFirstRank = result.gradeFirstRank();
        }
        this.trace = traceJson;
    }

    UUID getId() {
        return id;
    }

    UUID getRunId() {
        return runId;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    int getPosition() {
        return position;
    }

    Status getStatus() {
        return status;
    }

    String getUnvaluedReason() {
        return unvaluedReason;
    }

    boolean isGraded() {
        return graded;
    }

    String getInputs() {
        return inputs;
    }

    BigDecimal getFmv() {
        return fmv;
    }

    BigDecimal getRv() {
        return rv;
    }

    BigDecimal getQsv() {
        return qsv;
    }

    BigDecimal getStressedFmv() {
        return stressedFmv;
    }

    BigDecimal getFlv() {
        return flv;
    }

    String getConfidence() {
        return confidence;
    }

    boolean isEligible() {
        return eligible;
    }

    List<String> getExclusionReasons() {
        return exclusionReasons;
    }

    BigDecimal getContribution() {
        return contribution;
    }

    BigDecimal getInsuredRv() {
        return insuredRv;
    }

    String getInsurerNote() {
        return insurerNote;
    }

    BigDecimal getFlvGraded() {
        return flvGraded;
    }

    BigDecimal getFlvRawForLift() {
        return flvRawForLift;
    }

    BigDecimal getGradingCost() {
        return gradingCost;
    }

    BigDecimal getLift() {
        return lift;
    }

    Integer getGradeFirstRank() {
        return gradeFirstRank;
    }

    String getTrace() {
        return trace;
    }
}

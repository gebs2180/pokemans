package com.pokemans.app.valuation;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One valuation of a collection: its inputs, the portfolio results and the trace. Append-only. */
@Entity
@Table(name = "valuation_run")
class ValuationRun {

    @Id
    private UUID id;
    private UUID orgId;
    private UUID collectionId;
    private String collectionName;
    private LocalDate asOf;
    private String parameterSetVersion;
    /** The parameter set's properties, as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String parameterSet;
    private String gradingCompany;
    @JdbcTypeCode(SqlTypes.JSON)
    private String gradingCost;
    private String inputsSha256;
    private int cardCount;
    private int valuedCount;
    private int eligibleCount;
    private BigDecimal eligibleFlv;
    private BigDecimal collateralBase;
    private BigDecimal rawShare;
    private BigDecimal gradedShare;
    private BigDecimal alphaLow;
    private BigDecimal alphaHigh;
    private BigDecimal advanceLow;
    private BigDecimal advanceHigh;
    private boolean capsApplied;
    private BigDecimal totalRv;
    @JdbcTypeCode(SqlTypes.JSON)
    private String portfolioTrace;
    private String note;
    private UUID createdBy;
    private Instant createdAt;

    protected ValuationRun() {
    }

    ValuationRun(UUID orgId, UUID collectionId, String collectionName, LocalDate asOf, String parameterSetVersion, String parameterSet, String gradingCompany,
                 String gradingCost, String inputsSha256, int cardCount, int valuedCount, int eligibleCount, BigDecimal eligibleFlv,
                 BigDecimal collateralBase, BigDecimal rawShare, BigDecimal gradedShare, BigDecimal alphaLow, BigDecimal alphaHigh,
                 BigDecimal advanceLow, BigDecimal advanceHigh, boolean capsApplied, BigDecimal totalRv, String portfolioTrace,
                 String note, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.collectionId = collectionId;
        this.collectionName = collectionName;
        this.asOf = asOf;
        this.parameterSetVersion = parameterSetVersion;
        this.parameterSet = parameterSet;
        this.gradingCompany = gradingCompany;
        this.gradingCost = gradingCost;
        this.inputsSha256 = inputsSha256;
        this.cardCount = cardCount;
        this.valuedCount = valuedCount;
        this.eligibleCount = eligibleCount;
        this.eligibleFlv = eligibleFlv;
        this.collateralBase = collateralBase;
        this.rawShare = rawShare;
        this.gradedShare = gradedShare;
        this.alphaLow = alphaLow;
        this.alphaHigh = alphaHigh;
        this.advanceLow = advanceLow;
        this.advanceHigh = advanceHigh;
        this.capsApplied = capsApplied;
        this.totalRv = totalRv;
        this.portfolioTrace = portfolioTrace;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getCollectionId() {
        return collectionId;
    }

    String getCollectionName() {
        return collectionName;
    }

    LocalDate getAsOf() {
        return asOf;
    }

    String getParameterSetVersion() {
        return parameterSetVersion;
    }

    String getParameterSet() {
        return parameterSet;
    }

    String getGradingCompany() {
        return gradingCompany;
    }

    String getGradingCost() {
        return gradingCost;
    }

    String getInputsSha256() {
        return inputsSha256;
    }

    int getCardCount() {
        return cardCount;
    }

    int getValuedCount() {
        return valuedCount;
    }

    int getEligibleCount() {
        return eligibleCount;
    }

    BigDecimal getEligibleFlv() {
        return eligibleFlv;
    }

    BigDecimal getCollateralBase() {
        return collateralBase;
    }

    BigDecimal getRawShare() {
        return rawShare;
    }

    BigDecimal getGradedShare() {
        return gradedShare;
    }

    BigDecimal getAlphaLow() {
        return alphaLow;
    }

    BigDecimal getAlphaHigh() {
        return alphaHigh;
    }

    BigDecimal getAdvanceLow() {
        return advanceLow;
    }

    BigDecimal getAdvanceHigh() {
        return advanceHigh;
    }

    boolean isCapsApplied() {
        return capsApplied;
    }

    BigDecimal getTotalRv() {
        return totalRv;
    }

    String getPortfolioTrace() {
        return portfolioTrace;
    }

    String getNote() {
        return note;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

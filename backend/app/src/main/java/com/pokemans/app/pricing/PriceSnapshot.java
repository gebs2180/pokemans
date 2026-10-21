package com.pokemans.app.pricing;

import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.GradePrice;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** The price of one grade of one card as of a date, and how it was set. Append-only. */
@Entity
@Table(name = "price_snapshot")
class PriceSnapshot implements Persistable<UUID> {

    @Id
    private UUID id;
    private UUID snapshotSetId;
    private UUID catalogCardId;
    private String grade;
    @Enumerated(EnumType.STRING)
    private PriceSource.GradingCompany gradingCompany;
    private LocalDate asOf;
    private BigDecimal price;
    private String currency;
    @Enumerated(EnumType.STRING)
    private GradePrice.Method method;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> compIds;
    private int n;
    private Integer windowDays;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> basisGrades;
    private BigDecimal stepDownRatio;
    @Enumerated(EnumType.STRING)
    private Confidence confidence;
    private String parameterSet;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> trace;
    private Instant createdAt;

    /** New until saved or loaded: saving inserts straight away (and batches) instead of merging, which selects first. */
    @Transient
    private boolean isNew = true;

    protected PriceSnapshot() {
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    PriceSnapshot(UUID snapshotSetId, UUID catalogCardId, LocalDate asOf, PriceSource.GradingCompany gradingCompany,
                  String currency, GradePrice price, BigDecimal stepDownRatio, String parameterSet, List<String> trace) {
        this.id = UUID.randomUUID();
        this.snapshotSetId = snapshotSetId;
        this.catalogCardId = catalogCardId;
        this.grade = price.grade().toString();
        // Every row carries its set's company, U included: each company's table is priced on its own.
        this.gradingCompany = gradingCompany;
        this.asOf = asOf;
        this.price = price.gross();
        this.currency = currency;
        this.method = price.method();
        this.compIds = price.compIds();
        this.n = price.salesInWindow();
        this.windowDays = price.windowDays() == 0 ? null : price.windowDays();
        this.basisGrades = price.basis().stream().map(Object::toString).toList();
        this.stepDownRatio = stepDownRatio;
        this.confidence = price.confidence();
        this.parameterSet = parameterSet;
        this.trace = List.copyOf(trace);
        this.createdAt = Instant.now();
    }

    UUID getSnapshotSetId() {
        return snapshotSetId;
    }

    UUID getCatalogCardId() {
        return catalogCardId;
    }

    String getGrade() {
        return grade;
    }

    PriceSource.GradingCompany getGradingCompany() {
        return gradingCompany;
    }

    LocalDate getAsOf() {
        return asOf;
    }

    BigDecimal getPrice() {
        return price;
    }

    String getCurrency() {
        return currency;
    }

    GradePrice.Method getMethod() {
        return method;
    }

    List<String> getCompIds() {
        return compIds;
    }

    int getN() {
        return n;
    }

    Integer getWindowDays() {
        return windowDays;
    }

    List<String> getBasisGrades() {
        return basisGrades;
    }

    BigDecimal getStepDownRatio() {
        return stepDownRatio;
    }

    Confidence getConfidence() {
        return confidence;
    }

    String getParameterSet() {
        return parameterSet;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

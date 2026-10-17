package com.pokemans.app.pricing;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One stored sold sale with its provenance. Append-only: no setters. */
@Entity
@Table(name = "sold_comp")
class SoldComp {

    @Id
    private UUID id;
    private String source;
    private String externalSaleId;
    private UUID catalogCardId;
    @Enumerated(EnumType.STRING)
    private PriceSource.GradingCompany gradingCompany;
    private BigDecimal grade;
    @Enumerated(EnumType.STRING)
    private PriceSource.RawCondition rawCondition;
    private BigDecimal price;
    private String currency;
    private LocalDate soldOn;
    @Enumerated(EnumType.STRING)
    private PriceSource.ListingType listingType;
    private String url;
    private String licence;
    private Instant retrievedAt;
    private UUID ingestRunId;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> raw;
    private Instant createdAt;

    protected SoldComp() {
    }

    SoldComp(PriceSource.Sale sale, String licence, Instant retrievedAt, UUID ingestRunId) {
        this.id = UUID.randomUUID();
        this.source = sale.source();
        this.externalSaleId = sale.externalSaleId();
        this.catalogCardId = sale.catalogCardId();
        this.gradingCompany = sale.gradingCompany();
        this.grade = sale.grade();
        this.rawCondition = sale.rawCondition();
        this.price = sale.price();
        this.currency = sale.currency();
        this.soldOn = sale.soldDate();
        this.listingType = sale.listingType();
        this.url = sale.url() == null ? null : sale.url().toString();
        this.licence = licence;
        this.retrievedAt = retrievedAt;
        this.ingestRunId = ingestRunId;
        this.raw = sale.raw();
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    String getSource() {
        return source;
    }

    UUID getCatalogCardId() {
        return catalogCardId;
    }

    PriceSource.GradingCompany getGradingCompany() {
        return gradingCompany;
    }

    BigDecimal getGrade() {
        return grade;
    }

    PriceSource.RawCondition getRawCondition() {
        return rawCondition;
    }

    BigDecimal getPrice() {
        return price;
    }

    String getCurrency() {
        return currency;
    }

    LocalDate getSoldOn() {
        return soldOn;
    }
}

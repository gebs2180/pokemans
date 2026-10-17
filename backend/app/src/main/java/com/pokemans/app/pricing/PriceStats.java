package com.pokemans.app.pricing;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Per-card market stats computed with a snapshot set: σ and sales per month. Append-only. */
@Entity
@Table(name = "price_stats")
class PriceStats {

    @Id
    private UUID id;
    private UUID snapshotSetId;
    private UUID catalogCardId;
    private LocalDate asOf;
    private BigDecimal sigma;
    private int sigmaChanges;
    private BigDecimal salesPerMonth;
    private Instant createdAt;

    protected PriceStats() {
    }

    PriceStats(UUID snapshotSetId, UUID catalogCardId, LocalDate asOf, BigDecimal sigma, int sigmaChanges,
               BigDecimal salesPerMonth) {
        this.id = UUID.randomUUID();
        this.snapshotSetId = snapshotSetId;
        this.catalogCardId = catalogCardId;
        this.asOf = asOf;
        this.sigma = sigma;
        this.sigmaChanges = sigmaChanges;
        this.salesPerMonth = salesPerMonth;
        this.createdAt = Instant.now();
    }

    BigDecimal getSigma() {
        return sigma;
    }

    int getSigmaChanges() {
        return sigmaChanges;
    }

    BigDecimal getSalesPerMonth() {
        return salesPerMonth;
    }
}

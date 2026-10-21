package com.pokemans.app.pricing;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Per-card market stats computed with a snapshot set: σ and sales per month. Append-only. */
@Entity
@Table(name = "price_stats")
class PriceStats implements Persistable<UUID> {

    @Id
    private UUID id;
    private UUID snapshotSetId;
    private UUID catalogCardId;
    private LocalDate asOf;
    private BigDecimal sigma;
    private int sigmaChanges;
    private BigDecimal salesPerMonth;
    private Instant createdAt;

    /** New until saved or loaded: saving inserts straight away (and batches) instead of merging, which selects first. */
    @Transient
    private boolean isNew = true;

    protected PriceStats() {
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

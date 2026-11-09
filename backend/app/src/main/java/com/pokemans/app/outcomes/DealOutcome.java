package com.pokemans.app.outcomes;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a buyer says happened to a card they bought: graded (with the grade in {@link GradeOutcome}),
 * resold raw, or kept. Immutable; a correction is a newer row for the same listing, and the newest
 * row is what happened. Every row is kept, wrong ones included.
 */
@Entity
@Table(name = "deal_outcome")
class DealOutcome extends AssignedIdEntity<UUID> {

    @Id
    private UUID id;
    private UUID listingId;
    private UUID offerId;
    private UUID cardItemId;
    private UUID buyerOrgId;
    @Enumerated(EnumType.STRING)
    private DealOutcomes.Kind kind;
    private UUID gradeOutcomeId;
    private BigDecimal resalePrice;
    private String note;
    private UUID createdBy;
    private Instant createdAt;

    protected DealOutcome() {
    }

    DealOutcome(DealOutcomes.Deal deal, DealOutcomes.Kind kind, UUID gradeOutcomeId, BigDecimal resalePrice, String note, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.listingId = deal.listingId();
        this.offerId = deal.offerId();
        this.cardItemId = deal.cardItemId();
        this.buyerOrgId = deal.buyerOrgId();
        this.kind = kind;
        this.gradeOutcomeId = gradeOutcomeId;
        this.resalePrice = resalePrice;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getListingId() {
        return listingId;
    }

    UUID getOfferId() {
        return offerId;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    UUID getBuyerOrgId() {
        return buyerOrgId;
    }

    DealOutcomes.Kind getKind() {
        return kind;
    }

    UUID getGradeOutcomeId() {
        return gradeOutcomeId;
    }

    BigDecimal getResalePrice() {
        return resalePrice;
    }

    String getNote() {
        return note;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

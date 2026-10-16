package com.pokemans.app.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One physical card, or a photographed lot of cheap cards. Belongs to exactly one batch or one
 * collection. Identification against the catalog comes in M2; until then the label is free text.
 */
@Entity
@Table(name = "card_item")
class CardItem {

    @Id
    private UUID id;
    private UUID orgId;
    private UUID batchId;
    private UUID collectionId;
    private String label;
    @Column(name = "is_lot")
    private boolean lot;
    private int quantity;
    private UUID createdBy;
    private Instant createdAt;

    protected CardItem() {
    }

    private CardItem(UUID orgId, UUID batchId, UUID collectionId, String label, boolean lot, int quantity, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.batchId = batchId;
        this.collectionId = collectionId;
        this.label = label;
        this.lot = lot;
        this.quantity = quantity;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    static CardItem inBatch(UUID orgId, UUID batchId, String label, boolean lot, int quantity, UUID createdBy) {
        return new CardItem(orgId, batchId, null, label, lot, quantity, createdBy);
    }

    static CardItem inCollection(UUID orgId, UUID collectionId, String label, boolean lot, int quantity, UUID createdBy) {
        return new CardItem(orgId, null, collectionId, label, lot, quantity, createdBy);
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getBatchId() {
        return batchId;
    }

    UUID getCollectionId() {
        return collectionId;
    }

    String getLabel() {
        return label;
    }

    boolean isLot() {
        return lot;
    }

    int getQuantity() {
        return quantity;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

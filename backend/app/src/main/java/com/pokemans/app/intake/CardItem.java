package com.pokemans.app.intake;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One physical card, or a photographed lot of cheap cards. Belongs to exactly one batch or one
 * collection.
 *
 * <p>The label is the name people see. A card is added before anyone knows its name (photos first): it
 * shows {@link #PLACEHOLDER_LABEL} until the collector's proposal or the admin's identification names it,
 * through {@link #name}. A lot is still described up front. Identity itself lives in the catalog module;
 * the label is only what to call the card.
 */
@Entity
@Table(name = "card_item")
class CardItem extends AssignedIdEntity<UUID> {

    /** What a card is called until it is named. */
    static final String PLACEHOLDER_LABEL = "New card";

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
    private Instant archivedAt;
    private UUID archivedBy;

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

    @Override
    public UUID getId() {
        return id;
    }

    UUID getCreatedBy() {
        return createdBy;
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

    /** The card's name, or {@link #PLACEHOLDER_LABEL} until it has one. */
    String getLabel() {
        return label != null ? label : PLACEHOLDER_LABEL;
    }

    boolean isNamed() {
        return label != null;
    }

    void name(String label) {
        this.label = label;
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

    boolean isArchived() {
        return archivedAt != null;
    }

    /** Takes the card off its collector's list. Its photos and predictions stay. */
    void archive(UUID by) {
        this.archivedAt = Instant.now();
        this.archivedBy = by;
    }
}

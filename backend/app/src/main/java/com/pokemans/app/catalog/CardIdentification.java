package com.pokemans.app.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Which catalog identity a physical card was given, by whom. Append-only: re-identifying adds a
 * row, so a wrong identification stays on record. The latest confirmed row is current.
 */
@Entity
@Table(name = "card_identification")
class CardIdentification {

    enum Source { REVIEWER, PROVIDER }

    enum Status { SUGGESTED, CONFIRMED }

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    private UUID catalogCardId;
    @Enumerated(EnumType.STRING)
    private Source source;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String note;
    private BigDecimal confidence;
    private UUID readingId;
    private UUID createdBy;
    private Instant createdAt;

    protected CardIdentification() {
    }

    static CardIdentification confirmedByReviewer(UUID orgId, UUID cardItemId, UUID catalogCardId, String note, UUID reviewer) {
        CardIdentification i = new CardIdentification();
        i.id = UUID.randomUUID();
        i.orgId = orgId;
        i.cardItemId = cardItemId;
        i.catalogCardId = catalogCardId;
        i.source = Source.REVIEWER;
        i.status = Status.CONFIRMED;
        i.note = note;
        i.createdBy = reviewer;
        i.createdAt = Instant.now();
        return i;
    }

    /** A reader's guess, for the reviewer to confirm or correct. */
    static CardIdentification suggested(UUID orgId, UUID cardItemId, UUID catalogCardId, BigDecimal confidence, UUID readingId,
                                        String note) {
        CardIdentification i = new CardIdentification();
        i.id = UUID.randomUUID();
        i.orgId = orgId;
        i.cardItemId = cardItemId;
        i.catalogCardId = catalogCardId;
        i.source = Source.PROVIDER;
        i.status = Status.SUGGESTED;
        i.confidence = confidence;
        i.readingId = readingId;
        i.note = note;
        i.createdAt = Instant.now();
        return i;
    }

    BigDecimal getConfidence() {
        return confidence;
    }

    UUID getReadingId() {
        return readingId;
    }

    UUID getId() {
        return id;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    UUID getCatalogCardId() {
        return catalogCardId;
    }

    Source getSource() {
        return source;
    }

    Status getStatus() {
        return status;
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

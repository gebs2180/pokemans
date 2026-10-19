package com.pokemans.app.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One reading of a card's front photo: queued, then what the reader saw (or why it failed). */
@Entity
@Table(name = "card_reading")
class CardReading {

    enum Status { PENDING, DONE, FAILED }

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    private UUID photoId;
    private String reader;
    @Enumerated(EnumType.STRING)
    private Status status;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private String name;
    private String number;
    private Integer setTotal;
    private String language;
    private Boolean firstEditionStamp;
    @Enumerated(EnumType.STRING)
    private Variant finish;
    private Integer copyrightYear;
    private String setName;
    private String notes;
    private String model;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> raw;
    private UUID requestedBy;
    private Instant createdAt;
    private Instant finishedAt;

    protected CardReading() {
    }

    CardReading(UUID orgId, UUID cardItemId, UUID photoId, String reader, UUID requestedBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.photoId = photoId;
        this.reader = reader;
        this.status = Status.PENDING;
        this.requestedBy = requestedBy;
        this.createdAt = Instant.now();
        this.nextAttemptAt = createdAt;
    }

    void done(CardReader.Reading r) {
        this.status = Status.DONE;
        this.name = r.name();
        this.number = r.number();
        this.setTotal = r.setTotal();
        this.language = r.language();
        this.firstEditionStamp = r.firstEditionStamp();
        this.finish = r.finish();
        this.copyrightYear = r.copyrightYear();
        this.setName = r.setName();
        this.notes = r.notes();
        this.model = r.model();
        this.raw = r.raw();
        this.lastError = null;
        this.finishedAt = Instant.now();
    }

    /** No point retrying (no crop to read, reader turned off): final. */
    void failed(String reason) {
        this.status = Status.FAILED;
        this.lastError = reason;
        this.finishedAt = Instant.now();
    }

    /** Read and stored, but matching it to the catalog failed: the reading stands, a reviewer identifies by hand. */
    void matchFailed(String error) {
        this.lastError = "Matching failed: " + error;
    }

    /** A call failed (outage, rate limit): retry with backoff, until the limit. */
    void callFailed(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error;
        if (attempts >= maxAttempts) {
            failed(error);
        } else {
            this.nextAttemptAt = Instant.now().plusSeconds(Math.min(3600, 30L << (attempts - 1)));
        }
    }

    /** What was read, for matching. */
    CardReader.Reading reading() {
        return new CardReader.Reading(name, number, setTotal, language, firstEditionStamp, finish, copyrightYear, setName,
                notes, model, raw);
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    UUID getPhotoId() {
        return photoId;
    }

    String getReader() {
        return reader;
    }

    Status getStatus() {
        return status;
    }

    int getAttempts() {
        return attempts;
    }

    String getLastError() {
        return lastError;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getFinishedAt() {
        return finishedAt;
    }
}

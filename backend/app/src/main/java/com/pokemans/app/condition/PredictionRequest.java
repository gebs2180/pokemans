package com.pokemans.app.condition;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One provider call to make for one card: queued, submitted, then done or failed. */
@Entity
@Table(name = "prediction_request")
class PredictionRequest {

    enum Status { PENDING, SUBMITTED, DONE, FAILED }

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    private String provider;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String externalRequestId;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<UUID> photoIds;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private UUID requestedBy;
    private Instant createdAt;
    private Instant finishedAt;

    protected PredictionRequest() {
    }

    PredictionRequest(UUID orgId, UUID cardItemId, String provider, List<UUID> photoIds, UUID requestedBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.provider = provider;
        this.status = Status.PENDING;
        this.photoIds = List.copyOf(photoIds);
        this.requestedBy = requestedBy;
        this.createdAt = Instant.now();
        this.nextAttemptAt = createdAt;
    }

    void submitted(String externalRequestId) {
        this.externalRequestId = externalRequestId;
        this.status = Status.SUBMITTED;
        this.lastError = null;
        this.nextAttemptAt = Instant.now().plusSeconds(10);
    }

    /** Still running at the provider: look again shortly. */
    void stillPending(Duration wait) {
        this.nextAttemptAt = Instant.now().plus(wait);
    }

    void done() {
        this.status = Status.DONE;
        this.finishedAt = Instant.now();
    }

    /** The provider gave a final "can't grade this": no retry. */
    void rejected(String reason) {
        this.status = Status.FAILED;
        this.lastError = reason;
        this.finishedAt = Instant.now();
    }

    /** A call failed (outage, timeout): retry with exponential backoff, until the limit. */
    void callFailed(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error;
        if (attempts >= maxAttempts) {
            this.status = Status.FAILED;
            this.finishedAt = Instant.now();
        } else {
            this.nextAttemptAt = Instant.now().plusSeconds(Math.min(3600, 30L << (attempts - 1)));
        }
    }

    boolean isOpen() {
        return status == Status.PENDING || status == Status.SUBMITTED;
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

    String getProvider() {
        return provider;
    }

    Status getStatus() {
        return status;
    }

    String getExternalRequestId() {
        return externalRequestId;
    }

    List<UUID> getPhotoIds() {
        return photoIds;
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

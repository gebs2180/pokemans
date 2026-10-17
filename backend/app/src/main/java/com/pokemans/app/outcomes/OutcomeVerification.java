package com.pokemans.app.outcomes;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A check of an outcome's cert against the grader's public lookup: does the cert exist, and does it
 * show this card at this grade? The latest check is the outcome's status. Done by hand for now; an
 * automated lookup waits on a review of the graders' API terms.
 */
@Entity
@Table(name = "outcome_verification")
class OutcomeVerification {

    enum Status { VERIFIED, MISMATCH }

    @Id
    private UUID id;
    private UUID outcomeId;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String note;
    private UUID verifiedBy;
    private Instant createdAt;

    protected OutcomeVerification() {
    }

    OutcomeVerification(UUID outcomeId, Status status, String note, UUID verifiedBy) {
        this.id = UUID.randomUUID();
        this.outcomeId = outcomeId;
        this.status = status;
        this.note = note;
        this.verifiedBy = verifiedBy;
        this.createdAt = Instant.now();
    }

    UUID getOutcomeId() {
        return outcomeId;
    }

    Status getStatus() {
        return status;
    }

    String getNote() {
        return note;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

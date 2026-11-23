package com.pokemans.app.identity;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** The admin's answer to a request to buy: who decided, when, and for a decline why. At most one per request, never changed. */
@Entity
@Table(name = "buyer_decision")
class BuyerDecision extends AssignedIdEntity<UUID> {

    @Id
    private UUID applicationId;
    private boolean approved;
    private UUID decidedBy;
    private Instant decidedAt;
    private String note;

    protected BuyerDecision() {
    }

    BuyerDecision(UUID applicationId, boolean approved, UUID decidedBy, String note) {
        this.applicationId = applicationId;
        this.approved = approved;
        this.decidedBy = decidedBy;
        this.decidedAt = Instant.now();
        this.note = note;
    }

    @Override
    public UUID getId() {
        return applicationId;
    }

    boolean isApproved() {
        return approved;
    }

    UUID getDecidedBy() {
        return decidedBy;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    String getNote() {
        return note;
    }
}

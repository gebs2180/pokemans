package com.pokemans.app.identity;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One request by a login to buy, on the buyer org it opened. Append-only; asking again after a decline is a new row. */
@Entity
@Table(name = "buyer_application")
class BuyerApplication extends AssignedIdEntity<UUID> {

    @Id
    private UUID id;
    private UUID orgId;
    private UUID userId;
    private int attempt;
    private Instant createdAt;

    protected BuyerApplication() {
    }

    BuyerApplication(UUID orgId, UUID userId, int attempt) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.userId = userId;
        this.attempt = attempt;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getUserId() {
        return userId;
    }

    int getAttempt() {
        return attempt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

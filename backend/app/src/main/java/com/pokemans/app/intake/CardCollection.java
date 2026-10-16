package com.pokemans.app.intake;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A collector's collection, valued as a whole. */
@Entity
@Table(name = "collection")
class CardCollection {

    @Id
    private UUID id;
    private UUID orgId;
    private String name;
    private UUID createdBy;
    private Instant createdAt;

    protected CardCollection() {
    }

    CardCollection(UUID orgId, String name, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getName() {
        return name;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

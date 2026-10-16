package com.pokemans.app.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A user's access to one org, created by accepting an invite. */
@Entity
@Table(name = "membership")
public class Membership {

    @Id
    private UUID id;
    private UUID orgId;
    private UUID userId;
    @Enumerated(EnumType.STRING)
    private Role role;
    private Instant createdAt;

    protected Membership() {
    }

    Membership(UUID orgId, UUID userId, Role role) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.userId = userId;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

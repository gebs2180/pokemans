package com.pokemans.app.identity;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A single-use invite into an org. Only the token's SHA-256 is stored. */
@Entity
@Table(name = "invite")
public class Invite extends AssignedIdEntity<UUID> {

    @Id
    private UUID id;
    private UUID orgId;
    @Enumerated(EnumType.STRING)
    private Role role;
    private String tokenHash;
    private UUID createdBy;
    private Instant createdAt;
    private Instant expiresAt;
    private UUID acceptedBy;
    private Instant acceptedAt;
    private Instant revokedAt;
    private UUID revokedBy;

    protected Invite() {
    }

    Invite(UUID orgId, Role role, String tokenHash, UUID createdBy, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.role = role;
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    boolean isUsable(Instant now) {
        return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    void revoke(UUID by, Instant now) {
        this.revokedBy = by;
        this.revokedAt = now;
    }

    void accept(UUID userId, Instant now) {
        this.acceptedBy = userId;
        this.acceptedAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public Role getRole() {
        return role;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

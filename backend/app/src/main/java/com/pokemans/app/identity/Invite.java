package com.pokemans.app.identity;

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
public class Invite {

    @Id
    private UUID id;
    private UUID orgId;
    @Enumerated(EnumType.STRING)
    private Role role;
    private String tokenHash;
    private String email;
    private UUID createdBy;
    private Instant createdAt;
    private Instant expiresAt;
    private UUID acceptedBy;
    private Instant acceptedAt;

    protected Invite() {
    }

    Invite(UUID orgId, Role role, String tokenHash, String email, UUID createdBy, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.role = role;
        this.tokenHash = tokenHash;
        this.email = email;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    boolean isUsable(Instant now) {
        return acceptedAt == null && now.isBefore(expiresAt);
    }

    void accept(UUID userId, Instant now) {
        this.acceptedBy = userId;
        this.acceptedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public Role getRole() {
        return role;
    }

    public String getEmail() {
        return email;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }
}

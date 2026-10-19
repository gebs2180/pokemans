package com.pokemans.app.provenance;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * An admin's finding on whether a physical card is genuine, made against the catalog identity the
 * card had at the time, and the photos it was based on. Append-only: a new finding is a new row, and
 * the latest one counts.
 */
@Entity
@Table(name = "authenticity_verification")
class AuthenticityVerification {

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    private UUID catalogCardId;
    @Enumerated(EnumType.STRING)
    private Provenance.Finding status;
    @Enumerated(EnumType.STRING)
    private Provenance.AuthMethod method;
    private String note;
    private UUID verifiedBy;
    private Instant createdAt;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "authenticity_verification_photo", joinColumns = @JoinColumn(name = "verification_id"))
    @Column(name = "photo_id")
    private Set<UUID> photoIds = new LinkedHashSet<>();

    protected AuthenticityVerification() {
    }

    AuthenticityVerification(UUID orgId, UUID cardItemId, UUID catalogCardId, Provenance.Finding status,
                             Provenance.AuthMethod method, String note, Collection<UUID> photoIds, UUID verifiedBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.catalogCardId = catalogCardId;
        this.status = status;
        this.method = method;
        this.note = note;
        this.verifiedBy = verifiedBy;
        this.createdAt = Instant.now();
        this.photoIds = new LinkedHashSet<>(photoIds);
    }

    /** The card's photos the finding was based on; empty if none were named. */
    Set<UUID> getPhotoIds() {
        return Set.copyOf(photoIds);
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

    Provenance.Finding getStatus() {
        return status;
    }

    Provenance.AuthMethod getMethod() {
        return method;
    }

    String getNote() {
        return note;
    }

    UUID getVerifiedBy() {
        return verifiedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

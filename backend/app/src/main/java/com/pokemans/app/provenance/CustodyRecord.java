package com.pokemans.app.provenance;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Who holds a physical card, where, and since when, and whether that has been checked. Append-only:
 * a move or a check is a new row, and the latest row is current custody.
 */
@Entity
@Table(name = "custody_record")
class CustodyRecord {

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    @Enumerated(EnumType.STRING)
    private Provenance.HolderType holderType;
    private String holderName;
    private String location;
    private LocalDate heldSince;
    private boolean verified;
    @Enumerated(EnumType.STRING)
    private Provenance.CustodyMethod verificationMethod;
    private UUID verifiedBy;
    private String note;
    private UUID recordedBy;
    private Instant createdAt;

    protected CustodyRecord() {
    }

    /** @param verificationMethod how custody was checked, or null if it wasn't */
    CustodyRecord(UUID orgId, UUID cardItemId, Provenance.HolderType holderType, String holderName, String location,
                  LocalDate heldSince, Provenance.CustodyMethod verificationMethod, String note, UUID recordedBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.holderType = holderType;
        this.holderName = holderName;
        this.location = location;
        this.heldSince = heldSince;
        this.verified = verificationMethod != null;
        this.verificationMethod = verificationMethod;
        this.verifiedBy = verified ? recordedBy : null;
        this.note = note;
        this.recordedBy = recordedBy;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    Provenance.HolderType getHolderType() {
        return holderType;
    }

    String getHolderName() {
        return holderName;
    }

    String getLocation() {
        return location;
    }

    LocalDate getHeldSince() {
        return heldSince;
    }

    boolean isVerified() {
        return verified;
    }

    Provenance.CustodyMethod getVerificationMethod() {
        return verificationMethod;
    }

    UUID getVerifiedBy() {
        return verifiedBy;
    }

    String getNote() {
        return note;
    }

    UUID getRecordedBy() {
        return recordedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

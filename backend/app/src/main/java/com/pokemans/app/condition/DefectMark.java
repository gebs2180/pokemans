package com.pokemans.app.condition;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A defect a reviewer marked on one photo's surface view: where (a share of the crop), what kind and how bad. Shown to the
 * collector read-only. It feeds neither the condition distribution nor any price: whether and how it should is a
 * methodology decision that hasn't been made. Soft-deleted, never removed: we store everything.
 */
@Entity
@Table(name = "defect_mark")
class DefectMark extends AssignedIdEntity<UUID> {

    enum Type { SCRATCH, PRINT_LINE, DENT_CREASE, WHITENING, STAIN, OTHER }

    enum Severity { MINOR, MODERATE, MAJOR }

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    private UUID photoId;
    private String side;
    private double x;
    private double y;
    @Enumerated(EnumType.STRING)
    private Type type;
    @Enumerated(EnumType.STRING)
    private Severity severity;
    private String note;
    private UUID createdBy;
    private Instant createdAt;
    private UUID deletedBy;
    private Instant deletedAt;

    protected DefectMark() {
    }

    DefectMark(UUID orgId, UUID cardItemId, UUID photoId, String side, double x, double y, Type type, Severity severity,
               String note, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.photoId = photoId;
        this.side = side;
        this.x = x;
        this.y = y;
        this.type = type;
        this.severity = severity;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    void softDelete(UUID by) {
        if (deletedAt == null) {
            this.deletedBy = by;
            this.deletedAt = Instant.now();
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    UUID getPhotoId() {
        return photoId;
    }

    String getSide() {
        return side;
    }

    double getX() {
        return x;
    }

    double getY() {
        return y;
    }

    Type getType() {
        return type;
    }

    Severity getSeverity() {
        return severity;
    }

    String getNote() {
        return note;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    boolean isDeleted() {
        return deletedAt != null;
    }
}

package com.pokemans.app.condition;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person's reading of one face of a card's printed frame, what {@link CenteringMeasurer} is scored
 * against. Append-only: the newest row for a card and face counts, and the older ones stay. Border
 * widths are physical, so a label applies to every photo of that face. A null width means the person
 * couldn't see that side's frame.
 */
@Entity
@Table(name = "centering_label")
class CenteringLabel extends AssignedIdEntity<UUID> {

    enum Face { FRONT, BACK }

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    @Enumerated(EnumType.STRING)
    private Face face;
    private UUID photoId;
    private Double leftMm;
    private Double rightMm;
    private Double topMm;
    private Double bottomMm;
    @Column(name = "hard_card")
    private boolean hardCard;
    private UUID labelledBy;
    private Instant createdAt;
    /** When the measurer's reading was shown: the moment of saving on the blind page, null before it existed. */
    private Instant revealedAt;

    protected CenteringLabel() {
    }

    /** @param photoId the photo it was drawn on */
    CenteringLabel(UUID orgId, UUID cardItemId, Face face, UUID photoId, Double leftMm, Double rightMm, Double topMm, Double bottomMm,
                   boolean hardCard, UUID labelledBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.face = face;
        this.photoId = photoId;
        this.leftMm = leftMm;
        this.rightMm = rightMm;
        this.topMm = topMm;
        this.bottomMm = bottomMm;
        this.hardCard = hardCard;
        this.labelledBy = labelledBy;
        this.createdAt = Instant.now();
        // Saving is the reveal: the person sees the measurer's reading only in the reply to this save.
        this.revealedAt = this.createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    Face getFace() {
        return face;
    }

    UUID getPhotoId() {
        return photoId;
    }

    Double getLeftMm() {
        return leftMm;
    }

    Double getRightMm() {
        return rightMm;
    }

    Double getTopMm() {
        return topMm;
    }

    Double getBottomMm() {
        return bottomMm;
    }

    boolean isHardCard() {
        return hardCard;
    }

    UUID getLabelledBy() {
        return labelledBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getRevealedAt() {
        return revealedAt;
    }
}

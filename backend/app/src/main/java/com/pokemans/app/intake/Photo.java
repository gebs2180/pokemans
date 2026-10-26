package com.pokemans.app.intake;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A photo of one side of a card. The row tracks the upload and its quality check; the stored
 * object itself is immutable, and a retake is a new photo.
 */
@Entity
@Table(name = "photo")
class Photo {

    enum Side { FRONT, BACK, DETAIL }

    enum Status { AWAITING_UPLOAD, PASSED, RETAKE }

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    @Enumerated(EnumType.STRING)
    private Side side;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String storageKey;
    private String contentType;
    private long declaredSize;
    private Long sizeBytes;
    private String sha256;
    private Integer width;
    private Integer height;
    private Double blurScore;
    private Double glareFraction;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<RetakeReason> retakeReasons = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    private List<CardDetector.Corner> cardCorners;
    private Double cardCoverage;
    private Double cardAspect;
    private Double cardBlurScore;
    private Double cardGlareFraction;
    private String cropKey;
    private Instant capturedAt;
    private UUID createdBy;
    private Instant createdAt;
    private Instant uploadedAt;
    private Instant checkedAt;

    protected Photo() {
    }

    Photo(UUID orgId, UUID cardItemId, Side side, String contentType, long declaredSize, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.side = side;
        this.status = Status.AWAITING_UPLOAD;
        this.storageKey = "orgs/" + orgId + "/cards/" + cardItemId + "/" + id;
        this.contentType = contentType;
        this.declaredSize = declaredSize;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** Key for this photo's card crop, a derivative stored next to the original. */
    String cropStorageKey() {
        return storageKey + ".card.jpg";
    }

    /** Key for the copy of a photo without a crop that others are shown: re-encoded, so without the upload's metadata. */
    String viewStorageKey() {
        return viewStorageKey(storageKey);
    }

    static String viewStorageKey(String storageKey) {
        return storageKey + ".view.jpg";
    }

    /**
     * Records the uploaded object and the quality check result.
     *
     * @param cropKey where the card crop was stored, or null when no card was found
     */
    void checked(long sizeBytes, String sha256, QualityChecker.Result quality, Instant capturedAt,
                 List<RetakeReason> extraReasons, String cropKey) {
        Instant now = Instant.now();
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.width = quality.width();
        this.height = quality.height();
        this.blurScore = quality.blurScore();
        this.glareFraction = quality.glareFraction();
        this.capturedAt = capturedAt;
        CardDetector.Detection card = quality.card();
        if (card != null) {
            this.cardCorners = card.corners();
            this.cardCoverage = card.coverage();
            this.cardAspect = card.aspect();
            this.cardBlurScore = card.blurScore();
            this.cardGlareFraction = card.glareFraction();
        }
        this.cropKey = cropKey;
        this.retakeReasons = new ArrayList<>(quality.reasons());
        this.retakeReasons.addAll(extraReasons);
        this.status = retakeReasons.isEmpty() ? Status.PASSED : Status.RETAKE;
        this.uploadedAt = now;
        this.checkedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    Side getSide() {
        return side;
    }

    Status getStatus() {
        return status;
    }

    String getStorageKey() {
        return storageKey;
    }

    String getContentType() {
        return contentType;
    }

    long getDeclaredSize() {
        return declaredSize;
    }

    String getSha256() {
        return sha256;
    }

    Integer getWidth() {
        return width;
    }

    Integer getHeight() {
        return height;
    }

    Double getBlurScore() {
        return blurScore;
    }

    Double getGlareFraction() {
        return glareFraction;
    }

    List<RetakeReason> getRetakeReasons() {
        return List.copyOf(retakeReasons);
    }

    List<CardDetector.Corner> getCardCorners() {
        return cardCorners;
    }

    Double getCardCoverage() {
        return cardCoverage;
    }

    Double getCardAspect() {
        return cardAspect;
    }

    Double getCardBlurScore() {
        return cardBlurScore;
    }

    Double getCardGlareFraction() {
        return cardGlareFraction;
    }

    String getCropKey() {
        return cropKey;
    }

    Instant getCapturedAt() {
        return capturedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUploadedAt() {
        return uploadedAt;
    }
}

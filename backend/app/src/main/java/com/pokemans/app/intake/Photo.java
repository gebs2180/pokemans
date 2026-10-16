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

    /** Records the uploaded object and the quality check result. */
    void checked(long sizeBytes, String sha256, QualityChecker.Result quality, Instant capturedAt, List<RetakeReason> extraReasons) {
        Instant now = Instant.now();
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.width = quality.width();
        this.height = quality.height();
        this.blurScore = quality.blurScore();
        this.glareFraction = quality.glareFraction();
        this.capturedAt = capturedAt;
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

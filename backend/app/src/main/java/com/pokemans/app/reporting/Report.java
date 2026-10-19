package com.pokemans.app.reporting;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An issued report PDF: which run it renders, where it is stored and its hash. Append-only. */
@Entity
@Table(name = "report")
class Report {

    enum Type { LENDER, INSURER }

    @Id
    private UUID id;
    private UUID runId;
    private UUID orgId;
    private UUID collectionId;
    @Enumerated(EnumType.STRING)
    private Type type;
    private BigDecimal lenderAlpha;
    private String templateVersion;
    private String storageKey;
    private String sha256;
    private long sizeBytes;
    private UUID issuedBy;
    private Instant issuedAt;

    protected Report() {
    }

    Report(UUID id, UUID runId, UUID orgId, UUID collectionId, Type type, BigDecimal lenderAlpha, String templateVersion,
           String storageKey, String sha256, long sizeBytes, UUID issuedBy, Instant issuedAt) {
        this.id = id;
        this.runId = runId;
        this.orgId = orgId;
        this.collectionId = collectionId;
        this.type = type;
        this.lenderAlpha = lenderAlpha;
        this.templateVersion = templateVersion;
        this.storageKey = storageKey;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.issuedBy = issuedBy;
        this.issuedAt = issuedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getRunId() {
        return runId;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getCollectionId() {
        return collectionId;
    }

    Type getType() {
        return type;
    }

    BigDecimal getLenderAlpha() {
        return lenderAlpha;
    }

    String getTemplateVersion() {
        return templateVersion;
    }

    String getStorageKey() {
        return storageKey;
    }

    String getSha256() {
        return sha256;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    Instant getIssuedAt() {
        return issuedAt;
    }
}

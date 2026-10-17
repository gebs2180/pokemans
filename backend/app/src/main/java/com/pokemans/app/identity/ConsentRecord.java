package com.pokemans.app.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A user's acceptance of one version of the data-use terms for one org. Append-only. */
@Entity
@Table(name = "consent_record")
class ConsentRecord {

    @Id
    private UUID id;
    private UUID orgId;
    private UUID userId;
    private String termsVersion;
    private String termsSha256;
    private Instant acceptedAt;

    protected ConsentRecord() {
    }

    ConsentRecord(UUID orgId, UUID userId, String termsVersion, String termsSha256) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.userId = userId;
        this.termsVersion = termsVersion;
        this.termsSha256 = termsSha256;
        this.acceptedAt = Instant.now();
    }

    String getTermsVersion() {
        return termsVersion;
    }

    Instant getAcceptedAt() {
        return acceptedAt;
    }
}

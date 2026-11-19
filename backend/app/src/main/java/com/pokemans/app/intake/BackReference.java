package com.pokemans.app.intake;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One version of the reference back the clean back is made against: the per-pixel median of several aligned BACK surface
 * crops of our own cards. Each rebuild is a new version; older ones stay.
 */
@Entity
@Table(name = "back_reference")
class BackReference extends AssignedIdEntity<Integer> {

    @Id
    private Integer version;
    private String storageKey;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<UUID> candidateIds;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<UUID> usedPhotoIds;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<UUID, String> rejected;
    private boolean seeded;
    private UUID createdBy;
    private Instant createdAt;

    protected BackReference() {
    }

    BackReference(int version, List<UUID> candidateIds, List<UUID> usedPhotoIds, Map<UUID, String> rejected, boolean seeded,
                  UUID createdBy) {
        this.version = version;
        this.storageKey = storageKey(version);
        this.candidateIds = List.copyOf(candidateIds);
        this.usedPhotoIds = List.copyOf(usedPhotoIds);
        this.rejected = rejected;
        this.seeded = seeded;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** In the photo bucket, beside no card's photos. */
    static String storageKey(int version) {
        return "reference/backs/v" + version + ".png";
    }

    @Override
    public Integer getId() {
        return version;
    }

    int getVersion() {
        return version;
    }

    String getStorageKey() {
        return storageKey;
    }

    List<UUID> getUsedPhotoIds() {
        return usedPhotoIds;
    }

    Map<UUID, String> getRejected() {
        return rejected;
    }

    boolean isSeeded() {
        return seeded;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

package com.pokemans.app.pricing;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One admin's mapping of a catalog card to a PokeTrace card, or unmapping (no PokeTrace id). Append-only. */
@Entity
@Table(name = "poketrace_card")
class PokeTraceCard extends AssignedIdEntity<UUID> {

    @Id
    private UUID id;
    private UUID catalogCardId;
    private String poketraceId;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> poketraceCard;
    private String overrideReason;
    private UUID mappedBy;
    private Instant createdAt;

    protected PokeTraceCard() {
    }

    PokeTraceCard(UUID catalogCardId, String poketraceId, Map<String, Object> poketraceCard, String overrideReason, UUID mappedBy) {
        this.id = UUID.randomUUID();
        this.catalogCardId = catalogCardId;
        this.poketraceId = poketraceId;
        this.poketraceCard = poketraceCard;
        this.overrideReason = overrideReason;
        this.mappedBy = mappedBy;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getCatalogCardId() {
        return catalogCardId;
    }

    String getPoketraceId() {
        return poketraceId;
    }

    Map<String, Object> getPoketraceCard() {
        return poketraceCard;
    }

    String getOverrideReason() {
        return overrideReason;
    }

    UUID getMappedBy() {
        return mappedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

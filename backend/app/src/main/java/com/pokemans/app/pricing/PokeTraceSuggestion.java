package com.pokemans.app.pricing;

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
 * What an automatic PokeTrace search found for a catalog card. It never prices: only an admin's
 * mapping does. Append-only; the newest row counts.
 */
@Entity
@Table(name = "poketrace_suggestion")
class PokeTraceSuggestion extends AssignedIdEntity<UUID> {

    @Id
    private UUID id;
    private UUID catalogCardId;
    private String suggestedId;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> candidates;
    private String error;
    private Instant createdAt;

    protected PokeTraceSuggestion() {
    }

    PokeTraceSuggestion(UUID catalogCardId, String suggestedId, List<Map<String, Object>> candidates, String error) {
        this.id = UUID.randomUUID();
        this.catalogCardId = catalogCardId;
        this.suggestedId = suggestedId;
        this.candidates = candidates;
        this.error = error;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getCatalogCardId() {
        return catalogCardId;
    }

    String getSuggestedId() {
        return suggestedId;
    }

    List<Map<String, Object>> getCandidates() {
        return candidates;
    }

    String getError() {
        return error;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

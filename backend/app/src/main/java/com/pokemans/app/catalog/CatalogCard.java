package com.pokemans.app.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A canonical card identity (CLAUDE.md pipeline step 1): set, number, language, edition, print
 * variant, stamp and promo status. Prices and grade data attach to this, not to the physical card.
 * Immutable once created; a different edition or variant is a different row.
 */
@Entity
@Table(name = "catalog_card")
class CatalogCard {

    enum Provider { TCGDEX, MANUAL }

    @Id
    private UUID id;
    private String identityKey;
    @Enumerated(EnumType.STRING)
    private Provider provider;
    private String externalId;
    private String language;
    private String setId;
    private String setName;
    private String seriesName;
    private LocalDate setReleaseDate;
    private String number;
    private String name;
    private String rarity;
    @Enumerated(EnumType.STRING)
    private Edition edition;
    @Enumerated(EnumType.STRING)
    private Variant variant;
    private String stamp;
    private boolean promo;
    @Enumerated(EnumType.STRING)
    private AuthenticityRisk authenticityRisk;
    private String imageUrl;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> providerPayload;
    private UUID createdBy;
    private Instant createdAt;

    protected CatalogCard() {
    }

    CatalogCard(CardIdentity identity, AuthenticityRisk authenticityRisk, Map<String, Object> providerPayload, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.identityKey = identity.key();
        this.provider = identity.provider();
        this.externalId = identity.externalId();
        this.language = identity.language();
        this.setId = identity.setId();
        this.setName = identity.setName();
        this.seriesName = identity.seriesName();
        this.setReleaseDate = identity.setReleaseDate();
        this.number = identity.number();
        this.name = identity.name();
        this.rarity = identity.rarity();
        this.edition = identity.edition();
        this.variant = identity.variant();
        this.stamp = identity.stamp();
        this.promo = identity.promo();
        this.authenticityRisk = authenticityRisk;
        this.imageUrl = identity.imageUrl();
        this.providerPayload = providerPayload;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    Provider getProvider() {
        return provider;
    }

    String getExternalId() {
        return externalId;
    }

    String getLanguage() {
        return language;
    }

    String getSetId() {
        return setId;
    }

    String getSetName() {
        return setName;
    }

    String getSeriesName() {
        return seriesName;
    }

    LocalDate getSetReleaseDate() {
        return setReleaseDate;
    }

    String getNumber() {
        return number;
    }

    String getName() {
        return name;
    }

    String getRarity() {
        return rarity;
    }

    Edition getEdition() {
        return edition;
    }

    Variant getVariant() {
        return variant;
    }

    String getStamp() {
        return stamp;
    }

    boolean isPromo() {
        return promo;
    }

    AuthenticityRisk getAuthenticityRisk() {
        return authenticityRisk;
    }

    String getImageUrl() {
        return imageUrl;
    }

    /**
     * National Pokedex numbers of the Pokemon on the card, as the provider gave them (TCGdex's
     * {@code dexId}: one for most cards, several for a tag team). Empty for manual entries, trainers
     * and energy.
     */
    java.util.List<Integer> getDexIds() {
        Object ids = providerPayload == null ? null : providerPayload.get("dexId");
        if (!(ids instanceof java.util.List<?> list)) {
            return java.util.List.of();
        }
        return list.stream().filter(Number.class::isInstance).map(n -> ((Number) n).intValue()).toList();
    }
}

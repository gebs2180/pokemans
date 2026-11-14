package com.pokemans.app.catalog;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The collector's pick from the catalog for a card they added: a proposed identity for the admin to
 * confirm or correct, never an identification. Nothing prices, flags or verifies on it. Append-only:
 * a changed pick adds a row, and the latest is the one shown.
 *
 * <p>Edition and variant are null where the catalog listed more than one and the collector wasn't
 * sure: we never guess them.
 */
@Entity
@Table(name = "card_identity_proposal")
class IdentityProposal extends AssignedIdEntity<UUID> {

    @Id
    private UUID id;
    private UUID orgId;
    private UUID cardItemId;
    @Enumerated(EnumType.STRING)
    private CatalogCard.Provider provider;
    private String externalId;
    private String language;
    private String name;
    private String setName;
    private String number;
    private LocalDate setReleaseDate;
    private String imageUrl;
    @Enumerated(EnumType.STRING)
    private Edition edition;
    @Enumerated(EnumType.STRING)
    private Variant variant;
    private String stamp;
    private boolean stampUnsure;
    private UUID proposedBy;
    private Instant createdAt;

    protected IdentityProposal() {
    }

    IdentityProposal(UUID orgId, UUID cardItemId, CatalogProvider.ProviderCard card, Edition edition, Variant variant,
                     String stamp, boolean stampUnsure, UUID proposedBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.provider = CatalogCard.Provider.TCGDEX;
        this.externalId = card.externalId();
        this.language = card.language();
        this.name = card.name();
        this.setName = card.setName();
        this.number = card.number();
        this.setReleaseDate = card.setReleaseDate();
        this.imageUrl = card.imageUrl();
        this.edition = edition;
        this.variant = variant;
        this.stamp = stamp;
        this.stampUnsure = stampUnsure;
        this.proposedBy = proposedBy;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getCardItemId() {
        return cardItemId;
    }

    CatalogCard.Provider getProvider() {
        return provider;
    }

    String getExternalId() {
        return externalId;
    }

    String getLanguage() {
        return language;
    }

    String getName() {
        return name;
    }

    String getSetName() {
        return setName;
    }

    String getNumber() {
        return number;
    }

    LocalDate getSetReleaseDate() {
        return setReleaseDate;
    }

    String getImageUrl() {
        return imageUrl;
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

    boolean isStampUnsure() {
        return stampUnsure;
    }

    UUID getProposedBy() {
        return proposedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

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
 * What the collector says a card they added is: a proposed identity for the admin to confirm or
 * correct, never an identification. Nothing prices, flags or verifies on it. Append-only: a changed
 * answer adds a row, and the latest is the one shown.
 *
 * <p>It is the reader's suggestion confirmed ({@link Source#SUGGESTION}), a card picked by search
 * ({@link Source#SEARCH}), or, when neither found it, the collector's own words ({@link Source#DESCRIBED}),
 * which carry no catalog card. It keeps the reading the collector was shown, so a wrong suggestion
 * stays on record next to what the collector said instead.
 *
 * <p>Edition and variant are null where the catalog listed more than one and the collector wasn't
 * sure: we never guess them.
 */
@Entity
@Table(name = "card_identity_proposal")
class IdentityProposal extends AssignedIdEntity<UUID> {

    enum Source {
        SUGGESTION, SEARCH, DESCRIBED
    }

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
    private Boolean stampUnsure;
    @Enumerated(EnumType.STRING)
    private Source source;
    private UUID readingId;
    private UUID suggestionId;
    private String description;
    private UUID proposedBy;
    private Instant createdAt;

    protected IdentityProposal() {
    }

    /** A catalog card: confirmed from a suggestion ({@code suggestionId} set) or picked by search. */
    IdentityProposal(UUID orgId, UUID cardItemId, CatalogProvider.ProviderCard card, Edition edition, Variant variant,
                     String stamp, boolean stampUnsure, UUID readingId, UUID suggestionId, UUID proposedBy) {
        this(orgId, cardItemId, suggestionId != null ? Source.SUGGESTION : Source.SEARCH, readingId, proposedBy);
        this.suggestionId = suggestionId;
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
    }

    /** The collector's own words, when neither the reader nor the search found the card. */
    static IdentityProposal described(UUID orgId, UUID cardItemId, String description, UUID readingId, UUID proposedBy) {
        IdentityProposal p = new IdentityProposal(orgId, cardItemId, Source.DESCRIBED, readingId, proposedBy);
        p.description = description;
        return p;
    }

    private IdentityProposal(UUID orgId, UUID cardItemId, Source source, UUID readingId, UUID proposedBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.cardItemId = cardItemId;
        this.source = source;
        this.readingId = readingId;
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

    /** Null for a description. */
    Boolean getStampUnsure() {
        return stampUnsure;
    }

    Source getSource() {
        return source;
    }

    UUID getReadingId() {
        return readingId;
    }

    UUID getSuggestionId() {
        return suggestionId;
    }

    String getDescription() {
        return description;
    }

    UUID getProposedBy() {
        return proposedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

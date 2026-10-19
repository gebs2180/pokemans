package com.pokemans.app.catalog;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Searching the catalog and recording which identity a physical card has. */
@Service
class IdentificationService {

    /**
     * What the reviewer chose. For a provider card, {@code externalId} names it and the provider
     * supplies set and name; for a manual entry, the reviewer types those in.
     *
     * @param promo null to take the provider's promo flag
     */
    record Choice(CatalogCard.Provider provider, String externalId, String language, Edition edition, Variant variant,
                  String stamp, Boolean promo, String setName, String number, String name, String rarity,
                  LocalDate setReleaseDate, String note) {
    }

    private final Optional<CatalogProvider> provider;
    private final CatalogCardRepository catalogCards;
    private final CardIdentificationRepository identifications;
    private final CardItems cardItems;
    private final CurrentUser currentUser;

    IdentificationService(Optional<CatalogProvider> provider, CatalogCardRepository catalogCards,
                          CardIdentificationRepository identifications, CardItems cardItems, CurrentUser currentUser) {
        this.provider = provider;
        this.catalogCards = catalogCards;
        this.identifications = identifications;
        this.cardItems = cardItems;
        this.currentUser = currentUser;
    }

    List<CatalogProvider.Hit> search(String name, String number, String language) {
        return call(() -> requireProvider().search(name, number, language));
    }

    CatalogProvider.ProviderCard providerCard(String externalId, String language) {
        return call(() -> requireProvider().card(externalId, language))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found in the catalog"));
    }

    /** Records the reviewer's identification, creating the catalog identity on first use. */
    @Transactional
    CardIdentification identify(UUID orgId, UUID cardId, Choice choice) {
        CardItems.CardItemRef item = cardItems.get(cardId);
        if (!item.orgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found");
        }
        CardIdentity identity;
        Map<String, Object> payload = null;
        if (choice.provider() == CatalogCard.Provider.MANUAL) {
            identity = manual(choice);
        } else {
            if (isBlank(choice.externalId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "externalId is required for a catalog card");
            }
            CatalogProvider.ProviderCard card = providerCard(choice.externalId(), choice.language());
            identity = identity(card, choice.edition(), choice.variant(), blankToNull(choice.stamp()), choice.promo());
            payload = card.payload();
        }
        CatalogCard catalogCard = findOrCreate(identity, payload, currentUser.id());
        return identifications.save(CardIdentification.confirmedByReviewer(orgId, cardId, catalogCard.getId(),
                blankToNull(choice.note()), currentUser.id()));
    }

    /** The catalog identity with this key, created on first use. {@code createdBy} is null for a reader's suggestion. */
    CatalogCard findOrCreate(CardIdentity identity, Map<String, Object> payload, UUID createdBy) {
        Optional<CatalogCard> existing = catalogCards.findByIdentityKey(identity.key());
        return existing.isPresent() ? existing.get() : catalogCards.save(
                new CatalogCard(identity, AuthenticityRisk.of(identity, LocalDate.now(ZoneOffset.UTC)), payload, createdBy));
    }

    /** The identity a provider card and chosen print make. */
    static CardIdentity identity(CatalogProvider.ProviderCard card, Edition edition, Variant variant, String stamp, Boolean promo) {
        return new CardIdentity(CatalogCard.Provider.TCGDEX, card.externalId(), card.language(), card.setId(),
                card.setName(), card.seriesName(), card.setReleaseDate(), card.number(), card.name(), card.rarity(),
                edition, variant, stamp, promo != null ? promo : card.promo(), card.imageUrl());
    }

    /** Current identification first, then earlier ones. */
    @Transactional(readOnly = true)
    List<CardIdentification> history(UUID cardId) {
        return identifications.findByCardItemIdOrderByCreatedAtDesc(cardId);
    }

    @Transactional(readOnly = true)
    Optional<CatalogCard> catalogCard(UUID id) {
        return catalogCards.findById(id);
    }

    private static CardIdentity manual(Choice choice) {
        if (isBlank(choice.setName()) || isBlank(choice.number()) || isBlank(choice.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Set, number and name are required for a manual entry");
        }
        // The release date drives the vintage and recent-chase authenticity flags, so it can't be left out.
        if (choice.setReleaseDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The set's release date is required for a manual entry");
        }
        if (choice.setReleaseDate().isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The set's release date can't be in the future");
        }
        return new CardIdentity(CatalogCard.Provider.MANUAL, null, choice.language(), null, choice.setName().trim(), null,
                choice.setReleaseDate(), choice.number().trim(), choice.name().trim(), blankToNull(choice.rarity()),
                choice.edition(), choice.variant(), blankToNull(choice.stamp()), Boolean.TRUE.equals(choice.promo()), null);
    }

    private CatalogProvider requireProvider() {
        return provider.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "No catalog provider is configured; enter the card by hand"));
    }

    /** A provider outage becomes a 502 the reviewer can act on (enter by hand), not a 500. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The card catalog didn't answer; try again or enter the card by hand", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}

package com.pokemans.app.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What other modules may know about card identities. No access checks: callers have done them. */
@Service
public class Catalog {

    /**
     * A physical card's current identity.
     *
     * @param identifiedSince when the card was first confirmed as this catalog card, counting from the
     *                        last time it was confirmed as a different one: findings made before then
     *                        were about another identity
     */
    public record IdentifiedCard(UUID cardItemId, UUID catalogCardId, String language, String setName, String seriesName,
                                 LocalDate setReleaseDate, String number, String name, String rarity, Edition edition,
                                 Variant variant, String stamp, boolean promo, AuthenticityRisk authenticityRisk,
                                 String imageUrl, Instant identifiedSince) {
    }

    /** A canonical card identity, for modules that work per catalog card (pricing). */
    public record CardSummary(UUID catalogCardId, String externalId, String language, String setName, LocalDate setReleaseDate,
                              String number, String name, Edition edition, Variant variant, String stamp, boolean promo) {
    }

    private final CardIdentificationRepository identifications;
    private final CatalogCardRepository catalogCards;

    Catalog(CardIdentificationRepository identifications, CatalogCardRepository catalogCards) {
        this.identifications = identifications;
        this.catalogCards = catalogCards;
    }

    @Transactional(readOnly = true)
    public Optional<IdentifiedCard> current(UUID cardItemId) {
        return Optional.ofNullable(current(List.of(cardItemId)).get(cardItemId));
    }

    /** Current identity per card, for the cards that have one. */
    @Transactional(readOnly = true)
    public Map<UUID, IdentifiedCard> current(Collection<UUID> cardItemIds) {
        if (cardItemIds.isEmpty()) {
            return Map.of();
        }
        // Confirmed identifications, newest first per card: the first is current, and the run of rows
        // with the same catalog card behind it says since when.
        Map<UUID, List<CardIdentification>> confirmed = identifications
                .findByCardItemIdInAndStatusOrderByCreatedAtDesc(cardItemIds, CardIdentification.Status.CONFIRMED).stream()
                .collect(Collectors.groupingBy(CardIdentification::getCardItemId));
        Map<UUID, CatalogCard> cards = catalogCards.findAllById(confirmed.values().stream()
                        .map(list -> list.getFirst().getCatalogCardId()).distinct().toList())
                .stream().collect(Collectors.toMap(CatalogCard::getId, Function.identity()));
        return confirmed.values().stream().collect(Collectors.toMap(list -> list.getFirst().getCardItemId(), list -> {
            CardIdentification current = list.getFirst();
            Instant since = current.getCreatedAt();
            for (CardIdentification earlier : list) {
                if (!earlier.getCatalogCardId().equals(current.getCatalogCardId())) {
                    break;
                }
                since = earlier.getCreatedAt();
            }
            CatalogCard c = cards.get(current.getCatalogCardId());
            return new IdentifiedCard(current.getCardItemId(), c.getId(), c.getLanguage(), c.getSetName(), c.getSeriesName(),
                    c.getSetReleaseDate(), c.getNumber(), c.getName(), c.getRarity(), c.getEdition(), c.getVariant(),
                    c.getStamp(), c.isPromo(), c.getAuthenticityRisk(), c.getImageUrl(), since);
        }));
    }

    /** Catalog cards that some physical card is currently identified as: the cards worth pricing. */
    @Transactional(readOnly = true)
    public List<CardSummary> identifiedCards() {
        return cards(identifications.findCurrentCatalogCardIds());
    }

    @Transactional(readOnly = true)
    public List<CardSummary> cards(Collection<UUID> catalogCardIds) {
        if (catalogCardIds.isEmpty()) {
            return List.of();
        }
        return catalogCards.findAllById(catalogCardIds).stream()
                .map(c -> new CardSummary(c.getId(), c.getExternalId(), c.getLanguage(), c.getSetName(), c.getSetReleaseDate(),
                        c.getNumber(), c.getName(), c.getEdition(), c.getVariant(), c.getStamp(), c.isPromo()))
                .toList();
    }
}

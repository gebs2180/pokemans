package com.pokemans.app.catalog;

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

    /** A physical card's current identity. */
    public record IdentifiedCard(UUID cardItemId, UUID catalogCardId, String language, String setName, String seriesName,
                                 LocalDate setReleaseDate, String number, String name, String rarity, Edition edition,
                                 Variant variant, String stamp, boolean promo, AuthenticityRisk authenticityRisk,
                                 String imageUrl) {
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
        var latest = identifications.findCurrent(cardItemIds);
        Map<UUID, CatalogCard> cards = catalogCards.findAllById(latest.stream().map(CardIdentification::getCatalogCardId).toList())
                .stream().collect(Collectors.toMap(CatalogCard::getId, Function.identity()));
        return latest.stream().collect(Collectors.toMap(CardIdentification::getCardItemId, i -> {
            CatalogCard c = cards.get(i.getCatalogCardId());
            return new IdentifiedCard(i.getCardItemId(), c.getId(), c.getLanguage(), c.getSetName(), c.getSeriesName(),
                    c.getSetReleaseDate(), c.getNumber(), c.getName(), c.getRarity(), c.getEdition(), c.getVariant(),
                    c.getStamp(), c.isPromo(), c.getAuthenticityRisk(), c.getImageUrl());
        }));
    }
}

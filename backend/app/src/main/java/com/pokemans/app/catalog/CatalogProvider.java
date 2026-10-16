package com.pokemans.app.catalog;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * External card catalog (HLD: {@code CatalogProvider}). The reviewer searches it and picks a card;
 * when no provider is reachable or the card isn't listed, the reviewer enters it by hand instead.
 */
interface CatalogProvider {

    /** Which {@link CatalogCard.Provider} this is. */
    CatalogCard.Provider id();

    /**
     * Cards whose name matches, optionally only those with this collector number.
     *
     * @param language ISO 639-1 code, e.g. "en" or "ja"
     */
    List<Hit> search(String name, String number, String language);

    /** Full detail for one provider card, or empty if the provider doesn't know it. */
    Optional<ProviderCard> card(String externalId, String language);

    /** A search result: enough to show a thumbnail and pick. */
    record Hit(String externalId, String number, String name, String setId, String imageUrl) {
    }

    /**
     * @param editions    editions this card was printed in
     * @param variants    finishes this card was printed in
     * @param stamps      distinguishing stamps seen on some prints
     * @param promo       whether the provider lists it as a promo
     * @param payload     the provider's response as received, kept for the record
     */
    record ProviderCard(String externalId, String language, String setId, String setName, String seriesName,
                        LocalDate setReleaseDate, String number, String name, String rarity, List<Edition> editions,
                        List<Variant> variants, List<String> stamps, boolean promo, String imageUrl,
                        Map<String, Object> payload) {
    }
}

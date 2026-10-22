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
     * Cards whose name matches, optionally only those with this collector number. A number given as
     * printed ("4/102") puts the sets of that size first.
     *
     * @param language ISO 639-1 code, e.g. "en" or "ja"
     */
    List<Hit> search(String name, String number, String language);

    /** Full detail for one provider card, or empty if the provider doesn't know it. */
    Optional<ProviderCard> card(String externalId, String language);

    /**
     * A search result: enough to show a thumbnail and pick.
     *
     * @param setName  the set's name, e.g. "Base Set", or null if unknown
     * @param setTotal the set size printed after the slash (102 in 4/102), or null if unknown
     */
    record Hit(String externalId, String number, String name, String setId, String imageUrl, String setName, Integer setTotal) {
        Hit(String externalId, String number, String name, String setId, String imageUrl) {
            this(externalId, number, name, setId, imageUrl, null, null);
        }
    }

    /**
     * @param editions    editions this card was printed in
     * @param variants    finishes this card was printed in
     * @param stamps      distinguishing stamps seen on some prints
     * @param promo       whether the provider lists it as a promo
     * @param payload     the provider's response as received, kept for the record
     * @param setOfficialCount the set size printed after the slash (113 in 11/113), when known
     */
    record ProviderCard(String externalId, String language, String setId, String setName, String seriesName,
                        LocalDate setReleaseDate, String number, String name, String rarity, List<Edition> editions,
                        List<Variant> variants, List<String> stamps, boolean promo, String imageUrl,
                        Map<String, Object> payload, Integer setOfficialCount) {
    }
}

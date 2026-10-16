package com.pokemans.app.catalog;

import java.time.LocalDate;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The fields that make up a canonical identity, before it is stored.
 *
 * @param externalId provider card id, or null for a manual entry
 * @param stamp      a print stamp that sets the card apart (e.g. "set-logo", "staff"), or null
 */
record CardIdentity(CatalogCard.Provider provider, String externalId, String language, String setId, String setName,
                    String seriesName, LocalDate setReleaseDate, String number, String name, String rarity,
                    Edition edition, Variant variant, String stamp, boolean promo, String imageUrl) {

    /**
     * Same physical identity, same key. A provider card is keyed by its id; a manual entry by set and
     * number, so two reviewers typing the same card by hand land on one row.
     */
    String key() {
        String card = externalId != null ? externalId : normalize(setName) + "#" + normalize(number);
        return Stream.of(provider.name(), card, language, edition.name(), variant.name(),
                        stamp == null ? "" : normalize(stamp), promo ? "promo" : "")
                .map(part -> part.replace("|", "/"))
                .collect(Collectors.joining("|"));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}

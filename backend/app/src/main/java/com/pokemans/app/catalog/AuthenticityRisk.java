package com.pokemans.app.catalog;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Categories the methodology treats as high counterfeit risk (CLAUDE.md pipeline step 1). A card in
 * one carries zero collateral value until verified. "High-value" for Japanese promos needs prices,
 * so every Japanese promo is flagged for now; conservative by default. So is a missing release date:
 * a holo or 1st Edition with no date might be vintage, and a top rarity with no date might be recent.
 */
public enum AuthenticityRisk {
    NONE,
    /** Holo or 1st Edition from the Wizards of the Coast era (sets released before 2003-07). */
    VINTAGE_HOLO,
    JAPANESE_PROMO,
    /** Top-rarity cards from sets released in the last three years. */
    RECENT_CHASE;

    public static final LocalDate END_OF_WOTC_ERA = LocalDate.of(2003, 7, 1);

    private static final Set<String> CHASE_RARITIES = Set.of(
            "secret rare", "hyper rare", "special illustration rare", "illustration rare", "ultra rare",
            "rare secret", "rare rainbow", "rare ultra", "shiny ultra rare", "full art trainer");

    static AuthenticityRisk of(CardIdentity card, LocalDate today) {
        LocalDate released = card.setReleaseDate();
        boolean holoOrFirst = card.variant() == Variant.HOLO || card.edition() == Edition.FIRST_EDITION
                || card.edition() == Edition.SHADOWLESS;
        if (released != null && released.isBefore(END_OF_WOTC_ERA) && holoOrFirst) {
            return VINTAGE_HOLO;
        }
        if ("ja".equals(card.language()) && card.promo()) {
            return JAPANESE_PROMO;
        }
        if (released == null && holoOrFirst) {
            return VINTAGE_HOLO;
        }
        if ((released == null || released.isAfter(today.minusYears(3))) && card.rarity() != null
                && CHASE_RARITIES.contains(card.rarity().toLowerCase(Locale.ROOT))) {
            return RECENT_CHASE;
        }
        return NONE;
    }
}

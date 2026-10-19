package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AuthenticityRiskTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private static CardIdentity card(String language, LocalDate released, String rarity, Edition edition, Variant variant, boolean promo) {
        return new CardIdentity(CatalogCard.Provider.MANUAL, null, language, null, "Set", null, released, "1", "Card",
                rarity, edition, variant, null, promo, null);
    }

    @Test
    void wotcEraHolosAndFirstEditionsAreVintageRisk() {
        assertThat(AuthenticityRisk.of(card("en", LocalDate.of(1999, 1, 9), "Rare", Edition.UNLIMITED, Variant.HOLO, false), TODAY))
                .isEqualTo(AuthenticityRisk.VINTAGE_HOLO);
        assertThat(AuthenticityRisk.of(card("en", LocalDate.of(2000, 6, 1), "Common", Edition.FIRST_EDITION, Variant.NORMAL, false), TODAY))
                .isEqualTo(AuthenticityRisk.VINTAGE_HOLO);
        assertThat(AuthenticityRisk.of(card("en", LocalDate.of(1999, 1, 9), "Common", Edition.UNLIMITED, Variant.NORMAL, false), TODAY))
                .isEqualTo(AuthenticityRisk.NONE);
        assertThat(AuthenticityRisk.of(card("en", LocalDate.of(2006, 5, 3), "Rare Holo", Edition.UNLIMITED, Variant.HOLO, false), TODAY))
                .isEqualTo(AuthenticityRisk.NONE);
    }

    @Test
    void japanesePromosAreRisk() {
        assertThat(AuthenticityRisk.of(card("ja", LocalDate.of(2015, 1, 1), null, Edition.UNLIMITED, Variant.HOLO, true), TODAY))
                .isEqualTo(AuthenticityRisk.JAPANESE_PROMO);
        assertThat(AuthenticityRisk.of(card("en", LocalDate.of(2015, 1, 1), null, Edition.UNLIMITED, Variant.HOLO, true), TODAY))
                .isEqualTo(AuthenticityRisk.NONE);
    }

    @Test
    void recentTopRaritiesAreChaseRisk() {
        assertThat(AuthenticityRisk.of(card("en", LocalDate.of(2025, 3, 1), "Special Illustration Rare", Edition.UNLIMITED, Variant.HOLO, false), TODAY))
                .isEqualTo(AuthenticityRisk.RECENT_CHASE);
        assertThat(AuthenticityRisk.of(card("en", LocalDate.of(2021, 3, 1), "Special Illustration Rare", Edition.UNLIMITED, Variant.HOLO, false), TODAY))
                .isEqualTo(AuthenticityRisk.NONE);
    }

    @Test
    void aMissingReleaseDateIsTreatedAsRisky() {
        // A hand-entered Base Set Charizard with no date must not slip through as NONE.
        assertThat(AuthenticityRisk.of(card("en", null, "Rare Holo", Edition.UNLIMITED, Variant.HOLO, false), TODAY))
                .isEqualTo(AuthenticityRisk.VINTAGE_HOLO);
        assertThat(AuthenticityRisk.of(card("en", null, "Common", Edition.FIRST_EDITION, Variant.NORMAL, false), TODAY))
                .isEqualTo(AuthenticityRisk.VINTAGE_HOLO);
        assertThat(AuthenticityRisk.of(card("en", null, "Special Illustration Rare", Edition.UNLIMITED, Variant.NORMAL, false), TODAY))
                .isEqualTo(AuthenticityRisk.RECENT_CHASE);
        assertThat(AuthenticityRisk.of(card("en", null, "Common", Edition.UNLIMITED, Variant.NORMAL, false), TODAY))
                .isEqualTo(AuthenticityRisk.NONE);
    }

    @Test
    void manualEntriesOfTheSameCardShareAKey() {
        CardIdentity a = new CardIdentity(CatalogCard.Provider.MANUAL, null, "en", null, " Base  Set", null, null, "4",
                "Charizard", null, Edition.UNLIMITED, Variant.HOLO, null, false, null);
        CardIdentity b = new CardIdentity(CatalogCard.Provider.MANUAL, null, "en", null, "base set", null, null, "4 ",
                "Charizard!", null, Edition.UNLIMITED, Variant.HOLO, null, false, null);
        assertThat(a.key()).isEqualTo(b.key());
    }
}

package com.pokemans.app.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CorrelationKeysTest {

    @ParameterizedTest
    @CsvSource({
            "Charizard, charizard",
            "Charizard ex, charizard",
            "Dark Charizard, charizard",
            "Charizard VMAX, charizard",
            "Team Rocket's Mewtwo ex, mewtwo",
            "Misty's Psyduck, psyduck",
            "Rayquaza δ, rayquaza",
            "Pikachu V-UNION, pikachu",
            "Gengar LV.X, gengar",
            "Mewtwo & Mew GX, mewtwo & mew",
            "Mr. Mime, mr. mime",
            "Ex, ex"})
    void characterIgnoresOwnersAndMechanics(String name, String key) {
        assertThat(CorrelationKeys.character(name)).isEqualTo(key);
    }

    @ParameterizedTest
    @CsvSource({"en, Base Set, EN:Base Set", "ja, Base Set, JA:Base Set"})
    void setsAreKeyedByLanguage(String language, String set, String key) {
        assertThat(CorrelationKeys.set(language, set)).isEqualTo(key);
    }
}

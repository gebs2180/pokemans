package com.pokemans.app.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CorrelationKeysTest {

    /** Every card of one species shares a slice, whatever its form, owner, mechanic or language (Zach, 2026-10-19). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Charizard | charizard",
            "Charizard ex | charizard",
            "Dark Charizard | charizard",
            "Charizard VMAX | charizard",
            "Mega Charizard X ex | charizard",
            "Radiant Charizard | charizard",
            "リザードン | charizard",
            "リザードンVMAX | charizard",
            "メガリザードンXex | charizard",
            "Team Rocket's Mewtwo ex | mewtwo",
            "ロケット団のミュウツー | mewtwo",
            "Mew ex | mew",
            "Lt. Surge's Pikachu | pikachu",
            "Flying Pikachu V | pikachu",
            "Alolan Vulpix | vulpix",
            "Galarian Moltres V | moltres",
            "Mr. Mime | mr. mime",
            "Farfetch'd | farfetch'd",
            "Porygon-Z | porygon-z",
            "Rayquaza δ | rayquaza",
            "Gengar LV.X | gengar"})
    void groupsBySpecies(String name, String key) {
        assertThat(CorrelationKeys.character(name, List.of())).isEqualTo(key);
    }

    /** A tag team counts toward its more popular partner; with neither ranked, the one named first. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Reshiram & Charizard GX | charizard",
            "Pikachu & Zekrom GX | pikachu",
            "Mewtwo & Mew GX | mew",
            "ピカチュウ＆ゼクロムGX | pikachu",
            "Celebi & Venusaur GX | venusaur",
            "Arceus & Dialga & Palkia GX | arceus",
            "Magikarp & Wailord GX | magikarp"})
    void tagTeamsCountTowardTheMorePopularPartner(String name, String key) {
        assertThat(CorrelationKeys.character(name, List.of())).isEqualTo(key);
    }

    @Test
    void theCatalogsPokedexNumbersWin() {
        // TCGdex gives the species even when the name is in a language we can't parse.
        assertThat(CorrelationKeys.character("Unparseable name", List.of(6))).isEqualTo("charizard");
        assertThat(CorrelationKeys.character("Reshiram & Charizard GX", List.of(643, 6))).isEqualTo("charizard");
        assertThat(CorrelationKeys.character("Anything", List.of(99999))).isEqualTo("anything");
    }

    @Test
    void aCardWithNoSpeciesIsKeyedByItsName() {
        assertThat(CorrelationKeys.character("Professor's Research", List.of())).isEqualTo("professor's research");
        assertThat(CorrelationKeys.character("Clean Card 3 ex", List.of())).isEqualTo("clean card 3");
        assertThat(CorrelationKeys.character(null, List.of())).isNull();
    }

    @ParameterizedTest
    @CsvSource({"en, Base Set, EN:Base Set", "ja, Base Set, JA:Base Set"})
    void setsAreKeyedByLanguage(String language, String set, String key) {
        assertThat(CorrelationKeys.set(language, set)).isEqualTo(key);
    }
}

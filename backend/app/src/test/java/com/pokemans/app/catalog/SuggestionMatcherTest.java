package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SuggestionMatcherTest {

    private static CatalogProvider.ProviderCard card(String id, String name, String number, int official, int year,
                                                     List<Edition> editions, List<Variant> variants) {
        return new CatalogProvider.ProviderCard(id, "en", id.split("-")[0], "Set " + id, null, LocalDate.of(year, 1, 1), number,
                name, "Rare Holo", editions, variants, List.of(), false, null, Map.of(), official);
    }

    /** Three Metagross #11s from different sets, and the 30th anniversary reprint, as TCGdex lists them. */
    private static final Map<String, CatalogProvider.ProviderCard> CARDS = Map.of(
            "30th-c-003", new CatalogProvider.ProviderCard("30th-c-003", "en", "30th-c", "30th Classic Collection", null,
                    null, "003", "Metagross", "None", List.of(Edition.UNLIMITED), List.of(Variant.NORMAL), List.of(), false,
                    null, Map.of(), 0),
            "ex5-11", card("ex5-11", "Metagross", "11", 101, 2004, List.of(Edition.UNLIMITED), List.of(Variant.HOLO)),
            "ex11-11", card("ex11-11", "Metagross δ", "11", 113, 2005, List.of(Edition.UNLIMITED), List.of(Variant.HOLO, Variant.REVERSE_HOLO)),
            "ex8-11", card("ex8-11", "Metagross", "11", 107, 2005, List.of(Edition.UNLIMITED), List.of(Variant.HOLO)));

    private final CatalogProvider provider = new CatalogProvider() {
        @Override
        public CatalogCard.Provider id() {
            return CatalogCard.Provider.TCGDEX;
        }

        @Override
        public List<Hit> search(String name, String number, String language) {
            return CARDS.values().stream()
                    .filter(c -> c.name().toLowerCase().contains(name.toLowerCase().split(" ")[0]))
                    .filter(c -> number == null || c.number().equals(TcgdexCatalogProvider.normalizeNumber(number)))
                    .map(c -> new Hit(c.externalId(), c.number(), c.name(), c.setId(), null))
                    .sorted((a, b) -> a.externalId().compareTo(b.externalId()))
                    .toList();
        }

        @Override
        public Optional<ProviderCard> card(String externalId, String language) {
            return Optional.ofNullable(CARDS.get(externalId));
        }
    };

    private final SuggestionMatcher matcher = new SuggestionMatcher(provider);

    private static CardReader.Reading reading(String name, String number, Integer total, Variant finish, Integer year) {
        return reading(name, number, total, finish, year, null);
    }

    private static CardReader.Reading reading(String name, String number, Integer total, Variant finish, Integer year, String notes) {
        return new CardReader.Reading(name, number, total, "en", false, finish, year, null, notes, "test", Map.of());
    }

    @Test
    void numberSetSizeAndNamePickOneSetConfidently() {
        List<SuggestionMatcher.Match> matches = matcher.match(reading("Metagross δ", "11", 113, Variant.REVERSE_HOLO, 2005));

        assertThat(matches.getFirst().card().externalId()).isEqualTo("ex11-11");
        assertThat(matches.getFirst().confidence()).isGreaterThan(0.9);
        assertThat(matches.getFirst().variant()).isEqualTo(Variant.REVERSE_HOLO);
        assertThat(matches.getFirst().why()).contains("number", "set size", "exact name");
        assertThat(matches).extracting(m -> m.confidence()).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void withoutTheSetSizeTiedCandidatesAreMarkedUnsure() {
        List<SuggestionMatcher.Match> matches = matcher.match(reading("Metagross", "11", null, null, null));

        assertThat(matches).hasSizeGreaterThan(1);
        assertThat(matches.getFirst().confidence()).isLessThan(0.5);
        assertThat(matches.getFirst().why()).contains("tied");
    }

    @Test
    void aMisreadNumberFallsBackToTheNameWithLowConfidence() {
        List<SuggestionMatcher.Match> matches = matcher.match(reading("Metagross δ", "71", 113, null, null));

        assertThat(matches.getFirst().card().externalId()).isEqualTo("ex11-11");
        assertThat(matches.getFirst().confidence()).isLessThan(0.6);
    }

    @Test
    void finishTheCardWasNeverPrintedInIsIgnored() {
        assertThat(matcher.match(reading("Metagross", "11", 101, Variant.REVERSE_HOLO, null)).getFirst().variant())
                .isEqualTo(Variant.HOLO);
    }

    @Test
    void printedSubtitlesAreDropped() {
        // As Claude read Zach's card: the catalog name is just "Metagross δ".
        List<SuggestionMatcher.Match> matches = matcher.match(reading("Metagross δ Delta Species", "11", 113, Variant.HOLO, 2005));

        assertThat(matches.getFirst().card().externalId()).isEqualTo("ex11-11");
        assertThat(matches.getFirst().confidence()).isGreaterThan(0.9);
        assertThat(SuggestionMatcher.nameVariants("Metagross Delta Species")).containsExactly(
                "Metagross Delta Species", "Metagross δ", "Metagross");
    }

    @Test
    void anAnniversaryStampPointsAtTheReprintCollection() {
        // Zach's card: prints 11/113 like the 2005 original, but carries the 30th anniversary stamp.
        String notes = "Pokémon 30th anniversary-style Pikachu stamp with \"3 0\" on the artwork (Celebrations-style stamp)";
        List<SuggestionMatcher.Match> matches = matcher.match(reading("Metagross δ Delta Species", "11", 113, Variant.HOLO, 2005, notes));

        assertThat(matches.getFirst().card().externalId()).isEqualTo("30th-c-003");
        assertThat(matches.getFirst().confidence()).isGreaterThan(0.85);
        assertThat(matches.getFirst().variant()).isEqualTo(Variant.HOLO); // the catalog's sparse "normal" is overridden
        assertThat(matches.get(1).card().externalId()).isEqualTo("ex11-11");
        assertThat(matches.get(1).why()).contains("stamp says reprint");
        assertThat(SuggestionMatcher.reprintSet("25th anniversary Celebrations stamp")).isEqualTo("cel25c");
        assertThat(SuggestionMatcher.reprintSet("Staff stamp")).isNull();
    }

    /** Skyridge's two Gengars as TCGdex lists them: the plain 10/144 and the holo subset H09/H32. */
    private static CatalogProvider.ProviderCard skyridge(String number, String rarity, List<Variant> variants) {
        return new CatalogProvider.ProviderCard("ecard3-" + number, "en", "ecard3", "Skyridge", null, LocalDate.of(2003, 5, 12),
                number, "Gengar", rarity, List.of(Edition.UNLIMITED), variants, List.of(), false, null, Map.of(), 144);
    }

    private static final CatalogProvider.ProviderCard PLAIN_GENGAR = skyridge("10", "Rare", List.of(Variant.NORMAL, Variant.REVERSE_HOLO));
    private static final CatalogProvider.ProviderCard HOLO_GENGAR = skyridge("H09", "Holo Rare", List.of(Variant.HOLO));

    private static CardReader.Reading gengar(String number, Variant finish, String notes, Map<String, Object> raw) {
        return new CardReader.Reading("Gengar", number, null, "en", false, finish, 2003, "Skyridge", notes, "test", raw);
    }

    /** The walkthrough's reading: no number read, "/H32" and "holo subset" only in the notes. Once a 36% tie, plain first. */
    @Test
    void anHTotalInTheNotesRanksTheHoloSubsetFirstWithHoloPicked() {
        CardReader.Reading read = gengar(null, null, "Printed /H32 at the bottom right, holo subset (probably H10)", Map.of());

        SuggestionMatcher.Match holo = SuggestionMatcher.score(read, HOLO_GENGAR, null);
        SuggestionMatcher.Match plain = SuggestionMatcher.score(read, PLAIN_GENGAR, null);

        assertThat(holo.confidence()).isGreaterThan(plain.confidence());
        assertThat(holo.variant()).isEqualTo(Variant.HOLO);
        assertThat(holo.why()).contains("subset number", "holo");
        assertThat(plain.why()).doesNotContain("subset number");
    }

    @Test
    void anHNumberOrHTotalBreaksTheTie() {
        assertThat(SuggestionMatcher.score(gengar("H09", null, null, Map.of()), HOLO_GENGAR, null).confidence())
                .isGreaterThan(SuggestionMatcher.score(gengar("H09", null, null, Map.of()), PLAIN_GENGAR, null).confidence());
        // The reader gave the total as text, so it couldn't fill setTotal; the raw answer still has it.
        CardReader.Reading textTotal = gengar(null, null, null, Map.of("input", Map.of("set_total", "H32")));
        assertThat(SuggestionMatcher.score(textTotal, HOLO_GENGAR, null).confidence())
                .isGreaterThan(SuggestionMatcher.score(textTotal, PLAIN_GENGAR, null).confidence());
    }

    @Test
    void aHoloFinishAloneBreaksTheTieAndANonHoloNoteDoesNot() {
        CardReader.Reading holo = gengar(null, Variant.HOLO, null, Map.of());
        assertThat(SuggestionMatcher.score(holo, HOLO_GENGAR, null).confidence())
                .isGreaterThan(SuggestionMatcher.score(holo, PLAIN_GENGAR, null).confidence());

        CardReader.Reading nonHolo = gengar(null, null, "non-holo, light whitening", Map.of());
        assertThat(SuggestionMatcher.saysHolo(nonHolo)).isFalse();
        assertThat(SuggestionMatcher.saysHolo(gengar(null, null, "reverse holo", Map.of()))).isFalse();
        assertThat(SuggestionMatcher.score(nonHolo, PLAIN_GENGAR, null).variant()).isEqualTo(Variant.NORMAL);
    }

    @Test
    void numberPrefixes() {
        assertThat(SuggestionMatcher.numberPrefix("H09")).isEqualTo("H");
        assertThat(SuggestionMatcher.numberPrefix("h9/h32")).isEqualTo("H");
        assertThat(SuggestionMatcher.numberPrefix("10")).isNull();
        assertThat(SuggestionMatcher.numberPrefix(null)).isNull();
    }

    @Test
    void noNameNoSuggestions() {
        assertThat(matcher.match(reading(null, "11", 113, null, null))).isEmpty();
    }
}

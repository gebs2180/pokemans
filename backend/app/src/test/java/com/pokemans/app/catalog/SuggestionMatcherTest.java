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

    /** Three Metagross #11s from different sets, as TCGdex lists them. */
    private static final Map<String, CatalogProvider.ProviderCard> CARDS = Map.of(
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
        return new CardReader.Reading(name, number, total, "en", false, finish, year, null, null, "test", Map.of());
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
    void noNameNoSuggestions() {
        assertThat(matcher.match(reading(null, "11", 113, null, null))).isEmpty();
    }
}

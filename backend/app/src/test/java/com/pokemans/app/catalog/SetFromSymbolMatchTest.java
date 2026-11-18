package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The first real miss: a Skyridge holo Gengar whose set was recognised from its symbol, with no number. */
class SetFromSymbolMatchTest {

    private static CatalogProvider.ProviderCard gengar(String id, String number, String setName, int total, int year) {
        return new CatalogProvider.ProviderCard(id, "en", id.split("-")[0], setName, null, LocalDate.of(year, 1, 1), number,
                "Gengar", "Rare Holo", List.of(Edition.UNLIMITED), List.of(Variant.HOLO), List.of(), false, null, Map.of(), total);
    }

    /** Twelve more Gengars than the matcher fetches, newest first like TCGdex, plus the Skyridge one last. */
    private static final Map<String, CatalogProvider.ProviderCard> CARDS = new java.util.LinkedHashMap<>();

    static {
        for (int i = 0; i < 12; i++) {
            CARDS.put("sv" + i + "-" + i, gengar("sv" + i + "-" + i, String.valueOf(i), "Modern Set " + i, 100 + i, 2024));
        }
        CARDS.put("ecard3-H09", gengar("ecard3-H09", "H09", "Skyridge", 144, 2003));
    }

    /** Like TCGdex: first word is the query, the rest filter on the card's or set's name. */
    private final CatalogProvider provider = new CatalogProvider() {
        @Override
        public CatalogCard.Provider id() {
            return CatalogCard.Provider.TCGDEX;
        }

        @Override
        public List<Hit> search(String name, String number, String language) {
            String[] words = name.toLowerCase(Locale.ROOT).split("\\s+");
            List<Hit> hits = new ArrayList<>();
            for (ProviderCard c : CARDS.values()) {
                String haystack = (c.name() + " " + c.setName()).toLowerCase(Locale.ROOT);
                boolean ok = c.name().toLowerCase(Locale.ROOT).contains(words[0]);
                for (int i = 1; i < words.length; i++) {
                    ok &= haystack.contains(words[i]);
                }
                if (ok && (number == null || c.number().equalsIgnoreCase(TcgdexCatalogProvider.normalizeNumber(number)))) {
                    hits.add(new Hit(c.externalId(), c.number(), c.name(), c.setId(), null, c.setName(), c.setOfficialCount()));
                }
            }
            return hits;
        }

        @Override
        public Optional<ProviderCard> card(String externalId, String language) {
            return Optional.ofNullable(CARDS.get(externalId));
        }
    };

    private final SuggestionMatcher matcher = new SuggestionMatcher(provider);

    private static CardReader.Reading reading(String setName, Integer setTotal) {
        return new CardReader.Reading("Gengar", null, setTotal, "en", false, Variant.HOLO, 2003, setName,
                "E-Card era layout with the Skyridge set logo (mountain symbol) near the bottom right.", "claude-sonnet-5-5",
                Map.of());
    }

    @Test
    void theGengarReadingSuggestsSkyridgeFirst() {
        List<SuggestionMatcher.Match> matches = matcher.match(reading("Skyridge", null));

        assertThat(matches).isNotEmpty();
        assertThat(matches.getFirst().card().externalId()).isEqualTo("ecard3-H09");
        assertThat(matches.getFirst().confidence()).isGreaterThanOrEqualTo(0.2);
        assertThat(matches.getFirst().why()).contains("set name", "exact name", "year");
        assertThat(matches).allMatch(m -> m.card().setName().equals("Skyridge"));
    }

    @Test
    void aWrongSetNameFindsNothingConfident() {
        // The set exists but holds no Gengar: fall back to name only, where every Gengar ties and drops under the cut-off.
        assertThat(matcher.match(reading("Aquapolis", null))).isEmpty();
    }

    @Test
    void withoutASetNameTheTieStillSuppressesSuggestions() {
        assertThat(matcher.match(reading(null, null))).isEmpty();
    }

    @Test
    void theSetTotalSortsTheRightSetIntoTheFetchedCandidates() {
        // No set name, but the set size: Skyridge's 144 is moved ahead of the twelve modern Gengars.
        List<SuggestionMatcher.Match> matches = matcher.match(reading(null, 144));

        assertThat(matches).isNotEmpty();
        assertThat(matches.getFirst().card().setName()).isEqualTo("Skyridge");
        assertThat(matches.getFirst().why()).contains("set size");
    }
}

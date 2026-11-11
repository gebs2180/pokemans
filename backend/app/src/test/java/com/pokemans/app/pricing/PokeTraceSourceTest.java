package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.catalog.Edition;
import com.pokemans.app.catalog.Variant;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The live source against a fake PokeTrace, and the mapping checks. */
class PokeTraceSourceTest {

    private static final UUID CARD = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private static Catalog.CardSummary card(String number, String set, Edition edition, Variant variant, String language, String stamp) {
        return new Catalog.CardSummary(CARD, null, language, set, LocalDate.of(1999, 1, 9), number, "Charizard", edition, variant, stamp,
                false);
    }

    private static Map<String, Object> detail(String number, String set, String variant) {
        return Map.of("id", "pt-1", "cardNumber", number, "variant", variant, "set", Map.of("slug", "x", "name", set));
    }

    /** Records which tiers and periods were asked for. */
    private static final class FakeApi implements PokeTraceApi {
        final List<String> asked = new ArrayList<>();

        @Override
        public List<Map<String, Object>> search(String text) {
            return List.of();
        }

        @Override
        public Map<String, Object> card(String id) {
            return Map.of("id", id, "currency", "USD",
                    "gradedOptions", List.of("PSA_9", "PSA_10", "CGC_10", "BGS_9_5"),
                    "conditionOptions", List.of("NEAR_MINT", "DAMAGED"));
        }

        @Override
        public List<Map<String, Object>> history(String id, String tier, String period) {
            asked.add(tier + "@" + period);
            return List.of(
                    Map.of("date", TODAY.toString(), "source", "ebay", "saleCount", 2, "avg", 999),
                    Map.of("date", TODAY.minusDays(1).toString(), "source", "ebay", "saleCount", 3, "avg", 500),
                    Map.of("date", TODAY.minusDays(2).toString(), "source", "ebay", "saleCount", 0, "avg", 450),
                    Map.of("date", TODAY.minusDays(400).toString(), "source", "ebay", "saleCount", 1, "avg", 300));
        }
    }

    private static PokeTraceSource source(FakeApi api, Optional<String> mapped, boolean stored) {
        PokeTraceMappings mappings = new PokeTraceMappings(null, null) {
            @Override
            Optional<String> poketraceId(UUID catalogCardId) {
                return mapped;
            }
        };
        SoldCompRepository comps = mock(SoldCompRepository.class);
        when(comps.existsBySourceAndExternalSaleIdStartingWith(eq(PokeTraceSource.NAME), anyString())).thenReturn(stored);
        PricingProperties properties = new PricingProperties("USD", 3, 12, null, null,
                new PricingProperties.PokeTrace(true, "k", URI.create("http://localhost"), Duration.ofSeconds(1), "1y", "30d", 2));
        return new PokeTraceSource(api, mappings, comps, properties);
    }

    private static final Catalog.CardSummary BASE_CHARIZARD = card("4", "Base Set", Edition.UNLIMITED, Variant.HOLO, "en", null);

    @Test
    void anUnmappedCardAsksNothing() {
        FakeApi api = new FakeApi();
        assertThat(source(api, Optional.empty(), false).soldSales(BASE_CHARIZARD, TODAY.minusDays(90), TODAY)).isEmpty();
        assertThat(api.asked).isEmpty();
    }

    @Test
    void psaAndRawTiersOnlyOneCompPerSaleDayAndNotToday() {
        FakeApi api = new FakeApi();
        List<PriceSource.Sale> sales = source(api, Optional.of("pt-1"), false).soldSales(BASE_CHARIZARD, TODAY.minusDays(90), TODAY);

        assertThat(api.asked).containsExactly("PSA_9@1y", "PSA_10@1y", "NEAR_MINT@1y", "DAMAGED@1y");
        // Per tier: today's partial day, the day with no sale and the day outside the window are left out.
        assertThat(sales).hasSize(4).allSatisfy(s -> {
            assertThat(s.soldDate()).isEqualTo(TODAY.minusDays(1));
            assertThat(s.price()).isEqualByComparingTo("500");
            assertThat(s.source()).isEqualTo(PokeTraceSource.NAME);
            assertThat(s.externalSaleId()).startsWith("pt-1|");
        });
    }

    @Test
    void laterPullsAskForTheLastThirtyDays() {
        FakeApi api = new FakeApi();
        source(api, Optional.of("pt-1"), true).soldSales(BASE_CHARIZARD, TODAY.minusDays(90), TODAY);
        assertThat(api.asked).allMatch(a -> a.endsWith("@30d"));
    }

    @Test
    void numbersNormalise() {
        assertThat(PokeTraceMappings.number("004/102")).isEqualTo("4");
        assertThat(PokeTraceMappings.number("04")).isEqualTo("4");
        assertThat(PokeTraceMappings.number("0")).isEqualTo("0");
        assertThat(PokeTraceMappings.number("SV49")).isEqualTo("sv49");
        assertThat(PokeTraceMappings.number("TG05/TG30")).isEqualTo("tg05");
        assertThat(PokeTraceMappings.number(null)).isEmpty();
    }

    @Test
    void aMatchingCardHasNoDisagreements() {
        assertThat(PokeTraceMappings.disagreements(BASE_CHARIZARD, detail("004/102", "Base Set", "Unlimited_Holofoil"))).isEmpty();
        assertThat(PokeTraceMappings.disagreements(BASE_CHARIZARD, detail("4/102", "Base", "Holofoil"))).isEmpty();
        assertThat(PokeTraceMappings.disagreements(card("4", "Base Set", Edition.FIRST_EDITION, Variant.HOLO, "en", null),
                detail("4/102", "Base Set", "1st_Edition_Holofoil"))).isEmpty();
        assertThat(PokeTraceMappings.disagreements(card("25", "Jungle", null, Variant.NORMAL, "en", null),
                detail("25/64", "Jungle", "Unlimited"))).isEmpty();
    }

    @Test
    void identityDisagreementsAreListed() {
        // PokeTrace's one "Holofoil" Base Set Charizard can't be our 1st Edition.
        assertThat(PokeTraceMappings.disagreements(card("4", "Base Set", Edition.FIRST_EDITION, Variant.HOLO, "en", null),
                detail("4/102", "Base Set", "Holofoil"))).singleElement().asString().startsWith("edition");
        assertThat(PokeTraceMappings.disagreements(BASE_CHARIZARD, detail("4/102", "Base Set", "1st_Edition_Holofoil")))
                .singleElement().asString().startsWith("edition");
        assertThat(PokeTraceMappings.disagreements(card("4", "Base Set", Edition.SHADOWLESS, Variant.HOLO, "en", null),
                detail("4/102", "Base Set", "Holofoil"))).singleElement().asString().startsWith("edition");
        assertThat(PokeTraceMappings.disagreements(BASE_CHARIZARD, detail("5/102", "Base Set", "Holofoil")))
                .singleElement().asString().startsWith("number");
        assertThat(PokeTraceMappings.disagreements(BASE_CHARIZARD, detail("4/102", "Jungle", "Holofoil")))
                .singleElement().asString().startsWith("set");
        assertThat(PokeTraceMappings.disagreements(BASE_CHARIZARD, detail("4/102", "Base Set", "Reverse_Holofoil")))
                .singleElement().asString().startsWith("variant");
        assertThat(PokeTraceMappings.disagreements(card("4", "Base Set", null, Variant.NORMAL, "en", null),
                detail("4/102", "Base Set", "Holofoil"))).singleElement().asString().startsWith("variant");
        assertThat(PokeTraceMappings.disagreements(card("4", "Base Set", null, Variant.HOLO, "ja", null),
                detail("4/102", "Base Set", "Holofoil"))).singleElement().asString().startsWith("language");
        assertThat(PokeTraceMappings.disagreements(card("4", "Base Set", null, Variant.HOLO, "en", "Prerelease"),
                detail("4/102", "Base Set", "Holofoil"))).singleElement().asString().startsWith("stamp");
    }
}

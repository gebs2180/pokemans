package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.app.catalog.Catalog;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The PokeTrace pilot source against the hand-made sample folder (analysis/poketrace/make_sample.py). */
class PokeTraceDailySourceTest {

    private static final Path SAMPLE = Path.of("src", "test", "resources", "poketrace-sample");
    private static final String DEEP = "5a000000-0000-4000-8000-00000000000a";
    private static final UUID CATALOG = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    private static Catalog.CardSummary card(UUID id) {
        return new Catalog.CardSummary(id, null, "en", "Sample Skies", LocalDate.of(2021, 8, 27), "1", "Sample Deep Umbreon", null, null,
                null, false);
    }

    private static Map<String, Object> row(int n, double avg, double low, double high) {
        return Map.of("date", "2026-10-20", "source", "ebay", "avg", avg, "low", low, "high", high, "saleCount", n);
    }

    private static List<BigDecimal> prices(List<PriceSource.Sale> sales) {
        return sales.stream().map(PriceSource.Sale::price).toList();
    }

    @Test
    void eachDayBecomesItsSaleCountOfSales() {
        PokeTraceDailySource.Key psa9 = new PokeTraceDailySource.Key("PSA_9", "ebay", "2026-10-20");
        LocalDate day = LocalDate.of(2026, 10, 20);
        assertThat(prices(PokeTraceDailySource.expand(CATALOG, DEEP, psa9, row(1, 500, 500, 500), day, "USD")))
                .containsExactly(new BigDecimal("500.00"));
        assertThat(prices(PokeTraceDailySource.expand(CATALOG, DEEP, psa9, row(2, 500, 450, 550), day, "USD")))
                .containsExactly(new BigDecimal("450.00"), new BigDecimal("550.00"));
        List<PriceSource.Sale> four = PokeTraceDailySource.expand(CATALOG, DEEP, psa9, row(4, 500, 400, 640), day, "USD");
        assertThat(prices(four)).containsExactly(new BigDecimal("400.00"), new BigDecimal("640.00"), new BigDecimal("500.00"),
                new BigDecimal("500.00"));
        assertThat(four).allSatisfy(s -> {
            assertThat(s.gradingCompany()).isEqualTo(PriceSource.GradingCompany.PSA);
            assertThat(s.grade()).isEqualByComparingTo("9");
            assertThat(s.soldDate()).isEqualTo(day);
            assertThat(s.raw()).containsEntry("expansion", "low, high and 2 at the average");
        });
        assertThat(four.stream().map(PriceSource.Sale::externalSaleId).distinct()).hasSize(4);

        // Half grades, raw conditions, and what is never a sale: no sales, EUR listings, TCGplayer on a graded tier.
        assertThat(PokeTraceDailySource.expand(CATALOG, DEEP, new PokeTraceDailySource.Key("BGS_9_5", "ebay", "2026-10-20"),
                row(1, 900, 900, 900), day, "USD").getFirst().grade()).isEqualByComparingTo("9.5");
        assertThat(PokeTraceDailySource.expand(CATALOG, DEEP, new PokeTraceDailySource.Key("DAMAGED", "tcgplayer", "2026-10-20"),
                row(1, 20, 20, 20), day, "USD").getFirst().rawCondition()).isEqualTo(PriceSource.RawCondition.DAMAGED);
        assertThat(PokeTraceDailySource.expand(CATALOG, DEEP, psa9, row(0, 500, 500, 500), day, "USD")).isEmpty();
        assertThat(PokeTraceDailySource.expand(CATALOG, DEEP, new PokeTraceDailySource.Key("PSA_9", "cardmarket_unsold", "2026-10-20"),
                row(3, 500, 400, 600), day, "USD")).isEmpty();
        assertThat(PokeTraceDailySource.expand(CATALOG, DEEP, new PokeTraceDailySource.Key("PSA_9", "tcgplayer", "2026-10-20"),
                row(3, 500, 400, 600), day, "USD")).isEmpty();
        assertThat(PokeTraceDailySource.expand(CATALOG, DEEP, new PokeTraceDailySource.Key("SEALED", "ebay", "2026-10-20"),
                row(3, 500, 400, 600), day, "USD")).isEmpty();
    }

    @Test
    void readsEveryPageOfTheSampleAndOnlyTheSalesWePriceFrom() {
        PokeTraceDailySource source = new PokeTraceDailySource(SAMPLE, Map.of(CATALOG, DEEP));
        List<PriceSource.Sale> sales = source.soldSales(card(CATALOG), FROM, TO);

        // 14 sales per tier in the sample: PSA 5-10, three raw conditions; CGC 10 has 3.
        Map<String, Long> byTier = sales.stream().collect(Collectors.groupingBy(
                s -> s.gradingCompany() == null ? s.rawCondition().name() : s.gradingCompany() + "_" + s.grade().stripTrailingZeros().toPlainString(),
                Collectors.counting()));
        assertThat(byTier).containsOnlyKeys("PSA_5", "PSA_6", "PSA_7", "PSA_8", "PSA_9", "PSA_10", "NEAR_MINT", "LIGHTLY_PLAYED",
                "DAMAGED", "CGC_10");
        assertThat(byTier.get("PSA_9")).isEqualTo(14); // the cardmarket_unsold and TCGplayer rows are left out
        assertThat(byTier.get("NEAR_MINT")).isEqualTo(14);
        assertThat(byTier.get("CGC_10")).isEqualTo(3);
        assertThat(sales).allSatisfy(s -> {
            assertThat(s.source()).isEqualTo(PokeTraceDailySource.NAME);
            assertThat(s.currency()).isEqualTo("USD");
            assertThat(s.catalogCardId()).isEqualTo(CATALOG);
        });
        assertThat(sales.stream().map(PriceSource.Sale::externalSaleId).distinct()).hasSize(sales.size());

        // The window is honoured, and a card the source doesn't know has no sales.
        assertThat(source.soldSales(card(CATALOG), LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31))).isEmpty();
        assertThat(source.soldSales(card(UUID.randomUUID()), FROM, TO)).isEmpty();
    }

    @Test
    void aLaterPullReplacesTheSameDay(@TempDir Path dir) throws IOException {
        copy(SAMPLE, dir);
        Path page = dir.resolve("pulls/2026-10-24/cards/" + DEEP + "/history/PSA_10/all-p001.json");
        String firstDay = Files.readString(page).replaceAll("(?s).*?\"date\": \"([0-9-]+)\".*", "$1");
        Path later = dir.resolve("pulls/2026-10-25/cards/" + DEEP + "/history/PSA_10/all-p001.json");
        Files.createDirectories(later.getParent());
        Files.writeString(later, "{\"response\": {\"data\": [{\"date\": \"" + firstDay
                + "\", \"source\": \"ebay\", \"avg\": 999, \"low\": 999, \"high\": 999, \"saleCount\": 1}]}}");

        List<PriceSource.Sale> day = new PokeTraceDailySource(dir, Map.of(CATALOG, DEEP)).soldSales(card(CATALOG), FROM, TO).stream()
                .filter(s -> s.grade() != null && s.grade().intValue() == 10 && s.gradingCompany() == PriceSource.GradingCompany.PSA
                        && s.soldDate().toString().equals(firstDay))
                .toList();
        assertThat(prices(day)).containsExactly(new BigDecimal("999.00"));
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : files.toList()) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(f, target);
                }
            }
        }
    }
}

package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One comp per sale day at the day's average; PokeTrace's noisy count is never a count. */
class PokeTraceDaysTest {

    private static final UUID CARD = UUID.randomUUID();
    private static final String PT = "pt-1";

    private static Map<String, Object> row(String source, Object saleCount, Object avg) {
        Map<String, Object> row = new HashMap<>();
        row.put("date", "2026-10-20T00:00:00Z");
        row.put("source", source);
        row.put("saleCount", saleCount);
        row.put("avg", avg);
        row.put("low", 400);
        row.put("high", 640);
        return row;
    }

    private static Optional<PriceSource.Sale> sale(String tier, Map<String, Object> row) {
        return PokeTraceDays.sale("POKETRACE", CARD, PT, tier, row, "USD");
    }

    @Test
    void aDayWithSalesIsOneCompAtTheAverageWhateverItsCount() {
        for (int count : new int[] {1, 2, 4, 9}) {
            PriceSource.Sale s = sale("PSA_9", row("ebay", count, 500)).orElseThrow();
            assertThat(s.price()).isEqualTo(new BigDecimal("500.00"));
            assertThat(s.gradingCompany()).isEqualTo(PriceSource.GradingCompany.PSA);
            assertThat(s.grade()).isEqualByComparingTo("9");
            assertThat(s.soldDate()).isEqualTo(LocalDate.of(2026, 10, 20));
            assertThat(s.externalSaleId()).isEqualTo("pt-1|PSA_9|ebay|2026-10-20");
            assertThat(s.listingType()).isEqualTo(PriceSource.ListingType.UNKNOWN);
            assertThat(s.raw()).containsEntry("rule", PokeTraceDays.RULE).containsEntry("tier", "PSA_9")
                    .containsEntry("saleCount", count);
        }
    }

    @Test
    void halfGradesAndRawConditions() {
        assertThat(sale("BGS_9_5", row("ebay", 1, 900)).orElseThrow().grade()).isEqualByComparingTo("9.5");
        assertThat(sale("PSA_8.5", row("ebay", 1, 900)).orElseThrow().grade()).isEqualByComparingTo("8.5");
        assertThat(sale("DAMAGED", row("tcgplayer", 1, 20)).orElseThrow().rawCondition()).isEqualTo(PriceSource.RawCondition.DAMAGED);
        assertThat(sale("NEAR_MINT", row("ebay", 1, 120)).orElseThrow().rawCondition()).isEqualTo(PriceSource.RawCondition.NEAR_MINT);
    }

    @Test
    void whatIsNeverAComp() {
        assertThat(sale("PSA_9", row("ebay", 0, 500))).as("no sale that day").isEmpty();
        assertThat(sale("PSA_9", row("ebay", null, 500))).as("no count").isEmpty();
        assertThat(sale("PSA_9", row("ebay", 3, null))).as("no average").isEmpty();
        assertThat(sale("PSA_9", row("cardmarket_unsold", 3, 500))).as("listings").isEmpty();
        assertThat(sale("PSA_9", row("cardmarket", 3, 500))).as("EUR").isEmpty();
        assertThat(sale("PSA_9", row("tcgplayer", 3, 500))).as("TCGplayer on a graded tier").isEmpty();
        assertThat(sale("SEALED", row("ebay", 3, 500))).isEmpty();
        assertThat(sale("ALTERED", row("ebay", 3, 500))).as("ours, not PokeTrace's").isEmpty();
        assertThat(sale("XYZ_9", row("ebay", 3, 500))).as("unknown grader").isEmpty();
        assertThat(sale("PSA_11", row("ebay", 3, 500))).isEmpty();
    }

    @Test
    void blankFieldsAreLeftOutOfTheRawRow() {
        Map<String, Object> row = row("ebay", 1, 500);
        row.put("median30d", null);
        assertThat(sale("PSA_9", row).orElseThrow().raw()).doesNotContainKey("median30d");
    }
}

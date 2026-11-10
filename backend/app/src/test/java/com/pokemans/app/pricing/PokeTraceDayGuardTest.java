package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.valuation.ParameterSet;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Review B4: which PokeTrace sale days parameter set 1.5.0 leaves out of pricing, and that none is ever shifted. */
class PokeTraceDayGuardTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);
    private static final UUID CARD = UUID.randomUUID();
    private static final ParameterSet V150 = ParameterSet.load("1.5.0");

    private static SoldComp day(String source, String grade, String avg, String low, String high, int daysAgo) {
        Map<String, Object> raw = new HashMap<>();
        if (low != null) {
            raw.put("low", new BigDecimal(low));
        }
        if (high != null) {
            raw.put("high", new BigDecimal(high));
        }
        LocalDate soldOn = DAY.minusDays(daysAgo);
        return new SoldComp(new PriceSource.Sale(source, "pt-x|PSA_" + grade + "|ebay|" + soldOn + "|" + UUID.randomUUID(), CARD,
                PriceSource.GradingCompany.PSA, new BigDecimal(grade), null, new BigDecimal(avg), "USD", soldOn, null,
                PriceSource.ListingType.UNKNOWN, raw), "test", Instant.now(), null);
    }

    private static SoldComp pt(String avg, String low, String high, int daysAgo) {
        return day(PokeTraceSource.NAME, "10", avg, low, high, daysAgo);
    }

    @Test
    void aDayWhoseAverageIsOutsideItsRangeIsLeftOut() {
        SoldComp bad = pt("500", "100", "200", 1);
        SoldComp zeroLow = pt("100", "0", "150", 2);
        SoldComp ok = pt("150", "100", "200", 3);

        PokeTraceDayGuard.Result r = PokeTraceDayGuard.apply(List.of(bad, zeroLow, ok), V150);

        assertThat(r.kept()).containsExactly(ok);
        assertThat(r.excluded()).extracting(PokeTraceDayGuard.Excluded::rule)
                .containsExactly(PokeTraceDayGuard.INCONSISTENT, PokeTraceDayGuard.INCONSISTENT);
        assertThat(r.excluded().get(0).reason()).contains("500").contains("100.00 to 200.00");
    }

    @Test
    void aDayTooWideToTellOneSaleFromTheRestIsLeftOut() {
        SoldComp wide = pt("300", "100", "301", 1);
        SoldComp atLimit = pt("200", "100", "300", 2);

        PokeTraceDayGuard.Result r = PokeTraceDayGuard.apply(List.of(wide, atLimit), V150);

        assertThat(r.kept()).containsExactly(atLimit);
        assertThat(r.excluded()).singleElement().satisfies(e -> {
            assertThat(e.rule()).isEqualTo(PokeTraceDayGuard.WIDE_DAY);
            assertThat(e.reason()).contains("3.0x its low 100.00");
        });
    }

    @Test
    void aDayFarAboveItsTiersOtherDaysIsLeftOutButALowDayNeverIs() {
        SoldComp a = pt("100", null, null, 1);
        SoldComp b = pt("110", null, null, 5);
        SoldComp c = pt("90", null, null, 9);
        SoldComp high = pt("400", null, null, 3);
        SoldComp low = pt("10", null, null, 4);
        // Another grade is another tier: its prices don't count as neighbours.
        SoldComp nine = day(PokeTraceSource.NAME, "9", "40", null, null, 2);

        PokeTraceDayGuard.Result r = PokeTraceDayGuard.apply(List.of(a, b, c, high, low, nine), V150);

        assertThat(r.kept()).containsExactly(a, b, c, low, nine);
        assertThat(r.excluded()).singleElement().satisfies(e -> {
            assertThat(e.comp()).isSameAs(high);
            assertThat(e.rule()).isEqualTo(PokeTraceDayGuard.HIGH_DAY);
            assertThat(e.reason()).contains("over 3x the median 95.00 of 4 other sale days");
        });
    }

    @Test
    void theTierRuleNeedsEnoughNeighboursCloseEnough() {
        SoldComp high = pt("400", null, null, 0);
        SoldComp near = pt("100", null, null, 10);
        SoldComp far = pt("100", null, null, 120);

        assertThat(PokeTraceDayGuard.apply(List.of(high, near, far), V150).excluded()).isEmpty();
        // Another source's sale counts as a neighbour, though it is never left out itself.
        SoldComp manual = day("MANUAL", "10", "100", null, null, 20);
        SoldComp manualHigh = day("MANUAL", "10", "5000", "1", "9000", 30);
        PokeTraceDayGuard.Result r = PokeTraceDayGuard.apply(List.of(high, near, manual, manualHigh), V150);
        assertThat(r.excluded()).extracting(PokeTraceDayGuard.Excluded::comp).containsExactly(high);
        assertThat(r.kept()).contains(manualHigh);
    }

    @Test
    void setsBefore150KeepEveryDay() {
        List<SoldComp> days = List.of(pt("500", "100", "200", 1), pt("300", "100", "900", 2), pt("100", null, null, 3),
                pt("100", null, null, 4), pt("1000", null, null, 5));

        PokeTraceDayGuard.Result r = PokeTraceDayGuard.apply(days, ParameterSet.load("1.4.0"));

        assertThat(r.kept()).isEqualTo(days);
        assertThat(r.excluded()).isEmpty();
    }
}

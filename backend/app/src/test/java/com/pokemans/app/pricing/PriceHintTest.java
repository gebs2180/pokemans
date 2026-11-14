package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradePrice;
import com.pokemans.valuation.RawFloors;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The early $25 hint says "under" only when no condition could lift the FMV to the minimum. */
class PriceHintTest {

    private static final BigDecimal MIN = new BigDecimal("25");

    @Test
    void underOnlyWhenEveryGradeIsUnder() {
        assertThat(PriceHintController.clearlyUnder(table(grades(new BigDecimal("24.99"), null)), MIN)).isTrue();
        Map<Grade, Prices.GradeSnapshot> tenAbove = grades(new BigDecimal("10"), null);
        tenAbove.put(Grade.G10, snapshot(Grade.G10, new BigDecimal("40"), null));
        assertThat(PriceHintController.clearlyUnder(table(tenAbove), MIN)).isFalse();
    }

    @Test
    void aRawFloorAboveTheMinimumMeansNoHint() {
        RawFloors.Floor floor = new RawFloors.Floor(null, new BigDecimal("30"), List.of(), 90, 3);
        assertThat(PriceHintController.clearlyUnder(table(grades(new BigDecimal("10"), floor)), MIN)).isFalse();
    }

    @Test
    void noTableOrAnUnpricedGradeMeansNoHint() {
        assertThat(PriceHintController.clearlyUnder(Optional.empty(), MIN)).isFalse();
        Map<Grade, Prices.GradeSnapshot> missing = grades(new BigDecimal("5"), null);
        missing.remove(Grade.G7);
        assertThat(PriceHintController.clearlyUnder(table(missing), MIN)).isFalse();
        // U without its own sales is $0 and doesn't block the hint.
        Map<Grade, Prices.GradeSnapshot> noU = grades(new BigDecimal("5"), null);
        noU.remove(Grade.U);
        assertThat(PriceHintController.clearlyUnder(table(noU), MIN)).isTrue();
    }

    private static Map<Grade, Prices.GradeSnapshot> grades(BigDecimal price, RawFloors.Floor floor) {
        Map<Grade, Prices.GradeSnapshot> grades = new EnumMap<>(Grade.class);
        for (Grade g : Grade.values()) {
            grades.put(g, snapshot(g, price, floor));
        }
        return grades;
    }

    private static Prices.GradeSnapshot snapshot(Grade g, BigDecimal price, RawFloors.Floor floor) {
        return new Prices.GradeSnapshot(g, price, "USD", GradePrice.Method.OBSERVED, 3, List.of(), 90, List.of(), null,
                Confidence.B, Confidence.B, floor);
    }

    private static Optional<Prices.CardPrices> table(Map<Grade, Prices.GradeSnapshot> grades) {
        return Optional.of(new Prices.CardPrices(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), "1.8.0", "PSA", grades,
                null, 0, BigDecimal.ONE, 0, 0));
    }
}

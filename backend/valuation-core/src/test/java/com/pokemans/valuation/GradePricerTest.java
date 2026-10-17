package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GradePricerTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);
    private static final GradingCost NO_COST = new GradingCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    private static final BigDecimal FLOOR = new BigDecimal("2");

    private final GradePricer pricer = new GradePricer(ParameterSet.defaults());
    private int nextId;

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private Comp comp(int daysAgo, String price) {
        return new Comp("c" + nextId++, AS_OF.minusDays(daysAgo), d(price));
    }

    private static Map<Grade, BigDecimal> ratios(String ratio) {
        Map<Grade, BigDecimal> ratios = new EnumMap<>(Grade.class);
        for (int g = 1; g <= 9; g++) {
            ratios.put(Grade.of(g), d(ratio));
        }
        return ratios;
    }

    private GradePricer.Input input(Map<Grade, List<Comp>> comps) {
        return new GradePricer.Input(AS_OF, comps, List.of(), FLOOR, ratios("0.78"), NO_COST, Map.of());
    }

    /** The worked example's observed prices, two recent sales each. */
    private Map<Grade, List<Comp>> exampleComps(boolean withGradeTwo) {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        String[][] observed = {{"10", "3000"}, {"9", "900"}, {"8", "450"}, {"7", "300"}, {"6", "220"}, {"5", "170"}};
        for (String[] row : observed) {
            comps.put(Grade.of(Integer.parseInt(row[0])), List.of(comp(5, row[1]), comp(10, row[1])));
        }
        if (withGradeTwo) {
            comps.put(Grade.G2, List.of(comp(5, "85"), comp(10, "85")));
        }
        return comps;
    }

    @Test
    void stepsDownFromTheNearestGradeAboveWhenNothingBelow() {
        GradePriceTable table = pricer.price(input(exampleComps(false)));

        assertThat(table.get(Grade.G5).method()).isEqualTo(GradePrice.Method.OBSERVED);
        assertThat(table.gross(Grade.G4)).isEqualByComparingTo("132.60");
        assertThat(table.get(Grade.G4).method()).isEqualTo(GradePrice.Method.STEP_DOWN);
        assertThat(table.gross(Grade.G3)).isEqualByComparingTo("103.43");
        assertThat(table.get(Grade.G3).derived()).isTrue();
        assertThat(table.trace().stepsFor("price[4]").getFirst().rule()).isEqualTo("era step-down");
    }

    @Test
    void interpolatesOnLogPriceWhenSalesOnBothSides() {
        GradePriceTable table = pricer.price(input(exampleComps(true)));

        assertThat(table.get(Grade.G4).method()).isEqualTo(GradePrice.Method.INTERPOLATED);
        assertThat(table.gross(Grade.G4)).isEqualByComparingTo("134.93");
        assertThat(table.gross(Grade.G3)).isEqualByComparingTo("107.09");
        // Below the lowest observed grade, step-down applies.
        assertThat(table.gross(Grade.G1)).isEqualByComparingTo("66.30");
    }

    @Test
    void neverStepsUpFromLowerGrades() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G4, List.of(comp(5, "40"), comp(6, "40")));
        comps.put(Grade.G5, List.of(comp(5, "60"), comp(6, "60")));

        GradePriceTable table = pricer.price(input(comps));

        for (int g = 6; g <= 10; g++) {
            assertThat(table.gross(Grade.of(g))).isEqualByComparingTo("60");
            assertThat(table.get(Grade.of(g)).method()).isEqualTo(GradePrice.Method.CARRIED_DOWN);
        }
    }

    @Test
    void recencyWeightingFavoursRecentSales() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G8, List.of(comp(0, "100"), comp(60, "200"), comp(60, "210")));

        GradePriceTable table = pricer.price(input(comps));

        // Weights 1, 0.25, 0.25: the recent sale alone carries more than half.
        assertThat(table.gross(Grade.G8)).isEqualByComparingTo("100");
        assertThat(table.get(Grade.G8).salesUsed()).isEqualTo(3);
    }

    @Test
    void extendsTheWindowWhenFewerThanTwoSales() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G8, List.of(comp(10, "100"), comp(120, "90"), comp(200, "10")));

        GradePriceTable table = pricer.price(input(comps));

        assertThat(table.get(Grade.G8).salesUsed()).isEqualTo(2);
        assertThat(table.trace().stepsFor("price[8]").getFirst().detail()).contains("180-day window");
    }

    @Test
    void ignoresSalesAfterTheValuationDate() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G8, List.of(comp(5, "100"), comp(6, "100"), comp(-3, "900")));

        GradePriceTable table = pricer.price(input(comps));

        assertThat(table.get(Grade.G8).salesUsed()).isEqualTo(2);
        assertThat(table.gross(Grade.G8)).isEqualByComparingTo("100");
    }

    @Test
    void trimsOutliersAtTenOrMoreSales() {
        List<Comp> sales = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            sales.add(comp(5, "100"));
        }
        sales.add(comp(5, "5000"));
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G9, sales);

        GradePriceTable table = pricer.price(input(comps));

        assertThat(table.get(Grade.G9).salesUsed()).isEqualTo(8);
        assertThat(table.get(Grade.G9).compIds()).doesNotContain(sales.getLast().id());
        assertThat(table.trace().stepsFor("price[9]").getFirst().detail()).contains("trimmed 1 from each end");
    }

    @Test
    void netsGradingCostUnlessTheRawSaleIsHigher() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G7, List.of(comp(1, "300"), comp(2, "300")));
        comps.put(Grade.G8, List.of(comp(1, "500"), comp(2, "500")));
        GradingCost cost = new GradingCost(d("25"), d("5"), d("2"), d("0.02"));
        Map<Grade, BigDecimal> raw = Map.of(Grade.G7, d("280"), Grade.G8, d("300"));

        GradePriceTable table = pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), FLOOR, ratios("0.78"), cost, raw));

        assertThat(table.net(Grade.G8)).isEqualByComparingTo("458.00");
        assertThat(table.get(Grade.G8).rawOverride()).isFalse();
        assertThat(table.net(Grade.G7)).isEqualByComparingTo("280");
        assertThat(table.get(Grade.G7).rawOverride()).isTrue();
    }

    @Test
    void pricesUngradableFromDamagedSalesElseTheBulkFloor() {
        Map<Grade, List<Comp>> comps = exampleComps(false);
        GradePriceTable withDamaged = pricer.price(new GradePricer.Input(AS_OF, comps,
                List.of(comp(3, "60"), comp(4, "60")), FLOOR, ratios("0.78"), NO_COST, Map.of()));
        GradePriceTable withoutDamaged = pricer.price(input(comps));

        assertThat(withDamaged.net(Grade.U)).isEqualByComparingTo("60");
        assertThat(withDamaged.get(Grade.U).method()).isEqualTo(GradePrice.Method.OBSERVED);
        assertThat(withoutDamaged.net(Grade.U)).isEqualByComparingTo("2");
        assertThat(withoutDamaged.get(Grade.U).method()).isEqualTo(GradePrice.Method.FLOOR);
    }

    @Test
    void requiresAStepDownRatioRatherThanGuessing() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G9, List.of(comp(1, "100"), comp(2, "100")));

        assertThatThrownBy(() -> pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), FLOOR, Map.of(), NO_COST, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step-down ratio");
    }

    @Test
    void refusesAStepDownRatioThatWouldStepUp() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G9, List.of(comp(1, "100"), comp(2, "100")));

        assertThatThrownBy(() -> pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), FLOOR, ratios("1.05"), NO_COST, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("above 1");
    }

    @Test
    void neverStepsUpEvenWithARatioAvailable() {
        // Only grade 3 sold. Grades above carry its price unchanged; the ratio is never inverted.
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G3, List.of(comp(1, "30"), comp(2, "30")));

        GradePriceTable table = pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), FLOOR, ratios("0.5"), NO_COST, Map.of()));

        for (int g = 4; g <= 10; g++) {
            assertThat(table.gross(Grade.of(g))).isEqualByComparingTo("30");
            assertThat(table.get(Grade.of(g)).basis()).containsExactly(Grade.G3);
        }
        assertThat(table.gross(Grade.G2)).isEqualByComparingTo("15.00");
    }

    @Test
    void observedConfidenceComesFromTheSalesInTheWindowBeforeTrimming() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        List<Comp> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(comp(i + 1, "1000"));
        }
        comps.put(Grade.G10, ten);
        comps.put(Grade.G8, List.of(comp(1, "500"), comp(2, "500"), comp(3, "500"), comp(4, "500"), comp(5, "500")));
        comps.put(Grade.G7, List.of(comp(1, "400"), comp(2, "400")));
        comps.put(Grade.G6, List.of(comp(1, "300")));

        GradePriceTable table = pricer.price(input(comps));

        // 10 sales, 8 left after trimming: confidence still counts the 10.
        assertThat(table.get(Grade.G10).salesUsed()).isEqualTo(8);
        assertThat(table.get(Grade.G10).salesInWindow()).isEqualTo(10);
        assertThat(table.get(Grade.G10).confidence()).isEqualTo(Confidence.A);
        assertThat(table.get(Grade.G8).confidence()).isEqualTo(Confidence.B);
        assertThat(table.get(Grade.G7).confidence()).isEqualTo(Confidence.C);
        // One sale in 180 days.
        assertThat(table.get(Grade.G6).windowDays()).isEqualTo(180);
        assertThat(table.get(Grade.G6).confidence()).isEqualTo(Confidence.D);
        assertThat(table.get(Grade.G8).windowDays()).isEqualTo(90);
    }

    @Test
    void derivedPricesDropOneLevelBelowTheirBasis() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        List<Comp> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(comp(i + 1, "1000"));
        }
        comps.put(Grade.G10, ten);
        comps.put(Grade.G8, List.of(comp(1, "500"), comp(2, "500"), comp(3, "500"), comp(4, "500"), comp(5, "500")));

        GradePriceTable table = pricer.price(input(comps));

        // Interpolated between B and A: the worse (B), dropped once.
        assertThat(table.get(Grade.G9).method()).isEqualTo(GradePrice.Method.INTERPOLATED);
        assertThat(table.get(Grade.G9).basis()).containsExactly(Grade.G8, Grade.G10);
        assertThat(table.get(Grade.G9).confidence()).isEqualTo(Confidence.C);
        // A step-down chain drops once per step.
        assertThat(table.get(Grade.G7).confidence()).isEqualTo(Confidence.C);
        assertThat(table.get(Grade.G6).basis()).containsExactly(Grade.G7);
        assertThat(table.get(Grade.G6).confidence()).isEqualTo(Confidence.D);
        // Derived prices carry no comps of their own.
        assertThat(table.get(Grade.G9).compIds()).isEmpty();
        assertThat(table.get(Grade.G9).windowDays()).isZero();
        // No damaged sales: U is the floor, confidence D.
        assertThat(table.get(Grade.U).confidence()).isEqualTo(Confidence.D);
    }

    @Test
    void carriedDownPricesAlsoDropConfidence() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G5, List.of(comp(1, "60"), comp(2, "60"), comp(3, "60"), comp(4, "60"), comp(5, "60")));

        GradePriceTable table = pricer.price(input(comps));

        assertThat(table.get(Grade.G5).confidence()).isEqualTo(Confidence.B);
        assertThat(table.get(Grade.G6).method()).isEqualTo(GradePrice.Method.CARRIED_DOWN);
        assertThat(table.get(Grade.G6).confidence()).isEqualTo(Confidence.C);
    }

    @Test
    void observedPricesSkipTheMissingGradeRule() {
        Map<Grade, BigDecimal> observed = pricer.observedPrices(AS_OF, exampleComps(false));

        assertThat(observed).containsOnlyKeys(Grade.G5, Grade.G6, Grade.G7, Grade.G8, Grade.G9, Grade.G10);
        assertThat(observed.get(Grade.G9)).isEqualByComparingTo("900");
    }
}

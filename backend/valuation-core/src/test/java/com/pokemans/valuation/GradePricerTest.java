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

    private final GradePricer pricer = new GradePricer(ParameterSet.defaults());
    private int nextId;

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private Comp comp(int daysAgo, String price) {
        return new Comp("c" + nextId++, AS_OF.minusDays(daysAgo), d(price));
    }

    /** An era ratio table: grades 2 to 9, as StepDownRatios builds it (no grade 1 ratio). */
    private static Map<Grade, BigDecimal> ratios(String ratio) {
        Map<Grade, BigDecimal> ratios = new EnumMap<>(Grade.class);
        for (int g = 2; g <= 9; g++) {
            ratios.put(Grade.of(g), d(ratio));
        }
        return ratios;
    }

    /**
     * Adds grade 1 sales of its own. Grade 1 has no era ratio, so a card with sales in one grade (or
     * adjacent grades only) can't price grade 1 otherwise. Grade 1 never anchors another grade, so
     * this changes nothing else in the table.
     */
    private Map<Grade, List<Comp>> withGradeOne(Map<Grade, List<Comp>> comps) {
        comps.put(Grade.G1, List.of(comp(1, "5"), comp(2, "5")));
        return comps;
    }

    private GradePricer.Input input(Map<Grade, List<Comp>> comps) {
        return new GradePricer.Input(AS_OF, comps, List.of(), ratios("0.78"), NO_COST, Map.of());
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
        // Grade 1 has no era ratio of its own: grade 2 times the era 2-to-3 ratio (85 x 0.78), the doc's 66.
        assertThat(table.get(Grade.G1).method()).isEqualTo(GradePrice.Method.STEP_DOWN);
        assertThat(table.get(Grade.G1).stepDownRatio()).isEqualByComparingTo("0.78");
        assertThat(table.gross(Grade.G1)).isEqualByComparingTo("66.30");
    }

    @Test
    void neverStepsUpFromLowerGrades() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G4, List.of(comp(5, "40"), comp(6, "40")));
        comps.put(Grade.G5, List.of(comp(5, "60"), comp(6, "60")));

        GradePriceTable table = pricer.price(input(withGradeOne(comps)));

        for (int g = 6; g <= 10; g++) {
            assertThat(table.gross(Grade.of(g))).isEqualByComparingTo("60");
            assertThat(table.get(Grade.of(g)).method()).isEqualTo(GradePrice.Method.CARRIED_DOWN);
        }
    }

    @Test
    void recencyWeightingFavoursRecentSales() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G8, List.of(comp(0, "100"), comp(60, "200"), comp(60, "210")));

        GradePriceTable table = pricer.price(input(withGradeOne(comps)));

        // Weights 1, 0.25, 0.25: the recent sale alone carries more than half.
        assertThat(table.gross(Grade.G8)).isEqualByComparingTo("100");
        assertThat(table.get(Grade.G8).salesUsed()).isEqualTo(3);
    }

    @Test
    void extendsTheWindowWhenFewerThanTwoSales() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G8, List.of(comp(10, "100"), comp(120, "90"), comp(200, "10")));

        GradePriceTable table = pricer.price(input(withGradeOne(comps)));

        assertThat(table.get(Grade.G8).salesUsed()).isEqualTo(2);
        assertThat(table.trace().stepsFor("price[8]").getFirst().detail()).contains("180-day window");
    }

    @Test
    void ignoresSalesAfterTheValuationDate() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G8, List.of(comp(5, "100"), comp(6, "100"), comp(-3, "900")));

        GradePriceTable table = pricer.price(input(withGradeOne(comps)));

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

        GradePriceTable table = pricer.price(input(withGradeOne(comps)));

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

        GradePriceTable table = pricer.price(new GradePricer.Input(AS_OF, withGradeOne(comps), List.of(), ratios("0.78"), cost, raw));

        assertThat(table.net(Grade.G8)).isEqualByComparingTo("458.00");
        assertThat(table.get(Grade.G8).rawOverride()).isFalse();
        assertThat(table.net(Grade.G7)).isEqualByComparingTo("280");
        assertThat(table.get(Grade.G7).rawOverride()).isTrue();
    }

    @Test
    void pricesUngradableFromDamagedSalesElseZero() {
        Map<Grade, List<Comp>> comps = exampleComps(false);
        GradePriceTable withDamaged = pricer.price(new GradePricer.Input(AS_OF, comps,
                List.of(comp(3, "60"), comp(4, "60")), ratios("0.78"), NO_COST, Map.of()));
        GradePriceTable withoutDamaged = pricer.price(input(comps));

        assertThat(withDamaged.net(Grade.U)).isEqualByComparingTo("60");
        assertThat(withDamaged.get(Grade.U).method()).isEqualTo(GradePrice.Method.OBSERVED);
        assertThat(withoutDamaged.net(Grade.U)).isEqualByComparingTo("0");
        assertThat(withoutDamaged.get(Grade.U).method()).isEqualTo(GradePrice.Method.NO_SALES);
        assertThat(withoutDamaged.get(Grade.U).confidence()).isEqualTo(Confidence.D);
    }

    @Test
    void refusesACardWithNoGradedSalesRatherThanFlooringIt() {
        assertThatThrownBy(() -> pricer.price(input(new EnumMap<>(Grade.class))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No graded sales");
    }

    @Test
    void requiresAStepDownRatioRatherThanGuessing() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G9, List.of(comp(1, "100"), comp(2, "100")));

        assertThatThrownBy(() -> pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), Map.of(), NO_COST, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step-down ratio");
    }

    private Map<Grade, List<Comp>> spread() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G10, List.of(comp(1, "1000"), comp(2, "1000")));
        comps.put(Grade.G8, List.of(comp(1, "400"), comp(2, "400")));
        comps.put(Grade.G6, List.of(comp(1, "300"), comp(2, "300")));
        return comps;
    }

    @Test
    void withoutAnEraRatioASpreadOfGradesStepsDownWithTheCardsOwnSteepestRatio() {
        GradePriceTable table = pricer.price(new GradePricer.Input(AS_OF, spread(), List.of(), Map.of(), NO_COST, Map.of()));

        // Per-grade ratios: 6 to 8 is sqrt(300/400) = 0.866, 8 to 10 is sqrt(400/1000) = 0.632. The steeper one wins.
        GradePrice g5 = table.get(Grade.G5);
        assertThat(g5.method()).isEqualTo(GradePrice.Method.CARD_STEP_DOWN);
        assertThat(g5.stepDownRatio()).isEqualByComparingTo("0.632455");
        assertThat(g5.gross()).isEqualByComparingTo("189.74");
        assertThat(g5.basis()).containsExactly(Grade.G6);
        assertThat(g5.confidence()).isEqualTo(Confidence.D); // one below grade 6's C
        assertThat(table.gross(Grade.G4)).isLessThan(g5.gross());
        assertThat(table.get(Grade.G9).method()).isEqualTo(GradePrice.Method.INTERPOLATED);
        assertThat(table.get(Grade.G9).stepDownRatio()).isNull();
        assertThat(table.trace().stepsFor("price[5]").getFirst().rule()).isEqualTo("card's own step-down");
    }

    @Test
    void anEraRatioStillWinsOverTheCardsOwnLadder() {
        GradePriceTable table = pricer.price(new GradePricer.Input(AS_OF, spread(), List.of(), ratios("0.78"), NO_COST, Map.of()));

        assertThat(table.get(Grade.G5).method()).isEqualTo(GradePrice.Method.STEP_DOWN);
        assertThat(table.get(Grade.G5).stepDownRatio()).isEqualByComparingTo("0.78");
    }

    @Test
    void aGradeOnePremiumNeverLiftsGradesTwoToFour() {
        // PSA 1s have their own collectors: here a 1 sold above the 5. Grade 1 keeps its own price,
        // but grades 2-4 step down from the 5 instead of being interpolated up towards the 1.
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G10, List.of(comp(1, "1000"), comp(2, "1000")));
        comps.put(Grade.G5, List.of(comp(1, "100"), comp(2, "100")));
        comps.put(Grade.G1, List.of(comp(1, "200"), comp(2, "200")));

        GradePriceTable table = pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), Map.of(), NO_COST, Map.of()));

        assertThat(table.get(Grade.G1).method()).isEqualTo(GradePrice.Method.OBSERVED);
        assertThat(table.gross(Grade.G1)).isEqualByComparingTo("200");
        // The ladder ignores grade 1 too: (100 / 1000) ^ (1/5) per grade.
        assertThat(table.get(Grade.G4).method()).isEqualTo(GradePrice.Method.CARD_STEP_DOWN);
        assertThat(table.get(Grade.G4).stepDownRatio()).isEqualByComparingTo("0.630957");
        assertThat(table.gross(Grade.G4)).isEqualByComparingTo("63.10");
        for (int g = 2; g <= 4; g++) {
            assertThat(table.gross(Grade.of(g))).isLessThan(table.gross(Grade.G5));
            assertThat(table.get(Grade.of(g)).basis()).doesNotContain(Grade.G1);
        }
    }

    @Test
    void gradeOneSalesAloneCannotPriceTheOtherGrades() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G1, List.of(comp(1, "200"), comp(2, "200")));

        assertThatThrownBy(() -> pricer.price(input(comps)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only grade 1 sales");
    }

    @Test
    void adjacentGradesAreNotASpread() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G10, List.of(comp(1, "1000"), comp(2, "1000")));
        comps.put(Grade.G9, List.of(comp(1, "500"), comp(2, "500")));

        assertThatThrownBy(() -> pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), Map.of(), NO_COST, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too few grades");
    }

    @Test
    void theCardsOwnLadderNeverStepsUp() {
        // The 8 sold above the 10: the ladder is capped at 1, so lower grades carry the 8's price, never more.
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G10, List.of(comp(1, "400"), comp(2, "400")));
        comps.put(Grade.G8, List.of(comp(1, "500"), comp(2, "500")));

        GradePriceTable table = pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), Map.of(), NO_COST, Map.of()));

        assertThat(table.get(Grade.G7).stepDownRatio()).isEqualByComparingTo("1");
        assertThat(table.gross(Grade.G1)).isEqualByComparingTo("500");
    }

    @Test
    void refusesAStepDownRatioThatWouldStepUp() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G9, List.of(comp(1, "100"), comp(2, "100")));

        assertThatThrownBy(() -> pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), ratios("1.05"), NO_COST, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("above 0 and at most 1");
        assertThatThrownBy(() -> pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), ratios("0"), NO_COST, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("above 0 and at most 1");
    }

    @Test
    void neverStepsUpEvenWithARatioAvailable() {
        // Only grade 3 sold. Grades above carry its price unchanged; the ratio is never inverted.
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G3, List.of(comp(1, "30"), comp(2, "30")));

        GradePriceTable table = pricer.price(new GradePricer.Input(AS_OF, withGradeOne(comps), List.of(), ratios("0.5"), NO_COST, Map.of()));

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
        // No damaged sales: U is valued at zero (no bulk floor), confidence D.
        assertThat(table.get(Grade.U).confidence()).isEqualTo(Confidence.D);
    }

    @Test
    void carriedDownPricesAlsoDropConfidence() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G5, List.of(comp(1, "60"), comp(2, "60"), comp(3, "60"), comp(4, "60"), comp(5, "60")));

        GradePriceTable table = pricer.price(input(withGradeOne(comps)));

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

    @Test
    void interpolationIsCappedAtTheHigherAnchorWhenAnchorsAreOutOfOrder() {
        // The 8 sold above the 10: interpolating 9 between them would put it above the 10.
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G10, List.of(comp(1, "400"), comp(2, "400")));
        comps.put(Grade.G8, List.of(comp(1, "500"), comp(2, "500")));

        GradePriceTable table = pricer.price(input(comps));

        assertThat(table.get(Grade.G9).method()).isEqualTo(GradePrice.Method.INTERPOLATED);
        assertThat(table.gross(Grade.G9)).isEqualByComparingTo("400");
        assertThat(table.trace().stepsFor("price[9]").getFirst().detail()).contains("capped");
    }

    @Test
    void inOrderInterpolationIsNotMarkedCapped() {
        GradePriceTable table = pricer.price(input(spread()));

        assertThat(table.trace().stepsFor("price[9]").getFirst().detail()).doesNotContain("capped");
    }

    @Test
    void gradeOneIgnoresAnEraOneToTwoRatioAndBorrowsTheTwoToThree() {
        // Sales at 10 and 2 only. A grade 1 ratio of 0.99 is passed (other cards' PSA 1 premiums): ignored.
        // Grade 1 steps down from 2 with the era's 2-to-3 ratio instead.
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G10, List.of(comp(1, "1000"), comp(2, "1000")));
        comps.put(Grade.G2, List.of(comp(1, "100"), comp(2, "100")));
        Map<Grade, BigDecimal> ratios = new EnumMap<>(ratios("0.78"));
        ratios.put(Grade.G1, d("0.99"));

        GradePriceTable table = pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), ratios, NO_COST, Map.of()));

        GradePrice g1 = table.get(Grade.G1);
        assertThat(g1.method()).isEqualTo(GradePrice.Method.STEP_DOWN);
        assertThat(g1.stepDownRatio()).isEqualByComparingTo("0.78");
        assertThat(g1.gross()).isEqualByComparingTo("78.00");
        assertThat(g1.basis()).containsExactly(Grade.G2);
        assertThat(table.trace().stepsFor("price[1]").getFirst().rule()).contains("2-to-3");
        for (int g = 3; g <= 9; g++) {
            assertThat(table.get(Grade.of(g)).method()).isEqualTo(GradePrice.Method.INTERPOLATED);
        }
    }

    @Test
    void gradeOneWithoutAnEraTwoToThreeRatioUsesTheCardsLadder() {
        // No era ratios at all, sales at 10 and 2: grade 1 from the card's own ladder, (100 / 1000) ^ (1/8).
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G10, List.of(comp(1, "1000"), comp(2, "1000")));
        comps.put(Grade.G2, List.of(comp(1, "100"), comp(2, "100")));

        GradePrice g1 = pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), Map.of(), NO_COST, Map.of())).get(Grade.G1);

        assertThat(g1.method()).isEqualTo(GradePrice.Method.CARD_STEP_DOWN);
        assertThat(g1.stepDownRatio()).isEqualByComparingTo("0.749894");
        assertThat(g1.gross()).isEqualByComparingTo("74.99");
    }

    @Test
    void oneSoldGradeWithAnEraTablePricesEveryGradeBelow() {
        // One sold grade and era ratios for 2-9: grades 8 down to 2 step down, and grade 1 borrows 2-to-3.
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G9, List.of(comp(1, "100"), comp(2, "100")));

        GradePriceTable table = pricer.price(input(comps));

        assertThat(table.get(Grade.G1).method()).isEqualTo(GradePrice.Method.STEP_DOWN);
        assertThat(table.gross(Grade.G1)).isLessThanOrEqualTo(table.gross(Grade.G2));
    }

    @Test
    void withNoEraRatioAndNoLadderTheCardIsRefused() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G9, List.of(comp(1, "100"), comp(2, "100")));

        assertThatThrownBy(() -> pricer.price(new GradePricer.Input(AS_OF, comps, List.of(), Map.of(), NO_COST, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No era step-down ratio for grade 8 to 9");
    }

    @Test
    void refusesNullInputsWithAClearMessage() {
        Map<Grade, List<Comp>> comps = exampleComps(false);
        assertThatThrownBy(() -> new GradePricer.Input(AS_OF, null, List.of(), Map.of(), NO_COST, Map.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("gradedComps");
        assertThatThrownBy(() -> new GradePricer.Input(AS_OF, comps, null, Map.of(), NO_COST, Map.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("damagedRawComps");
        assertThatThrownBy(() -> new GradePricer.Input(AS_OF, comps, List.of(), null, NO_COST, Map.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("stepDownRatios");
        assertThatThrownBy(() -> new GradePricer.Input(AS_OF, comps, List.of(), Map.of(), NO_COST, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("rawConditionPrices");
        assertThatThrownBy(() -> new GradePricer.Input(AS_OF, comps, List.of(), Map.of(), null, Map.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("gradingCost");
        assertThatThrownBy(() -> new GradePricer.Input(null, comps, List.of(), Map.of(), NO_COST, Map.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("asOf");
    }

    // Window boundaries: the window is inclusive of asOf and of the day exactly windowDays before it.

    private GradePrice gradeEightWith(Comp... sales) {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G8, List.of(sales));
        return pricer.price(input(withGradeOne(comps))).get(Grade.G8);
    }

    @Test
    void aSaleExactly90DaysBackIsInTheWindow() {
        GradePrice g8 = gradeEightWith(comp(5, "100"), comp(6, "100"), comp(90, "100"));

        assertThat(g8.windowDays()).isEqualTo(90);
        assertThat(g8.salesInWindow()).isEqualTo(3);
    }

    @Test
    void aSale91DaysBackIsOutOfTheWindow() {
        GradePrice g8 = gradeEightWith(comp(5, "100"), comp(6, "100"), comp(91, "100"));

        assertThat(g8.windowDays()).isEqualTo(90);
        assertThat(g8.salesInWindow()).isEqualTo(2);
    }

    @Test
    void aSaleExactly180DaysBackIsInTheExtendedWindow() {
        GradePrice g8 = gradeEightWith(comp(5, "100"), comp(180, "100"));

        assertThat(g8.windowDays()).isEqualTo(180);
        assertThat(g8.salesInWindow()).isEqualTo(2);
    }

    @Test
    void aSale181DaysBackIsOutOfTheExtendedWindow() {
        GradePrice g8 = gradeEightWith(comp(5, "100"), comp(181, "100"));

        assertThat(g8.windowDays()).isEqualTo(180);
        assertThat(g8.salesInWindow()).isEqualTo(1);
    }

    @Test
    void aSaleOnTheValuationDateCounts() {
        GradePrice g8 = gradeEightWith(comp(0, "100"), comp(1, "100"));

        assertThat(g8.windowDays()).isEqualTo(90);
        assertThat(g8.salesInWindow()).isEqualTo(2);
    }

    @Test
    void aGradeWithAllSalesOutsideTheExtendedWindowIsNotObserved() {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G10, List.of(comp(1, "1000"), comp(2, "1000")));
        comps.put(Grade.G8, List.of(comp(200, "500")));

        GradePriceTable table = pricer.price(input(withGradeOne(comps)));

        assertThat(table.get(Grade.G8).method()).isEqualTo(GradePrice.Method.STEP_DOWN);
    }
}

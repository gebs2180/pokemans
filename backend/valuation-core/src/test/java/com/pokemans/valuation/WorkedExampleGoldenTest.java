package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden test: the worked example in the Raw Collection Valuation Methodology (2026-10-15).
 * A hypothetical raw vintage holo: FMV $411, RV $481, QSV $314, stressed FMV $181, FLV $107,
 * advance $64 at α = 60%. The doc rounds to whole dollars; so does this test.
 *
 * <p>Net prices are taken from the doc's grade table as given: only grades 5 to 10 have sales, so
 * grades 4 down to 1 are derived by the 0.78 step-down. The pricer's own derivation is covered in
 * {@link GradePricerTest}.
 */
class WorkedExampleGoldenTest {

    private final ParameterSet params = ParameterSet.defaults();

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static GradeDistribution exampleDistribution() {
        Map<Grade, BigDecimal> p = new EnumMap<>(Grade.class);
        p.put(Grade.G10, d("0.02"));
        p.put(Grade.G9, d("0.10"));
        p.put(Grade.G8, d("0.25"));
        p.put(Grade.G7, d("0.28"));
        p.put(Grade.G6, d("0.18"));
        p.put(Grade.G5, d("0.10"));
        p.put(Grade.G4, d("0.05"));
        p.put(Grade.U, d("0.02"));
        return GradeDistribution.of(p);
    }

    private static GradePriceTable examplePrices() {
        Map<Grade, GradePrice> prices = new EnumMap<>(Grade.class);
        int sales = 12; // enough observed sales for comp confidence A
        prices.put(Grade.G10, GradePrice.given(Grade.G10, d("3000"), GradePrice.Method.OBSERVED, sales));
        prices.put(Grade.G9, GradePrice.given(Grade.G9, d("900"), GradePrice.Method.OBSERVED, sales));
        prices.put(Grade.G8, GradePrice.given(Grade.G8, d("450"), GradePrice.Method.OBSERVED, sales));
        prices.put(Grade.G7, GradePrice.given(Grade.G7, d("300"), GradePrice.Method.OBSERVED, sales));
        prices.put(Grade.G6, GradePrice.given(Grade.G6, d("220"), GradePrice.Method.OBSERVED, sales));
        prices.put(Grade.G5, GradePrice.given(Grade.G5, d("170"), GradePrice.Method.OBSERVED, sales));
        prices.put(Grade.G4, GradePrice.given(Grade.G4, d("133"), GradePrice.Method.STEP_DOWN, 0));
        prices.put(Grade.G3, GradePrice.given(Grade.G3, d("104"), GradePrice.Method.STEP_DOWN, 0));
        prices.put(Grade.G2, GradePrice.given(Grade.G2, d("81"), GradePrice.Method.STEP_DOWN, 0));
        // Carries no probability centrally or under stress.
        prices.put(Grade.G1, GradePrice.given(Grade.G1, d("63"), GradePrice.Method.STEP_DOWN, 0));
        prices.put(Grade.U, GradePrice.given(Grade.U, d("60"), GradePrice.Method.OBSERVED, sales));
        return new GradePriceTable(prices, new Trace());
    }

    private static CardValuator.Input exampleInput() {
        return new CardValuator.Input(
                "worked-example-vintage-holo",
                exampleDistribution(),
                examplePrices(),
                d("0.08"),   // monthly volatility
                d("10"),     // ~10 sales a month: mid liquidity (s = 5%, d = 10%)
                1,           // one copy: depth discount at its 10% floor
                Confidence.A);
    }

    private static BigDecimal dollars(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP);
    }

    @Test
    void theStressNeverGainsFromAGradeOnePremium() {
        // A PSA 1 premium (PSA 1 collectors) above grades 2-4: the stress shifts a grade 3 card into grade 1,
        // which must not raise its value. Stressed prices are capped at the grade above, so grade 1 counts at 81.
        Map<Grade, GradePrice> prices = new EnumMap<>(examplePrices().all().stream()
                .collect(java.util.stream.Collectors.toMap(GradePrice::grade, p -> p)));
        prices.put(Grade.G1, GradePrice.given(Grade.G1, d("500"), GradePrice.Method.OBSERVED, 12));
        Map<Grade, BigDecimal> p = new EnumMap<>(Grade.class);
        p.put(Grade.G3, d("1"));
        CardValuation value = new CardValuator(params).value(new CardValuator.Input("grade-one-premium",
                GradeDistribution.of(p), new GradePriceTable(prices, new Trace()), d("0.08"), d("10"), 1, Confidence.A));

        assertThat(value.fmv()).isEqualByComparingTo("104.00");
        assertThat(value.stressedFmv()).isEqualByComparingTo("81.00");
    }

    @Test
    void stressedDistributionMatchesTheDocTable() {
        GradeDistribution stressed = exampleDistribution().shiftDown(2);

        assertThat(stressed.p(Grade.G10)).isEqualByComparingTo("0");
        assertThat(stressed.p(Grade.G9)).isEqualByComparingTo("0");
        assertThat(stressed.p(Grade.G8)).isEqualByComparingTo("0.02");
        assertThat(stressed.p(Grade.G7)).isEqualByComparingTo("0.10");
        assertThat(stressed.p(Grade.G6)).isEqualByComparingTo("0.25");
        assertThat(stressed.p(Grade.G5)).isEqualByComparingTo("0.28");
        assertThat(stressed.p(Grade.G4)).isEqualByComparingTo("0.18");
        assertThat(stressed.p(Grade.G3)).isEqualByComparingTo("0.10");
        assertThat(stressed.p(Grade.G2)).isEqualByComparingTo("0.05");
        assertThat(stressed.p(Grade.G1)).isEqualByComparingTo("0");
        assertThat(stressed.p(Grade.U)).isEqualByComparingTo("0.02");
    }

    @Test
    void reproducesTheWorkedExample() {
        CardValuation v = new CardValuator(params).value(exampleInput());

        assertThat(dollars(v.fmv())).isEqualByComparingTo("411");
        assertThat(dollars(v.rv())).isEqualByComparingTo("481");
        assertThat(dollars(v.qsv())).isEqualByComparingTo("314");
        assertThat(dollars(v.stressedFmv())).isEqualByComparingTo("181");
        assertThat(dollars(v.flv())).isEqualByComparingTo("107");

        // Advance on this single card at the lender's α = 60% (the doc's 107 × 0.60).
        BigDecimal advance = PortfolioEvaluator.advance(v.flv(), d("0.60"));
        assertThat(dollars(advance)).isEqualByComparingTo("64");
    }

    @Test
    void exactIntermediateValues() {
        CardValuation v = new CardValuator(params).value(exampleInput());

        assertThat(v.fmv()).isEqualByComparingTo("410.95");
        assertThat(v.rv()).isEqualByComparingTo("480.81");
        assertThat(v.qsv()).isEqualByComparingTo("314.38");
        assertThat(v.stressedFmv()).isEqualByComparingTo("181.19");
        assertThat(v.flv()).isEqualByComparingTo("107.25");
        assertThat(v.liquidityTier()).isEqualTo(ParameterSet.LiquidityTier.MID);
        assertThat(v.searchPremium()).isEqualByComparingTo("0.05");
        assertThat(v.speedDiscount()).isEqualByComparingTo("0.10");
        assertThat(v.depthDiscount()).isEqualByComparingTo("0.10");
    }

    @Test
    void derivedPricesDropConfidenceOneLevelAndStressStaysTwoGrades() {
        CardValuation v = new CardValuator(params).value(exampleInput());

        assertThat(v.confidence()).isEqualTo(Confidence.B);
        assertThat(v.stressShift()).isEqualTo(2);
    }

    @Test
    void everyValueIsTraced() {
        CardValuation v = new CardValuator(params).value(exampleInput());

        for (String subject : new String[] {"FMV", "RV", "QSV", "FMV_stressed", "volatility_shock", "depth_discount", "FLV", "confidence"}) {
            assertThat(v.trace().stepsFor(subject)).as(subject).isNotEmpty();
        }
        assertThat(v.trace().stepsFor("confidence").getFirst().detail()).contains("4").contains("3");
        assertThat(v.trace().stepsFor("parameters").getFirst().value()).isEqualTo("1.0.0");
    }
}

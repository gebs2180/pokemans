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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CardValuatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);
    private static final GradingCost NO_COST = new GradingCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private final ParameterSet params = ParameterSet.defaults();
    private final CardValuator valuator = new CardValuator(params);
    private int nextId;

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    /** Every grade observed with plenty of sales, except the grades given as derived. */
    private static GradePriceTable prices(String price, Grade... derived) {
        Map<Grade, GradePrice> prices = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            prices.put(grade, GradePrice.given(grade, d(price), GradePrice.Method.OBSERVED, 20));
        }
        for (Grade grade : derived) {
            prices.put(grade, GradePrice.given(grade, d(price), GradePrice.Method.STEP_DOWN, 0));
        }
        return new GradePriceTable(prices, new Trace());
    }

    private static CardValuator.Input input(GradePriceTable prices, Confidence photo, String sigma, String salesPerMonth, int copies) {
        return new CardValuator.Input("card", GradeDistribution.certain(Grade.G10), prices, d(sigma), d(salesPerMonth), copies, photo);
    }

    private List<Comp> tenSales(String price, String outlierLow, String outlierHigh) {
        List<Comp> sales = new ArrayList<>();
        sales.add(new Comp("c" + nextId++, AS_OF.minusDays(1), d(outlierLow)));
        for (int i = 0; i < 8; i++) {
            sales.add(new Comp("c" + nextId++, AS_OF.minusDays(i + 1), d(price)));
        }
        sales.add(new Comp("c" + nextId++, AS_OF.minusDays(1), d(outlierHigh)));
        return sales;
    }

    @Test
    void flvNeverExceedsQsv() throws Exception {
        // With the 1.0.0 parameters the depth discount is always at least d, so FLV stays under QSV anyway.
        // A parameter set with a steeper speed discount (30% at mid liquidity) breaks that: one copy at
        // 10 sales a month and no volatility gives 1000 x 0.85 x 1 x 0.90 = 765, above QSV 1000 x 0.85 x 0.70
        // = 595. FLV is held at QSV.
        java.util.Properties p = new java.util.Properties();
        try (var in = ParameterSet.class.getResourceAsStream("/parameter-sets/1.0.0.properties")) {
            p.load(in);
        }
        p.setProperty("values.speedDiscount.mid", "0.30");
        CardValuation v = new CardValuator(ParameterSet.fromProperties(p)).value(input(prices("1000"), Confidence.A, "0", "10", 1));

        assertThat(v.qsv()).isEqualByComparingTo("595.00");
        assertThat(v.flv()).isEqualByComparingTo("595.00");
        assertThat(v.trace().stepsFor("FLV").getFirst().detail()).contains("formula gave 765.00, capped at QSV");
    }

    @Test
    void compConfidenceCountsSalesInTheWindowBeforeTrimming() {
        // A real pricer table: ten sales per grade, trimmed to eight. Ten count towards confidence A.
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        comps.put(Grade.G10, tenSales("1000", "10", "9000"));
        comps.put(Grade.G8, tenSales("400", "5", "5000"));
        GradePriceTable table = new GradePricer(params).price(new GradePricer.Input(AS_OF, comps, List.of(), Map.of(), NO_COST, Map.of()));
        assertThat(table.get(Grade.G10).salesUsed()).isEqualTo(8);
        assertThat(table.get(Grade.G10).salesInWindow()).isEqualTo(10);

        CardValuation v = valuator.value(input(table, Confidence.A, "0.05", "30", 1));

        assertThat(v.confidence()).isEqualTo(Confidence.A);
        assertThat(v.stressShift()).isEqualTo(2);
        assertThat(v.trace().stepsFor("confidence_base").getFirst().detail()).contains("weighted sales 10.00");
    }

    @Test
    void derivedCheckUsesTheThreeGradeStressAtConfidenceC() {
        // Grade 7 is derived. The 2-grade stress lands on 8 (observed), the 3-grade stress on 7 (derived).
        // Photo confidence C means a 3-grade stress, so the derived 7 is in play and confidence drops to D.
        CardValuation atC = valuator.value(input(prices("100", Grade.G7), Confidence.C, "0.05", "30", 1));

        assertThat(atC.confidence()).isEqualTo(Confidence.D);
        assertThat(atC.stressShift()).isEqualTo(3);
        assertThat(atC.trace().stepsFor("confidence").getFirst().detail()).contains("7");
    }

    @Test
    void derivedCheckUsesTheTwoGradeStressAtConfidenceA() {
        CardValuation atA = valuator.value(input(prices("100", Grade.G7), Confidence.A, "0.05", "30", 1));

        assertThat(atA.confidence()).isEqualTo(Confidence.A);
        assertThat(atA.stressShift()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({
            "4.99,  LOW,  0.10, 0.20",
            "5,     MID,  0.05, 0.10",
            "20,    MID,  0.05, 0.10",
            "20.01, HIGH, 0.00, 0.05"})
    void liquidityTierBoundaries(String salesPerMonth, ParameterSet.LiquidityTier tier, String s, String discount) {
        CardValuation v = valuator.value(input(prices("100"), Confidence.A, "0.05", salesPerMonth, 1));

        assertThat(v.liquidityTier()).isEqualTo(tier);
        assertThat(v.searchPremium()).isEqualByComparingTo(s);
        assertThat(v.speedDiscount()).isEqualByComparingTo(discount);
        assertThat(v.rv()).isEqualByComparingTo(Money.cents(d("100").multiply(BigDecimal.ONE.add(d("0.12")).add(d(s)))));
    }

    @Test
    void volatilityShockIsClampedAtZero() {
        // k·σ·√t = 2 × 0.5 × √2 > 1.
        CardValuation v = valuator.value(input(prices("100"), Confidence.A, "0.5", "30", 1));

        assertThat(v.volatilityShock()).isEqualByComparingTo("0");
        assertThat(v.flv()).isEqualByComparingTo("0");
    }

    @Test
    void depthDiscountTakesTheCapWithNoSales() {
        CardValuation v = valuator.value(input(prices("100"), Confidence.A, "0.05", "0", 1));

        assertThat(v.depthDiscount()).isEqualByComparingTo("0.50");
        assertThat(v.liquidityTier()).isEqualTo(ParameterSet.LiquidityTier.LOW);
    }

    @Test
    void depthDiscountIsTheShareOfAMonthsSalesBetweenFloorAndCap() {
        assertThat(valuator.value(input(prices("100"), Confidence.A, "0.05", "30", 1)).depthDiscount()).isEqualByComparingTo("0.10");
        assertThat(valuator.value(input(prices("100"), Confidence.A, "0.05", "10", 3)).depthDiscount()).isEqualByComparingTo("0.3");
        assertThat(valuator.value(input(prices("100"), Confidence.A, "0.05", "10", 9)).depthDiscount()).isEqualByComparingTo("0.50");
    }

    @Test
    void rejectsImpossibleInputs() {
        GradePriceTable table = prices("100");
        assertThatThrownBy(() -> input(table, Confidence.A, "-0.01", "10", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("volatility");
        assertThatThrownBy(() -> input(table, Confidence.A, "0.05", "-1", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Sales per month");
        assertThatThrownBy(() -> input(table, Confidence.A, "0.05", "10", 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Copies held");
        assertThatThrownBy(() -> new CardValuator.Input("card", GradeDistribution.certain(Grade.G10), null, d("0"), d("1"), 1, Confidence.A))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("prices");
    }

    @Test
    void penaltiesDropConfidenceBeforeTheStressIsChosen() {
        CardValuator.Input plain = input(prices("100"), Confidence.A, "0.05", "30", 1);
        CardValuator.Input stale = new CardValuator.Input("card", plain.distribution(), plain.prices(), plain.monthlyVolatility(),
                plain.salesPerMonth(), 1, Confidence.A,
                List.of(new ConfidencePenalty("price table 40 days old", 2), new ConfidencePenalty("nothing", 0)), false);

        CardValuation a = valuator.value(plain);
        CardValuation c = valuator.value(stale);

        assertThat(a.confidence()).isEqualTo(Confidence.A);
        assertThat(a.stressShift()).isEqualTo(2);
        assertThat(c.confidence()).isEqualTo(Confidence.C);
        assertThat(c.stressShift()).isEqualTo(3);
        assertThat(c.trace().stepsFor("confidence_penalty")).hasSize(1);
    }

    @Test
    void theOfferConfidenceSkipsPenaltiesThatOnlyBearOnTheFlv() {
        // Likely a 9 or 10 (observed); the stress reaches 7 and 8, which are derived; σ is a fallback.
        GradeDistribution likely = GradeDistribution.of(Map.of(Grade.G9, d("0.5"), Grade.G10, d("0.5")));
        GradePriceTable table = prices("100", Grade.G7, Grade.G8);
        CardValuator.Input in = new CardValuator.Input("card", likely, table, d("0.20"), d("30"), 1, Confidence.B,
                List.of(new ConfidencePenalty("fallback sigma 0.20", 1, true)), false);

        CardValuation v = valuator.value(in);

        // The FLV takes both: B, fallback σ to C, derived grades in the stress to D.
        assertThat(v.confidence()).isEqualTo(Confidence.D);
        // The offer uses neither σ nor the stress: it stays at B.
        assertThat(v.offerConfidence()).isEqualTo(Confidence.B);
        assertThat(v.trace().stepsFor("offer_confidence_penalty")).isEmpty();
        assertThat(v.trace().stepsFor("offer_confidence").getFirst().detail()).contains("FLV-only penalties");
    }

    @Test
    void theOfferConfidenceStillTakesStaleTablesAndLikelyDerivedPrices() {
        GradeDistribution likely = GradeDistribution.of(Map.of(Grade.G8, d("0.5"), Grade.G9, d("0.5")));
        CardValuator.Input in = new CardValuator.Input("card", likely, prices("100", Grade.G8), d("0.05"), d("30"), 1, Confidence.A,
                List.of(new ConfidencePenalty("price table 40 days old", 1), new ConfidencePenalty("fallback sigma 0.20", 1, true)), false);

        CardValuation v = valuator.value(in);

        // A, stale table to B, then the derived 8 the card may well be: C.
        assertThat(v.offerConfidence()).isEqualTo(Confidence.C);
        assertThat(v.trace().stepsFor("offer_confidence_penalty")).singleElement()
                .satisfies(st -> assertThat(st.rule()).startsWith("price table 40 days old"));
        assertThat(v.trace().stepsFor("offer_confidence").getFirst().detail()).contains("derived grades 8");
    }

    @Test
    void aKnownGradeGetsNoConditionStress() {
        Map<Grade, GradePrice> ladder = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            String price = grade.isUngradable() ? "0" : Integer.toString(10 * grade.ordinal());
            ladder.put(grade, GradePrice.given(grade, d(price), GradePrice.Method.OBSERVED, 20));
        }
        GradePriceTable table = new GradePriceTable(ladder, new Trace());
        CardValuator.Input graded = new CardValuator.Input("slab", GradeDistribution.certain(Grade.G8), table, d("0.05"), d("30"), 1,
                Confidence.A, List.of(), true);

        CardValuation v = valuator.value(graded);

        assertThat(v.stressShift()).isZero();
        assertThat(v.stressedFmv()).isEqualByComparingTo(v.fmv());
    }

    @Test
    void penaltiesCantRaiseConfidence() {
        assertThatThrownBy(() -> new ConfidencePenalty("up", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CardValuator.Input("card", GradeDistribution.certain(Grade.G10), prices("1"), d("0"), d("1"), 1,
                Confidence.A, null, false)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nettingASnapshotMatchesThePricer() {
        GradingCost cost = new GradingCost(d("25"), d("5"), d("2"), d("0.02"));
        GradePriceTable gross = prices("100");

        GradePriceTable net = GradePriceTable.netOf(gross.all(), cost);

        // 100 - 32 - 2 = 66; U is already a raw sale price.
        assertThat(net.net(Grade.G9)).isEqualByComparingTo("66.00");
        assertThat(net.gross(Grade.G9)).isEqualByComparingTo("100");
        assertThat(net.net(Grade.U)).isEqualByComparingTo("100");
        assertThat(GradePriceTable.netOf(prices("20").all(), cost).net(Grade.G9)).isEqualByComparingTo("0");
        assertThat(net.atGross().net(Grade.G9)).isEqualByComparingTo("100");
    }

    @Test
    void gradingCostRefusesNonsense() {
        assertThatThrownBy(() -> new GradingCost(d("-1"), d("0"), d("0"), d("0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GradingCost(d("0"), d("0"), d("0"), d("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GradingCost(d("0"), d("0"), d("0"), d("-0.1"))).isInstanceOf(IllegalArgumentException.class);
    }
}

package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfferLadderTest {

    private final ParameterSet params = ParameterSet.defaults();
    private final OfferLadder ladder = new OfferLadder(params, new BigDecimal("0.10"));

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** Net prices: 10 at 1000, 9 at 500, 8 at 300, then 100 down to 1, with a grade 1 premium of 400. U at 20. */
    private static GradePriceTable prices() {
        Map<Grade, GradePrice> p = new EnumMap<>(Grade.class);
        String[] net = {"20", "400", "100", "100", "100", "100", "100", "100", "300", "500", "1000"};
        for (Grade g : Grade.values()) {
            p.put(g, GradePrice.given(g, d(net[g.number()]), GradePrice.Method.OBSERVED, 10));
        }
        return new GradePriceTable(p, new Trace());
    }

    @Test
    void eachRungIsTheQuickSaleValueLessTheBuyersMargin() {
        GradeDistribution dist = GradeDistribution.of(Map.of(Grade.G10, d("0.3"), Grade.G9, d("0.5"), Grade.G8, d("0.2")));

        // 10 sales a month is mid liquidity: d = 10%. Factor 0.85 x 0.90 x 0.90 = 0.6885.
        OfferLadder.Offer offer = ladder.offer(dist, prices(), d("10"));

        assertThat(offer.rungs()).hasSize(11);
        assertThat(rung(offer, Grade.G10).price()).isEqualByComparingTo("688.50");
        assertThat(rung(offer, Grade.G9).price()).isEqualByComparingTo("344.25");
        assertThat(rung(offer, Grade.G8).price()).isEqualByComparingTo("206.55");
        assertThat(rung(offer, Grade.G9).probability()).isEqualByComparingTo("0.5");
        // 0.3 x 688.50 + 0.5 x 344.25 + 0.2 x 206.55 = 419.985, to the even cent
        assertThat(offer.price()).isEqualByComparingTo("419.98");
        assertThat(offer.liquidityTier()).isEqualTo(ParameterSet.LiquidityTier.MID);
        assertThat(offer.rungs().getFirst().grade()).isEqualTo(Grade.G10);
    }

    @Test
    void aWorseGradeNeverPaysMore() {
        // Grade 1 sells at 400 (its own collectors), but an offer never pays more for a worse card.
        OfferLadder.Offer offer = ladder.offer(GradeDistribution.certain(Grade.G1), prices(), d("10"));

        assertThat(rung(offer, Grade.G1).price()).isEqualByComparingTo(rung(offer, Grade.G2).price());
        assertThat(rung(offer, Grade.U).price()).isEqualByComparingTo("13.77");
        BigDecimal above = null;
        for (OfferLadder.Rung r : offer.rungs()) {
            if (above != null && !r.grade().isUngradable()) {
                assertThat(r.price()).isLessThanOrEqualTo(above);
            }
            above = r.price();
        }
    }

    @Test
    void thinMarketsGetASteeperSpeedDiscount() {
        GradeDistribution dist = GradeDistribution.certain(Grade.G10);

        BigDecimal liquid = rung(ladder.offer(dist, prices(), d("30")), Grade.G10).price();
        BigDecimal thin = rung(ladder.offer(dist, prices(), d("2")), Grade.G10).price();

        assertThat(liquid).isEqualByComparingTo("726.75"); // 1000 x 0.85 x 0.95 x 0.90
        assertThat(thin).isEqualByComparingTo("612.00");   // 1000 x 0.85 x 0.80 x 0.90
    }

    @Test
    void refusesAMarginOutsideZeroToOne() {
        assertThatThrownBy(() -> new OfferLadder(params, d("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OfferLadder(params, d("-0.1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ladder.offer(null, prices(), d("1"))).isInstanceOf(NullPointerException.class);
    }

    // Rule 1 (Zach, 2026-11-09): from 1.4.0 an offer's 10 rung is at most M = 2.5 times its 9 rung.

    private static final ParameterSet V130 = ParameterSet.load("1.3.0");
    private static final ParameterSet V140 = ParameterSet.load("1.4.0");

    /** Net prices with the 9 at {@code nine}, the 10 at {@code ten} and the 8 at 200; 100 below, U 20. */
    private static GradePriceTable tenPremium(String nine, String ten) {
        Map<Grade, GradePrice> p = new EnumMap<>(Grade.class);
        for (Grade g : Grade.values()) {
            String net = switch (g) {
                case G10 -> ten;
                case G9 -> nine;
                case G8 -> "200";
                case U -> "20";
                default -> "100";
            };
            p.put(g, GradePrice.given(g, d(net), GradePrice.Method.OBSERVED, 10));
        }
        return new GradePriceTable(p, new Trace());
    }

    /** 25% grade 10, 50% grade 9, 25% grade 8. */
    private static GradeDistribution cleanCard() {
        return GradeDistribution.of(Map.of(Grade.G10, d("0.25"), Grade.G9, d("0.5"), Grade.G8, d("0.25")));
    }

    @Test
    void aTenAtFiveTimesTheNineIsCappedAtTwoAndAHalf() {
        // The 10 is 5x the 9 and the 8 is half of it. Factor 0.6885: rungs 10 1377.00, 9 275.40, 8 137.70.
        GradePriceTable prices = tenPremium("400", "2000");

        OfferLadder.Offer before = new OfferLadder(V130, d("0.10")).offer(cleanCard(), prices, d("10"));
        OfferLadder.Offer after = new OfferLadder(V140, d("0.10")).offer(cleanCard(), prices, d("10"));

        BigDecimal nine = rung(after, Grade.G9).price();
        assertThat(nine).isEqualByComparingTo("275.40");
        // Uncapped: 0.25 x 5 + 0.5 x 1 + 0.25 x 0.5 = 1.875 nine rungs (516.375).
        assertThat(rung(before, Grade.G10).price()).isEqualByComparingTo("1377.00");
        assertThat(before.price()).isBetween(d("516.37"), d("516.38"));
        assertThat(before.tenCap()).isNull();
        assertThat(before.trace().stepsFor("offer_ten_cap")).isEmpty();
        // Capped: 0.25 x 2.5 + 0.5 x 1 + 0.25 x 0.5 = 1.25 nine rungs.
        assertThat(rung(after, Grade.G10).price()).isEqualByComparingTo("688.50");
        assertThat(after.price()).isEqualByComparingTo("344.25");
        assertThat(after.price()).isEqualByComparingTo(nine.multiply(d("1.25")));
        assertThat(after.tenCap()).isEqualTo(new OfferLadder.TenCap(d("1377.00"), d("688.50"), d("2.5")));
        Trace.Step step = after.trace().stepsFor("offer_ten_cap").getFirst();
        assertThat(step.detail()).contains("M 2.5").contains("1377.00").contains("275.40");
        // The rest of the ladder is untouched.
        assertThat(rung(after, Grade.G8).price()).isEqualByComparingTo(rung(before, Grade.G8).price());
    }

    @Test
    void theCapDoesNotBindWhenTheTenIsWithinTwoAndAHalfNines() {
        // Exactly 2.5x the 9, then under it.
        for (String ten : new String[] {"1000", "900"}) {
            GradePriceTable prices = tenPremium("400", ten);
            OfferLadder.Offer before = new OfferLadder(V130, d("0.10")).offer(cleanCard(), prices, d("10"));
            OfferLadder.Offer after = new OfferLadder(V140, d("0.10")).offer(cleanCard(), prices, d("10"));

            assertThat(after.tenCap()).as(ten).isNull();
            assertThat(after.price()).as(ten).isEqualByComparingTo(before.price());
            assertThat(after.rungs()).as(ten).isEqualTo(before.rungs());
        }
    }

    @Test
    void aNineWithNoPriceLeavesTheTenAlone() {
        // A 9 that nets to nothing (grading costs more than it sells for) gives the 10 nothing to be measured against.
        GradePriceTable prices = tenPremium("0", "2000");

        OfferLadder.Offer after = new OfferLadder(V140, d("0.10")).offer(cleanCard(), prices, d("10"));

        assertThat(rung(after, Grade.G9).price()).isEqualByComparingTo("0");
        assertThat(rung(after, Grade.G10).price()).isEqualByComparingTo("1377.00");
        assertThat(after.tenCap()).isNull();
    }

    @Test
    void setsBefore140HaveNoCap() {
        assertThat(V130.offerTenToNineCap()).isEmpty();
        assertThat(ParameterSet.load("1.2.0").offerTenToNineCap()).isEmpty();
        assertThat(V140.offerTenToNineCap()).contains(d("2.5"));
    }

    @Test
    void theCapLeavesTheValuesAlone() {
        // FMV, RV, QSV and FLV keep the full ladder: same inputs, same values under 1.3.0 and 1.4.0.
        CardValuator.Input in = new CardValuator.Input("c", cleanCard(), tenPremium("400", "2000"), d("0.08"), d("10"), 1, Confidence.A);

        CardValuation before = new CardValuator(V130).value(in);
        CardValuation after = new CardValuator(V140).value(in);

        assertThat(after.fmv()).isEqualByComparingTo(before.fmv());
        assertThat(after.fmv()).isEqualByComparingTo("750"); // 0.25 x 2000 + 0.5 x 400 + 0.25 x 200, uncapped
        assertThat(after.rv()).isEqualByComparingTo(before.rv());
        assertThat(after.qsv()).isEqualByComparingTo(before.qsv());
        assertThat(after.stressedFmv()).isEqualByComparingTo(before.stressedFmv());
        assertThat(after.flv()).isEqualByComparingTo(before.flv());
    }

    private static OfferLadder.Rung rung(OfferLadder.Offer offer, Grade grade) {
        return offer.rungs().stream().filter(r -> r.grade() == grade).findFirst().orElseThrow();
    }
}

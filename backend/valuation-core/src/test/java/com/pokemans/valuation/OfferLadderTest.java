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
        assertThat(offer.expected()).isEqualByComparingTo("419.98");
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

    private static OfferLadder.Rung rung(OfferLadder.Offer offer, Grade grade) {
        return offer.rungs().stream().filter(r -> r.grade() == grade).findFirst().orElseThrow();
    }
}

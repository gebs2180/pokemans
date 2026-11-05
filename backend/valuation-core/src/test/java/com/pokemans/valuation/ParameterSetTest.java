package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterSetTest {

    /** One ladder for a card's weighted comps and a single grade's sales (review finding 58). */
    @Test
    void confidenceFromACountOfSalesClimbsAtEachThreshold() {
        ParameterSet p = ParameterSet.defaults();

        assertThat(p.fromCompCount(new BigDecimal("1.99"))).isEqualTo(Confidence.D);
        assertThat(p.fromCompCount(new BigDecimal("2"))).isEqualTo(Confidence.C);
        assertThat(p.fromCompCount(new BigDecimal("4.9"))).isEqualTo(Confidence.C);
        assertThat(p.fromCompCount(new BigDecimal("5"))).isEqualTo(Confidence.B);
        assertThat(p.fromCompCount(new BigDecimal("9.99"))).isEqualTo(Confidence.B);
        assertThat(p.fromCompCount(BigDecimal.TEN)).isEqualTo(Confidence.A);
    }

    @Test
    void loadsTheMethodologyDefaults() {
        ParameterSet p = ParameterSet.defaults();

        assertThat(p.version()).isEqualTo("1.0.0");
        assertThat(p.sellingFees()).isEqualByComparingTo("0.15");
        assertThat(p.buyerCosts()).isEqualByComparingTo("0.12");
        assertThat(p.stressK()).isEqualByComparingTo("2");
        assertThat(p.stressMonths()).isEqualByComparingTo("2");
        assertThat(p.stressShiftGrades()).isEqualTo(2);
        assertThat(p.stressShiftGradesConfidenceC()).isEqualTo(3);
        assertThat(p.halfLifeDays()).isEqualTo(30);
        assertThat(p.windowDays()).isEqualTo(90);
        assertThat(p.extendedWindowDays()).isEqualTo(180);
        assertThat(p.singleCardCap()).isEqualByComparingTo("0.20");
        assertThat(p.topFiveCap()).isEqualByComparingTo("0.50");
        assertThat(p.correlationThreshold()).isEqualByComparingTo("0.40");
    }

    @Test
    void liquidityTiersFollowTheMonthlySalesBoundaries() {
        ParameterSet p = ParameterSet.defaults();

        assertThat(p.liquidityTier(new BigDecimal("21"))).isEqualTo(ParameterSet.LiquidityTier.HIGH);
        assertThat(p.liquidityTier(new BigDecimal("20"))).isEqualTo(ParameterSet.LiquidityTier.MID);
        assertThat(p.liquidityTier(new BigDecimal("5"))).isEqualTo(ParameterSet.LiquidityTier.MID);
        assertThat(p.liquidityTier(new BigDecimal("4.9"))).isEqualTo(ParameterSet.LiquidityTier.LOW);
        assertThat(p.speedDiscount(ParameterSet.LiquidityTier.LOW)).isEqualByComparingTo("0.20");
        assertThat(p.searchPremium(ParameterSet.LiquidityTier.HIGH)).isEqualByComparingTo("0");
    }

    @Test
    void version110AddsTheFallbackSigma() {
        ParameterSet v110 = ParameterSet.load("1.1.0");

        assertThat(v110.fallbackSigma()).isEqualByComparingTo("0.20");
        assertThat(ParameterSet.defaults().fallbackSigma()).isNull();
        assertThat(v110.sellingFees()).isEqualByComparingTo(ParameterSet.defaults().sellingFees());
        assertThat(v110.topFiveCap()).isEqualByComparingTo(ParameterSet.defaults().topFiveCap());
    }

    @Test
    void version120AddsTheOfferMarginByConfidence() {
        ParameterSet v120 = ParameterSet.load("1.2.0");

        assertThat(v120.offerMargin(Confidence.A)).isEqualByComparingTo("0.10");
        assertThat(v120.offerMargin(Confidence.B)).isEqualByComparingTo("0.13");
        assertThat(v120.offerMargin(Confidence.C)).isEqualByComparingTo("0.17");
        assertThatThrownBy(() -> v120.offerMargin(Confidence.D)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParameterSet.load("1.1.0").offerMargin(Confidence.A)).isInstanceOf(IllegalStateException.class);
        // Everything else is 1.1.0's.
        assertThat(v120.fallbackSigma()).isEqualByComparingTo(ParameterSet.load("1.1.0").fallbackSigma());
        assertThat(v120.stressShiftGradesConfidenceC()).isEqualTo(ParameterSet.load("1.1.0").stressShiftGradesConfidenceC());
    }

    @Test
    void unknownVersionFails() {
        assertThatThrownBy(() -> ParameterSet.load("9.9.9")).isInstanceOf(IllegalArgumentException.class);
    }
}

package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterSetTest {

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
        assertThat(p.capsMinCards()).isEqualTo(10);
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
    void unknownVersionFails() {
        assertThatThrownBy(() -> ParameterSet.load("9.9.9")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void volatilityIsTheSampleStdDevOfMonthlyChanges() {
        // Changes: +10%, -10%, +10%, -10% → mean 0, sample variance 4 x 0.01 / 3.
        List<BigDecimal> prices = List.of(new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("99"),
                new BigDecimal("108.9"), new BigDecimal("98.01"));

        BigDecimal sigma = Volatility.monthly(prices);

        assertThat(sigma.doubleValue()).isCloseTo(Math.sqrt(0.04 / 3), org.assertj.core.data.Offset.offset(1e-12));
    }
}

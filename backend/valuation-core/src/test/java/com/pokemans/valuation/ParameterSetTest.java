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
    void version130AddsTheModalGradeAndThinGradeRules() throws Exception {
        ParameterSet v120 = ParameterSet.load("1.2.0");
        ParameterSet v130 = ParameterSet.load("1.3.0");

        assertThat(v130.usesModalGradeConfidence()).isTrue();
        assertThat(v130.thinGradesAreDerived()).isTrue();
        assertThat(v120.usesModalGradeConfidence()).isFalse();
        assertThat(v120.thinGradesAreDerived()).isFalse();
        assertThat(v120.modalGradeConfidence()).isNull();
        // Everything else is 1.2.0's.
        assertThat(new ParameterSet("1.2.0", v130.halfLifeDays(), v130.windowDays(), v130.extendedWindowDays(),
                v130.minSalesInWindow(), v130.outlierTrimMinSales(), v130.outlierTrimFraction(), v130.cardLadderMinGrades(),
                v130.cardLadderMinSpan(), v130.staleGraceDays(), v130.staleDaysPerLevel(), v130.highLiquidityAbove(),
                v130.lowLiquidityBelow(), v130.sellingFees(), v130.buyerCosts(), v130.searchPremiumHigh(),
                v130.searchPremiumMid(), v130.searchPremiumLow(), v130.speedDiscountHigh(), v130.speedDiscountMid(),
                v130.speedDiscountLow(), v130.stressK(), v130.stressMonths(), v130.stressShiftGrades(),
                v130.stressShiftGradesConfidenceC(), v130.depthDiscountFloor(), v130.depthDiscountCap(), v130.compsForA(),
                v130.compsForB(), v130.compsForC(), v130.minFmv(), v130.singleCardCap(), v130.topFiveCap(),
                v130.correlationThreshold(), v130.correlationHaircut(), v130.suggestedAlphaLow(), v130.suggestedAlphaHigh(),
                v130.gradeFirstMinLift(), v130.gradeFirstTopN(), v130.fallbackSigma(), v130.offerMarginA(),
                v130.offerMarginB(), v130.offerMarginC(), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)).isEqualTo(v120);

        java.util.Properties p = new java.util.Properties();
        try (var in = ParameterSet.class.getResourceAsStream("/parameter-sets/1.3.0.properties")) {
            p.load(in);
        }
        p.setProperty("confidence.modalGrade", "yes");
        assertThatThrownBy(() -> ParameterSet.fromProperties(p)).isInstanceOf(IllegalArgumentException.class);
        p.setProperty("confidence.modalGrade", "false");
        assertThat(ParameterSet.fromProperties(p).usesModalGradeConfidence()).isFalse();
    }

    @Test
    void version150AddsTheDayGuardsAndTheSigmaCap() {
        ParameterSet v140 = ParameterSet.load("1.4.0");
        ParameterSet v150 = ParameterSet.load("1.5.0");

        assertThat(v150.dayMaxSpread()).isEqualByComparingTo("3");
        assertThat(v150.dayMaxMultipleOfTier()).isEqualByComparingTo("3");
        assertThat(v150.dayGuardMinOtherDays()).isEqualTo(2);
        assertThat(v150.dayGuardNeighbourhoodDays()).isEqualTo(90);
        assertThat(v150.maxSigma()).isEqualByComparingTo("1.0");
        assertThat(v150.sigmaTooHigh(new BigDecimal("1.0"))).isFalse();
        assertThat(v150.sigmaTooHigh(new BigDecimal("1.01"))).isTrue();
        assertThat(v140.dayMaxSpread()).isNull();
        assertThat(v140.maxSigma()).isNull();
        assertThat(v140.sigmaTooHigh(new BigDecimal("50"))).isFalse();
        // Everything else is 1.4.0's.
        assertThat(v150.offerMaxTenToNineMultiple()).isEqualByComparingTo(v140.offerMaxTenToNineMultiple());
        assertThat(v150.cardLadderExcludesTen()).isEqualTo(v140.cardLadderExcludesTen());
        assertThat(v150.fallbackSigma()).isEqualByComparingTo(v140.fallbackSigma());
        assertThat(v150.offerMargin(Confidence.C)).isEqualByComparingTo(v140.offerMargin(Confidence.C));
    }

    @Test
    void version180AddsRawSaleFloors() {
        ParameterSet v170 = ParameterSet.load("1.7.0");
        ParameterSet v180 = ParameterSet.load("1.8.0");

        assertThat(v180.hasRawFloors()).isTrue();
        assertThat(v180.rawFloorIsDerived()).isTrue();
        assertThat(v180.rawFloorQuantile()).isEqualByComparingTo("0.25");
        assertThat(v180.rawFloorMinSaleDays()).isEqualTo(2);
        assertThat(v170.hasRawFloors()).isFalse();
        assertThat(v170.rawFloorNearMint()).isNull();
        // Everything else is 1.7.0's.
        assertThat(v180.pricesPokeTraceRevisions()).isEqualTo(v170.pricesPokeTraceRevisions());
        assertThat(v180.oneProviderMaxFmvForA()).isEqualByComparingTo(v170.oneProviderMaxFmvForA());
        assertThat(v180.offerMargin(Confidence.A)).isEqualByComparingTo(v170.offerMargin(Confidence.A));
        assertThat(v180.windowDays()).isEqualTo(v170.windowDays());
        assertThat(v180.halfLifeDays()).isEqualTo(v170.halfLifeDays());
    }

    @Test
    void version170PricesRevisedPokeTraceDays() {
        ParameterSet v160 = ParameterSet.load("1.6.0");
        ParameterSet v170 = ParameterSet.load("1.7.0");

        assertThat(v170.pricesPokeTraceRevisions()).isTrue();
        assertThat(v160.pricesPokeTraceRevisions()).isFalse();
        assertThat(v160.pokeTraceLatestRevision()).isNull();
        // Everything else is 1.6.0's.
        assertThat(v170.oneProviderMaxFmvForA()).isEqualByComparingTo(v160.oneProviderMaxFmvForA());
        assertThat(v170.dayMaxSpread()).isEqualByComparingTo(v160.dayMaxSpread());
        assertThat(v170.maxSigma()).isEqualByComparingTo(v160.maxSigma());
        assertThat(v170.offerMargin(Confidence.B)).isEqualByComparingTo(v160.offerMargin(Confidence.B));
        assertThat(v170.compsForA()).isEqualByComparingTo(v160.compsForA());
    }

    @Test
    void version160AddsTheOneProviderThresholdForA() {
        ParameterSet v150 = ParameterSet.load("1.5.0");
        ParameterSet v160 = ParameterSet.load("1.6.0");

        assertThat(v160.oneProviderMaxFmvForA()).isEqualByComparingTo("1000");
        assertThat(v160.oneProviderAboveA(new BigDecimal("1000"))).isFalse();
        assertThat(v160.oneProviderAboveA(new BigDecimal("1000.01"))).isTrue();
        // Before 1.6.0 there is no threshold: one provider is never A.
        assertThat(v150.oneProviderMaxFmvForA()).isNull();
        assertThat(v150.oneProviderAboveA(BigDecimal.ONE)).isTrue();
        // Everything else is 1.5.0's.
        assertThat(v160.maxSigma()).isEqualByComparingTo(v150.maxSigma());
        assertThat(v160.dayMaxSpread()).isEqualByComparingTo(v150.dayMaxSpread());
        assertThat(v160.offerMaxTenToNineMultiple()).isEqualByComparingTo(v150.offerMaxTenToNineMultiple());
        assertThat(v160.offerMargin(Confidence.A)).isEqualByComparingTo(v150.offerMargin(Confidence.A));
        assertThat(v160.compsForA()).isEqualByComparingTo(v150.compsForA());
    }

    @Test
    void unknownVersionFails() {
        assertThatThrownBy(() -> ParameterSet.load("9.9.9")).isInstanceOf(IllegalArgumentException.class);
    }
}

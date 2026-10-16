package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GradeDistributionTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void rejectsProbabilitiesThatDoNotSumToOne() {
        assertThatThrownBy(() -> GradeDistribution.of(Map.of(Grade.G8, d("0.5"), Grade.G9, d("0.4"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to 1");
    }

    @Test
    void rejectsNegativeProbabilities() {
        assertThatThrownBy(() -> GradeDistribution.of(Map.of(Grade.G8, d("1.1"), Grade.G9, d("-0.1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shiftDownFloorsAtOneAndHoldsUngradable() {
        GradeDistribution dist = GradeDistribution.of(Map.of(
                Grade.G2, d("0.3"), Grade.G3, d("0.3"), Grade.G9, d("0.3"), Grade.U, d("0.1")));

        GradeDistribution shifted = dist.shiftDown(3);

        assertThat(shifted.p(Grade.G1)).isEqualByComparingTo("0.6");
        assertThat(shifted.p(Grade.G6)).isEqualByComparingTo("0.3");
        assertThat(shifted.p(Grade.U)).isEqualByComparingTo("0.1");
        assertThat(shifted.p(Grade.G9)).isEqualByComparingTo("0");
    }

    @Test
    void shiftDownRejectsUpwardShifts() {
        assertThatThrownBy(() -> GradeDistribution.certain(Grade.G5).shiftDown(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void photoAndSurfacePenaltiesOnlyMoveProbabilityDown() {
        GradeDistribution dist = GradeDistribution.of(Map.of(
                Grade.G1, d("0.1"), Grade.G9, d("0.5"), Grade.G10, d("0.3"), Grade.U, d("0.1")));

        GradeDistribution adjusted = dist.applySurfacePenalty(d("0.2"));

        assertThat(adjusted.p(Grade.G10)).isEqualByComparingTo("0.24");
        assertThat(adjusted.p(Grade.G9)).isEqualByComparingTo("0.46");
        assertThat(adjusted.p(Grade.G8)).isEqualByComparingTo("0.10");
        assertThat(adjusted.p(Grade.G1)).isEqualByComparingTo("0.1");
        assertThat(adjusted.p(Grade.U)).isEqualByComparingTo("0.1");
        assertThat(dist.applyPhotoConfidencePenalty(d("0.2"))).isEqualTo(adjusted);
        assertThatThrownBy(() -> dist.applyPhotoConfidencePenalty(d("-0.1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void medianOrdersUngradableBelowOne() {
        GradeDistribution dist = GradeDistribution.of(Map.of(
                Grade.U, d("0.3"), Grade.G6, d("0.3"), Grade.G8, d("0.4")));

        assertThat(dist.median()).isEqualTo(Grade.G6);
    }
}

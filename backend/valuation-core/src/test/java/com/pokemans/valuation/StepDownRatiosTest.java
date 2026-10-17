package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StepDownRatiosTest {

    private static Map<Grade, BigDecimal> card(String g8, String g9) {
        return Map.of(Grade.G8, new BigDecimal(g8), Grade.G9, new BigDecimal(g9));
    }

    @Test
    void takesTheMedianAdjacentGradeRatioAcrossCards() {
        StepDownRatios.Table table = StepDownRatios.compute(List.of(card("50", "100"), card("60", "100"), card("90", "100")), 3);

        assertThat(table.ratios().get(Grade.G8)).isEqualByComparingTo("0.6");
        assertThat(table.cardCount().get(Grade.G8)).isEqualTo(3);
        assertThat(table.ratios()).containsOnlyKeys(Grade.G8);
    }

    @Test
    void takesTheLowerMiddleRatioForAnEvenCount() {
        StepDownRatios.Table table = StepDownRatios.compute(
                List.of(card("50", "100"), card("60", "100"), card("70", "100"), card("90", "100")), 1);

        assertThat(table.ratios().get(Grade.G8)).isEqualByComparingTo("0.6");
    }

    @Test
    void leavesOutRatiosWithTooFewCards() {
        StepDownRatios.Table table = StepDownRatios.compute(List.of(card("50", "100"), card("60", "100")), 3);

        assertThat(table.ratios()).isEmpty();
    }

    @Test
    void onlyCountsCardsWithBothGradesObserved() {
        StepDownRatios.Table table = StepDownRatios.compute(List.of(
                card("50", "100"), card("60", "100"), card("70", "100"),
                Map.of(Grade.G8, new BigDecimal("1"))), 3);

        assertThat(table.cardCount().get(Grade.G8)).isEqualTo(3);
        assertThat(table.ratios().get(Grade.G8)).isEqualByComparingTo("0.6");
    }

    @Test
    void neverStepsUp() {
        // The lower grade sold for more on most cards: capped at 1, not inverted.
        StepDownRatios.Table table = StepDownRatios.compute(List.of(card("120", "100"), card("130", "100"), card("110", "100")), 3);

        assertThat(table.ratios().get(Grade.G8)).isEqualByComparingTo("1");
    }
}

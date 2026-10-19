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

    @Test
    void gradeOneNeverFeedsTheTable() {
        // PSA 1 premiums: grade 1 sold above grade 2 on every card. No grade 1 ratio is built at all.
        List<Map<Grade, BigDecimal>> cards = List.of(
                Map.of(Grade.G1, new BigDecimal("150"), Grade.G2, new BigDecimal("100"), Grade.G3, new BigDecimal("125")),
                Map.of(Grade.G1, new BigDecimal("90"), Grade.G2, new BigDecimal("100"), Grade.G3, new BigDecimal("125")),
                Map.of(Grade.G1, new BigDecimal("200"), Grade.G2, new BigDecimal("100"), Grade.G3, new BigDecimal("125")));

        StepDownRatios.Table table = StepDownRatios.compute(cards, 3);

        assertThat(table.ratios()).containsOnlyKeys(Grade.G2);
        assertThat(table.ratios().get(Grade.G2)).isEqualByComparingTo("0.8");
        assertThat(table.cardCount()).doesNotContainKey(Grade.G1);
    }
}

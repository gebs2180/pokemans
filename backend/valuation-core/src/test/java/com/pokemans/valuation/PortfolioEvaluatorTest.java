package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PortfolioEvaluatorTest {

    private final PortfolioEvaluator evaluator = new PortfolioEvaluator(ParameterSet.defaults());

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static CardValuation valuation(String id, String fmv, String flv, Confidence confidence) {
        return new CardValuation(id, d(fmv), d(fmv), d(fmv), d(flv), d(flv), confidence, 2,
                ParameterSet.LiquidityTier.MID, d("0.05"), d("0.10"), BigDecimal.ONE, d("0.10"), new Trace());
    }

    private static PortfolioEvaluator.Card card(String id, String flv, String set, boolean graded) {
        return new PortfolioEvaluator.Card(valuation(id, "1000", flv, Confidence.B), set, "character-" + id, graded, false, true);
    }

    private static List<PortfolioEvaluator.Card> cards(int count, String flv, String set) {
        List<PortfolioEvaluator.Card> cards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cards.add(card(set + "-" + i, flv, set + "-" + i, false));
        }
        return cards;
    }

    private static BigDecimal contribution(PortfolioEvaluator.Result result, String id) {
        return result.cards().stream().filter(c -> c.cardId().equals(id)).findFirst().orElseThrow().contribution();
    }

    @Test
    void excludesIneligibleCardsWithReasons() {
        List<PortfolioEvaluator.Card> cards = new ArrayList<>(cards(10, "100", "ok"));
        cards.add(new PortfolioEvaluator.Card(valuation("d", "1000", "300", Confidence.D), "s", "c", false, false, true));
        cards.add(new PortfolioEvaluator.Card(valuation("auth", "1000", "300", Confidence.A), "s", "c", false, true, true));
        cards.add(new PortfolioEvaluator.Card(valuation("custody", "1000", "300", Confidence.A), "s", "c", false, false, false));
        cards.add(new PortfolioEvaluator.Card(valuation("cheap", "24.99", "10", Confidence.A), "s", "c", false, false, true));

        PortfolioEvaluator.Result result = evaluator.evaluate(cards, Optional.empty());

        assertThat(result.eligibleFlv()).isEqualByComparingTo("1000");
        assertThat(result.collateralBase()).isEqualByComparingTo("1000");
        assertThat(result.cards()).filteredOn(c -> !c.eligible())
                .extracting(c -> c.exclusionReasons().getFirst())
                .containsExactly("confidence D", "unresolved authenticity flag", "custody not verified", "FMV below 25");
        assertThat(result.advance()).isEmpty();
    }

    @Test
    void capsAreMeasuredAgainstTheEligibleFlvBeforeCaps() {
        // 2,500 beside ten 100s: 3,500 eligible, so no card above 700 and the top five at most 1,750.
        // The big card is capped at 700 (the top five are then 1,100). It is still 41% of the capped 1,700
        // in its own set and character, so both 10% haircuts apply: 700 x 0.9 x 0.9 = 567.
        List<PortfolioEvaluator.Card> cards = new ArrayList<>(cards(10, "100", "small"));
        cards.add(card("big", "2500", "big", false));

        PortfolioEvaluator.Result result = evaluator.evaluate(cards, Optional.empty());

        assertThat(result.trace().stepsFor("capped[big]").getFirst().value()).isEqualTo("700.00");
        assertThat(contribution(result, "big")).isEqualByComparingTo("567.00");
        assertThat(contribution(result, "small-0")).isEqualByComparingTo("100");
        assertThat(result.collateralBase()).isEqualByComparingTo("1567.00");
    }

    @Test
    void theSingleCardCapBinds() {
        // Nine 50s and a 550: 1,000 eligible, so the big card is capped at 200; the top five are then 400.
        List<PortfolioEvaluator.Card> cards = new ArrayList<>(cards(9, "50", "small"));
        cards.add(card("big", "550", "big", false));

        PortfolioEvaluator.Result result = evaluator.evaluate(cards, Optional.empty());

        assertThat(contribution(result, "big")).isEqualByComparingTo("200.00");
        assertThat(result.collateralBase()).isEqualByComparingTo("650.00");
    }

    @Test
    void theTopFiveCapCanBindBeforeTheSingleCardCap() {
        // Five 180s and ten 10s: 1,000 eligible. The single-card cap allows 200, but the top five may only
        // be 500, so every card is capped at 100.
        List<PortfolioEvaluator.Card> cards = new ArrayList<>(cards(5, "180", "top"));
        cards.addAll(cards(10, "10", "tail"));

        PortfolioEvaluator.Result result = evaluator.evaluate(cards, Optional.empty());

        assertThat(contribution(result, "top-0")).isEqualByComparingTo("100.00");
        assertThat(contribution(result, "tail-0")).isEqualByComparingTo("10");
        assertThat(result.collateralBase()).isEqualByComparingTo("600.00");
    }

    @Test
    void smallPledgesAreCappedToo() {
        // No minimum card count (Zach, 2026-10-19). One card counts at most 20% of its FLV, then it is the
        // whole base in its set and character, so both haircuts apply: 500 x 0.2 x 0.81 = 81.
        PortfolioEvaluator.Result one = evaluator.evaluate(List.of(card("only", "500", "only", false)), Optional.empty());
        assertThat(one.collateralBase()).isEqualByComparingTo("81.00");

        // Three 100s: 20% would allow 60 each, but the top five (all three) may only be 150, so 50 each.
        PortfolioEvaluator.Result three = evaluator.evaluate(cards(3, "100", "few"), Optional.empty());
        assertThat(three.collateralBase()).isEqualByComparingTo("150.00");
        assertThat(three.trace().stepsFor("concentration_caps").getFirst().value()).isEqualTo("50.00");
    }

    @Test
    void haircutsAConcentratedSetAndReportsRawShare() {
        List<PortfolioEvaluator.Card> cards = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cards.add(card("base-" + i, "100", "base-set", false));
        }
        for (int i = 0; i < 5; i++) {
            cards.add(card("other-" + i, "100", "set-" + i, i < 2));
        }

        PortfolioEvaluator.Result result = evaluator.evaluate(cards, Optional.of(d("0.60")));

        assertThat(contribution(result, "base-0")).isEqualByComparingTo("90");
        assertThat(contribution(result, "other-0")).isEqualByComparingTo("100");
        assertThat(result.collateralBase()).isEqualByComparingTo("950");
        assertThat(result.gradedShare()).isEqualByComparingTo(d("200").divide(d("950"), Money.MC));
        assertThat(result.rawShare().add(result.gradedShare())).isEqualByComparingTo("1");
        assertThat(result.suggestedAdvanceLow()).isEqualByComparingTo("475");
        assertThat(result.suggestedAdvanceHigh()).isEqualByComparingTo("665");
        assertThat(result.advance()).contains(d("570.00"));
        assertThat(result.trace().stepsFor("correlation[set:base-set]")).hasSize(1);
    }

    @Test
    void alphaIsTheLendersChoiceWithinZeroToOne() {
        assertThatThrownBy(() -> PortfolioEvaluator.advance(d("100"), d("1.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsADuplicateCardId() {
        List<PortfolioEvaluator.Card> cards = List.of(card("same", "100", "a", false), card("same", "200", "b", false));

        assertThatThrownBy(() -> evaluator.evaluate(cards, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate card id same");
    }

    @Test
    void theCapLevelIsInWholeCentsAndNeverOverTheLimits() {
        // Top five at 100.01 and four 100s with 99 cards of 5: the cap level lands on a cent, and the
        // capped top five never exceed half the base.
        List<PortfolioEvaluator.Card> cards = new ArrayList<>();
        cards.add(card("top-a", "100.01", "top-a", false));
        for (int i = 0; i < 4; i++) {
            cards.add(card("top-" + i, "100", "top-" + i, false));
        }
        for (int i = 0; i < 99; i++) {
            cards.add(card("tail-" + i, "5", "tail-" + i, false));
        }

        PortfolioEvaluator.Result result = evaluator.evaluate(cards, Optional.empty());

        BigDecimal topFive = contribution(result, "top-a");
        for (int i = 0; i < 4; i++) {
            topFive = topFive.add(contribution(result, "top-" + i));
        }
        assertThat(contribution(result, "top-a").scale()).isLessThanOrEqualTo(2);
        assertThat(topFive).isLessThanOrEqualTo(result.eligibleFlv().multiply(d("0.5")));
        assertThat(contribution(result, "top-a")).isLessThanOrEqualTo(result.eligibleFlv().multiply(d("0.2")));
        assertThat(result.trace().stepsFor("concentration_caps")).hasSize(1);
    }

    @Test
    void suggestsARangeWithAnEmptyBase() {
        PortfolioEvaluator.Result result = evaluator.evaluate(List.of(), Optional.of(d("0.5")));

        assertThat(result.collateralBase()).isEqualByComparingTo("0");
        assertThat(result.rawShare()).isEqualByComparingTo("0");
        assertThat(result.advance()).contains(d("0.00"));
    }

    @Test
    void alphaBelowZeroIsRefused() {
        assertThatThrownBy(() -> PortfolioEvaluator.advance(d("100"), d("-0.1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tiedCardsAreCappedAlike() {
        // Six equal cards and five small ones, 640 eligible: one level caps all six the same, 64 each, so the
        // top five are 320, half of 640, whichever five they are.
        List<PortfolioEvaluator.Card> cards = new ArrayList<>(cards(6, "100", "tie"));
        cards.addAll(cards(5, "8", "tail"));

        PortfolioEvaluator.Result result = evaluator.evaluate(cards, Optional.empty());

        for (int i = 0; i < 6; i++) {
            assertThat(contribution(result, "tie-" + i)).isEqualByComparingTo("64.00");
        }
        assertThat(contribution(result, "tail-0")).isEqualByComparingTo("8");
        assertThat(result.collateralBase()).isEqualByComparingTo("424.00");
    }
}

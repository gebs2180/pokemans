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
    void capsASingleCardAtTwentyPercentOfEligibleFlv() {
        List<PortfolioEvaluator.Card> cards = new ArrayList<>(cards(9, "50", "small"));
        cards.add(card("big", "550", "big", false));

        PortfolioEvaluator.Result result = evaluator.evaluate(cards, Optional.empty());

        assertThat(contribution(result, "big")).isEqualByComparingTo("200");
        assertThat(result.collateralBase()).isEqualByComparingTo("650");
    }

    @Test
    void capsTheTopFiveAtHalfOfEligibleFlv() {
        List<PortfolioEvaluator.Card> cards = new ArrayList<>(cards(5, "180", "top"));
        cards.addAll(cards(10, "10", "tail"));

        PortfolioEvaluator.Result result = evaluator.evaluate(cards, Optional.empty());

        assertThat(contribution(result, "top-0")).isEqualByComparingTo("100.00");
        assertThat(result.collateralBase()).isEqualByComparingTo("600");
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
}

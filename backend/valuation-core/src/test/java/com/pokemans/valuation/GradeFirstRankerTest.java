package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GradeFirstRankerTest {

    private final ParameterSet params = ParameterSet.defaults();
    private final CardValuator valuator = new CardValuator(params);
    private final GradeFirstRanker ranker = new GradeFirstRanker(params);
    private final GradingCost cost = new GradingCost(new BigDecimal("25"), new BigDecimal("5"), new BigDecimal("2"), BigDecimal.ZERO);

    /** Flat prices except the top grade, so the gain from grading is easy to see. */
    private static GradePriceTable prices(String topGradePrice) {
        Map<Grade, GradePrice> prices = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            String price = grade == Grade.G10 ? topGradePrice : "100";
            prices.put(grade, GradePrice.given(grade, new BigDecimal(price), GradePrice.Method.OBSERVED, 20));
        }
        return new GradePriceTable(prices, new Trace());
    }

    private GradeFirstRanker.Candidate candidate(String id, String topGradePrice) {
        CardValuator.Input input = new CardValuator.Input(id,
                GradeDistribution.of(Map.of(Grade.G10, new BigDecimal("0.6"), Grade.G9, new BigDecimal("0.4"))),
                prices(topGradePrice), new BigDecimal("0.05"), new BigDecimal("30"), 1, Confidence.A);
        return new GradeFirstRanker.Candidate(input, valuator.value(input), cost);
    }

    @Test
    void liftComparesGradedAndRawFlvPerDollarOfGradingCost() {
        GradeFirstRanker.Recommendation r = ranker.score(List.of(candidate("gem", "1000"))).getFirst();

        // Raw FLV: stress pushes everything to 100s: 100 x 0.85 x shock x 0.9.
        // Graded FLV: (0.6 x 1000 + 0.4 x 100) x 0.85 x shock x 0.9.
        BigDecimal shock = BigDecimal.ONE.subtract(new BigDecimal("0.1").multiply(new BigDecimal("2").sqrt(Money.MC)));
        BigDecimal factor = new BigDecimal("0.85").multiply(shock).multiply(new BigDecimal("0.9"));
        assertThat(r.flvRaw()).isEqualByComparingTo(Money.cents(new BigDecimal("100").multiply(factor)));
        assertThat(r.flvGraded()).isEqualByComparingTo(Money.cents(new BigDecimal("640").multiply(factor)));
        assertThat(r.cost()).isEqualByComparingTo("32");
        assertThat(r.expectedGrade()).isEqualTo(Grade.G10);
        assertThat(r.recommended()).isTrue();
    }

    @Test
    void recommendsOnlyLiftAboveOneSortedHighestFirst() {
        List<GradeFirstRanker.Recommendation> top = ranker.topRecommendations(List.of(
                candidate("flat", "100"), candidate("good", "500"), candidate("great", "2000")));

        assertThat(top).extracting(GradeFirstRanker.Recommendation::cardId).containsExactly("great", "good");
        assertThat(ranker.score(List.of(candidate("flat", "100"))).getFirst().lift()).isLessThan(BigDecimal.ONE);
    }

    @Test
    void liftOfExactlyOneIsNotRecommended() {
        GradeFirstRanker.Candidate probe = candidate("edge", "1000");
        BigDecimal flvGraded = ranker.score(List.of(probe)).getFirst().flvGraded();
        // FLV_raw chosen so that FLV_graded - FLV_raw - C = C exactly: lift 1.0.
        BigDecimal flvRaw = flvGraded.subtract(new BigDecimal("64"));
        CardValuation raw = probe.rawValuation();
        CardValuation atEdge = new CardValuation(raw.cardId(), raw.fmv(), raw.rv(), raw.qsv(), raw.stressedFmv(), flvRaw,
                raw.confidence(), raw.stressShift(), raw.liquidityTier(), raw.searchPremium(), raw.speedDiscount(),
                raw.volatilityShock(), raw.depthDiscount(), raw.trace());

        GradeFirstRanker.Recommendation r = ranker.score(List.of(new GradeFirstRanker.Candidate(probe.input(), atEdge, cost))).getFirst();

        assertThat(r.lift()).isEqualByComparingTo("1");
        assertThat(r.recommended()).isFalse();
    }

    @Test
    void showsOnlyTheTopTwentyInLiftOrder() {
        List<GradeFirstRanker.Candidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            candidates.add(candidate(String.format("card-%02d", i), Integer.toString(1000 + 100 * i)));
        }

        List<GradeFirstRanker.Recommendation> top = ranker.topRecommendations(candidates);

        assertThat(top).hasSize(20);
        assertThat(top.getFirst().cardId()).isEqualTo("card-24");
        assertThat(top.getLast().cardId()).isEqualTo("card-05");
        assertThat(top).extracting(GradeFirstRanker.Recommendation::lift).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void zeroCostGivesZeroLift() {
        GradingCost free = new GradingCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        GradeFirstRanker.Candidate c = candidate("free", "1000");

        GradeFirstRanker.Recommendation r = ranker.score(List.of(new GradeFirstRanker.Candidate(c.input(), c.rawValuation(), free))).getFirst();

        assertThat(r.lift()).isEqualByComparingTo("0");
        assertThat(r.recommended()).isFalse();
    }
}

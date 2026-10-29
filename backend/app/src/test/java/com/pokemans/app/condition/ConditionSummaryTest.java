package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConditionSummaryTest {

    private static GradeDistribution dist(Object... pairs) {
        Map<Grade, BigDecimal> map = new EnumMap<>(Grade.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Grade) pairs[i], new BigDecimal((String) pairs[i + 1]));
        }
        return GradeDistribution.of(map);
    }

    private static ProviderPrediction prediction(String provider, Double centering, Double corners, Double edges, Double surface) {
        PredictionRequest request = new PredictionRequest(UUID.randomUUID(), UUID.randomUUID(), provider, List.of(UUID.randomUUID()), null);
        return ProviderPrediction.succeeded(request,
                new PreGradeProvider.Reading(null, 8.5, null, centering, corners, edges, surface, Map.of()), Map.of(), 1);
    }

    @Test
    void likelyRangeIsTheFewestGradesHoldingEightyPercent() {
        Assessments.Summary s = Assessments.summarize(dist(Grade.G9, "0.10", Grade.G8, "0.50", Grade.G7, "0.25", Grade.G6, "0.15"), List.of());

        // 8 (50%) + 7 (25%) = 75%, so 6 or 9 is needed: 6 (15%) comes first.
        assertThat(s.low()).isEqualTo(6);
        assertThat(s.high()).isEqualTo(8);
        assertThat(s.mostLikely()).isEqualTo(8);
        assertThat(s.mayBeUngradable()).isFalse();
        assertThat(s.holdingBack()).isEmpty();
    }

    @Test
    void aChanceOfUngradableIsNotedAndDoesNotNarrowTheRange() {
        Assessments.Summary s = Assessments.summarize(dist(Grade.U, "0.30", Grade.G8, "0.40", Grade.G7, "0.20", Grade.G6, "0.10"), List.of());

        // 80% of the gradable 70% is 56%: 8 (40%) and 7 (20%).
        assertThat(s.low()).isEqualTo(7);
        assertThat(s.high()).isEqualTo(8);
        assertThat(s.mayBeUngradable()).isTrue();
    }

    @Test
    void holdingBackTakesEachPartsLowestScoreAcrossProvidersUpToTwo() {
        List<ProviderPrediction> latest = List.of(
                prediction("XIMILAR", 8.0, 9.0, 9.5, 7.0),
                prediction("CARDGRADE", 9.5, 8.5, 9.0, 9.0));

        Assessments.Summary s = Assessments.summarize(dist(Grade.G8, "1"), latest);

        // surface 7, centering 8, corners 8.5: the two lowest, lowest first.
        assertThat(s.holdingBack()).containsExactly("surface", "centering");
    }

    @Test
    void nothingHoldsItBackWhenEveryPartScoresAboveEightAndAHalf() {
        Assessments.Summary s = Assessments.summarize(dist(Grade.G9, "1"), List.of(prediction("XIMILAR", 9.0, 9.5, 9.0, null)));

        assertThat(s.holdingBack()).isEmpty();
        assertThat(s.low()).isEqualTo(9);
        assertThat(s.high()).isEqualTo(9);
    }
}

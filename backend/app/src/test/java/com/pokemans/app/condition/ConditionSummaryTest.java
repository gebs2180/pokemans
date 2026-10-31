package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Our own reading: a cap and no grade of its own, with whichever sides were measured. */
    private static ProviderPrediction measured(double cap, Map<String, Object> front, Map<String, Object> back) {
        PredictionRequest request = new PredictionRequest(UUID.randomUUID(), UUID.randomUUID(), CenteringPreGradeProvider.NAME,
                List.of(UUID.randomUUID()), null);
        return ProviderPrediction.succeeded(request, new PreGradeProvider.Reading(CenteringPreGradeProvider.VERSION, null, null, cap,
                null, null, null, Map.of("frontCentering", front, "backCentering", back, "psaCenteringCap", (int) cap)), Map.of(), 1);
    }

    private static final Map<String, Object> EVEN = Map.of("leftRight", "49.1/50.9", "topBottom", "48.4/51.6", "worstShare", 51.6);
    private static final Map<String, Object> UNMEASURED = Map.of("unmeasured", "no clear inner edge of the left border");

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
                prediction("XIMILAR", null, 9.0, 9.5, 7.0),
                prediction("CARDGRADE", 8.0, 8.5, 9.0, 9.0));

        Assessments.Summary s = Assessments.summarize(dist(Grade.G8, "1"), latest);

        // surface 7, centering 8, corners 8.5: the two lowest, lowest first.
        assertThat(s.holdingBack()).containsExactly("surface", "centering");
    }

    @Test
    void showsOurMeasurementRoundedAndNoSideThatWasNotMeasured() {
        Assessments.Summary s = Assessments.summarize(dist(Grade.G8, "1"), List.of(measured(10, EVEN, UNMEASURED)));

        assertThat(s.centering().front()).isEqualTo(new Assessments.SideCentering("49/51", "48/52"));
        assertThat(s.centering().back()).isNull();
    }

    @Test
    void neverShowsAnotherProvidersCentering() {
        PredictionRequest request = new PredictionRequest(UUID.randomUUID(), UUID.randomUUID(), "XIMILAR", List.of(UUID.randomUUID()), null);
        ProviderPrediction ximilar = ProviderPrediction.succeeded(request, new PreGradeProvider.Reading(null, 8.5, null, 9.0, 9.0, 9.0, 9.0,
                Map.of("frontCentering", Map.of("leftRight", "44/56", "topBottom", "52/48"))), Map.of(), 1);

        assertThat(Assessments.summarize(dist(Grade.G8, "1"), List.of(ximilar)).centering()).isNull();
    }

    @Test
    void noSideMeasuredShowsNothing() {
        assertThat(Assessments.summarize(dist(Grade.G8, "1"), List.of(measured(10, UNMEASURED, UNMEASURED))).centering()).isNull();
    }

    @Test
    void aMeasurementShowsBeforeThereIsAnAssessment() {
        Assessments assessments = assessmentsWith(measured(10, EVEN, EVEN));

        Assessments.Summary s = assessments.summary(UUID.randomUUID()).orElseThrow();

        assertThat(s.mostLikely()).isZero();
        assertThat(s.centering().back()).isEqualTo(new Assessments.SideCentering("49/51", "48/52"));
    }

    @Test
    void aLaterRefusalWithdrawsAnOlderMeasurement() {
        PredictionRequest request = new PredictionRequest(UUID.randomUUID(), UUID.randomUUID(), CenteringPreGradeProvider.NAME,
                List.of(UUID.randomUUID()), null);
        ProviderPrediction refused = ProviderPrediction.rejected(request, "Centering not measured", Map.of(), 1);

        // Newest first, as the repository returns them.
        assertThat(assessmentsWith(refused, measured(10, EVEN, EVEN)).summary(UUID.randomUUID())).isEmpty();
    }

    @Test
    void noMeasurementAndNoAssessmentIsNoSummary() {
        assertThat(assessmentsWith().summary(UUID.randomUUID())).isEmpty();
    }

    private static Assessments assessmentsWith(ProviderPrediction... newestFirst) {
        ConditionAssessmentRepository assessments = mock(ConditionAssessmentRepository.class);
        ProviderPredictionRepository predictions = mock(ProviderPredictionRepository.class);
        when(assessments.findByCardItemIdAndIsCurrentTrue(any())).thenReturn(Optional.empty());
        when(predictions.findByCardItemIdOrderByCreatedAtDesc(any())).thenReturn(List.of(newestFirst));
        return new Assessments(assessments, predictions);
    }

    @Test
    void aCapHoldsTheCardBackOnlyWhenItReachesTheLikelyRange() {
        ProviderPrediction cappedAtEight = measured(8, EVEN, UNMEASURED);

        // Capped at 8 and likely an 8: centering is what stops it.
        assertThat(Assessments.summarize(dist(Grade.G8, "1"), List.of(cappedAtEight)).holdingBack()).containsExactly("centering");
        // Likely a 5 for other reasons: a cap of 8 isn't holding it back.
        assertThat(Assessments.summarize(dist(Grade.G5, "1"), List.of(cappedAtEight)).holdingBack()).isEmpty();
    }

    @Test
    void aCapOfTenIsNotALowCenteringScore() {
        assertThat(Assessments.summarize(dist(Grade.G10, "1"), List.of(measured(10, EVEN, EVEN))).holdingBack()).isEmpty();
    }

    @Test
    void ourFrontMeasurementReplacesOtherProvidersCenteringScores() {
        List<ProviderPrediction> latest = List.of(prediction("CARDGRADE", 7.0, 9.0, 9.0, 9.0), measured(10, EVEN, EVEN));

        // CardGrade's 7 would say centering holds it back, beside ratios that say it doesn't.
        assertThat(Assessments.summarize(dist(Grade.G8, "1"), latest).holdingBack()).isEmpty();
    }

    @Test
    void withoutAFrontMeasurementOtherProvidersScoresStillCount() {
        List<ProviderPrediction> latest = List.of(prediction("CARDGRADE", 7.0, 9.0, 9.0, 9.0), measured(10, UNMEASURED, EVEN));

        assertThat(Assessments.summarize(dist(Grade.G8, "1"), latest).holdingBack()).containsExactly("centering");
    }

    @Test
    void ximilarsCenteringNeverHoldsTheCardBack() {
        List<ProviderPrediction> latest = List.of(prediction("XIMILAR", 7.0, 9.0, 9.0, 9.0), measured(10, UNMEASURED, EVEN));

        assertThat(Assessments.summarize(dist(Grade.G8, "1"), latest).holdingBack()).isEmpty();
        assertThat(Assessments.summarize(dist(Grade.G8, "1"), List.of(latest.getFirst())).holdingBack()).isEmpty();
    }

    @Test
    void nothingHoldsItBackWhenEveryPartScoresAboveEightAndAHalf() {
        Assessments.Summary s = Assessments.summarize(dist(Grade.G9, "1"), List.of(prediction("XIMILAR", 9.0, 9.5, 9.0, null)));

        assertThat(s.holdingBack()).isEmpty();
        assertThat(s.low()).isEqualTo(9);
        assertThat(s.high()).isEqualTo(9);
    }
}

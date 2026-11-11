package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DistributionBuilderTest {

    /** The defaults: P(10) at most 0.25, U at least 0.02. */
    static ConditionProperties defaults() {
        return properties(new BigDecimal("0.25"), new BigDecimal("0.02"));
    }

    static ConditionProperties properties(BigDecimal maxTenProbability, BigDecimal ungradableFloor) {
        return new ConditionProperties(null, null, 1.5, new BigDecimal("0.10"), new BigDecimal("0.25"), maxTenProbability, ungradableFloor,
                new ConditionProperties.Worker(false, Duration.ofSeconds(15), 8, Duration.ofHours(24), Duration.ofMinutes(10), 10,
                        Duration.ofDays(7), Duration.ofMinutes(2)),
                new ConditionProperties.CardGrade(false, null, null, "PSA", Duration.ofSeconds(30), 10, 50, Duration.ofHours(1)),
                new ConditionProperties.Centering(false, 1.0));
    }

    /**
     * Without the 10 cap and the U floor, so the spread and penalty tests read in round numbers. The
     * guards have their own tests below, with {@link #guarded}.
     */
    private final DistributionBuilder builder = new DistributionBuilder(properties(BigDecimal.ONE, BigDecimal.ZERO));
    private final DistributionBuilder guarded = new DistributionBuilder(defaults());

    private static BigDecimal sum(com.pokemans.valuation.GradeDistribution d) {
        return d.asMap().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, Double> grades(Object... pairs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return map;
    }

    @Test
    void twoAgreeingProvidersOnWholeCardPhotosAreConfidenceAWithoutPenalty() {
        DistributionBuilder.Result result = builder.build(new DistributionBuilder.Input(grades("A", 8.5, "B", 8.0), true, null));

        assertThat(result.confidence()).isEqualTo(Confidence.A);
        // Both fold to 8: the configured spread, unchanged.
        assertThat(result.distribution().p(Grade.G9)).isEqualByComparingTo("0.10");
        assertThat(result.distribution().p(Grade.G8)).isEqualByComparingTo("0.50");
        assertThat(result.distribution().p(Grade.G7)).isEqualByComparingTo("0.25");
        assertThat(result.distribution().p(Grade.G6)).isEqualByComparingTo("0.10");
        assertThat(result.distribution().p(Grade.G5)).isEqualByComparingTo("0.05");
        assertThat(result.distribution().p(Grade.U)).isZero();
        assertThat(result.distribution().median()).isEqualTo(Grade.G8);
        assertThat(result.basis()).containsEntry("foldedGrades", Map.of("A", 8, "B", 8));
    }

    @Test
    void halfGradesFoldDownNeverUp() {
        DistributionBuilder.Result result = builder.build(new DistributionBuilder.Input(grades("A", 9.9, "B", 9.5), true, null));

        assertThat(result.distribution().median()).isEqualTo(Grade.G9);
        assertThat(result.distribution().p(Grade.G10)).isEqualByComparingTo("0.10");
    }

    /** Zach, 2026-11-10: one provider is A and moves nothing down; the valuation caps it at B above $1,000 FMV. */
    @Test
    void oneProviderOnWholeCardPhotosIsConfidenceA() {
        // Zach, 2026-11-10: a CardGrade answer alone is A; the valuation caps it at B above the FMV threshold.
        DistributionBuilder.Result result = builder.build(new DistributionBuilder.Input(grades("A", 8.0), true, null));

        assertThat(result.confidence()).isEqualTo(Confidence.A);
        assertThat(result.distribution().asMap()).isEqualTo(builder.spread(8).asMap());
        assertThat(result.basis()).containsEntry("confidencePenalty", BigDecimal.ZERO);
    }

    @Test
    void providersMoreThanAGradeApartAreConfidenceBAndMoveSomeMassDown() {
        // 8 and 9.5 (folds to 9) are 1.5 apart: B, and B's penalty applies.
        DistributionBuilder.Result result = builder.build(new DistributionBuilder.Input(grades("A", 8.0, "B", 9.5), true, null));
        var unpenalised = com.pokemans.valuation.GradeDistribution.of(new java.util.EnumMap<>(Map.of(
                Grade.G10, new BigDecimal("0.05"), Grade.G9, new BigDecimal("0.30"), Grade.G8, new BigDecimal("0.375"),
                Grade.G7, new BigDecimal("0.175"), Grade.G6, new BigDecimal("0.075"), Grade.G5, new BigDecimal("0.025"))));

        assertThat(result.confidence()).isEqualTo(Confidence.B);
        assertThat(result.basis()).containsEntry("confidencePenalty", new BigDecimal("0.10"));
        assertThat(result.distribution()).isEqualTo(unpenalised.applyPhotoConfidencePenalty(new BigDecimal("0.10")));
    }

    /**
     * The worked check (Zach, 2026-11-10): CardGrade 9 overall, corners 9, edges 10, surface 10 (its
     * centering 10 is ignored), our centering allows 10. Tight spread 10 10%, 9 60%, 8 25%, 7 5%; the caps
     * do nothing; no one-provider penalty; the U floor scales the grades by 0.98.
     */
    @Test
    void cardGradeNineWithSubgradesAtOrAboveNineGetsTheTightSpread() {
        double minSub = DistributionBuilder.minSubgrade(9.0, 10.0, 10.0);
        var result = guarded.build(new DistributionBuilder.Input(grades("CARDGRADE", 9.0), true, 10, Map.of("CARDGRADE", minSub)));
        var d = result.distribution();

        assertThat(result.confidence()).isEqualTo(Confidence.A);
        assertThat(d.p(Grade.G10)).isEqualByComparingTo("0.098");
        assertThat(d.p(Grade.G9)).isEqualByComparingTo("0.588");
        assertThat(d.p(Grade.G8)).isEqualByComparingTo("0.245");
        assertThat(d.p(Grade.G7)).isEqualByComparingTo("0.049");
        assertThat(d.p(Grade.U)).isEqualByComparingTo("0.02");
        for (int g = 1; g <= 6; g++) {
            assertThat(d.p(Grade.of(g))).isZero();
        }
        assertThat(sum(d)).isEqualByComparingTo("1");
        assertThat(result.basis()).containsEntry("spreadUsed", Map.of("CARDGRADE", "tight"))
                .containsEntry("minSubgrades", Map.of("CARDGRADE", 9))
                .containsEntry("confidencePenalty", BigDecimal.ZERO);
    }

    @Test
    void aSubgradeBelowTheOverallKeepsTheDefaultSpread() {
        // Corners 8.5 folds to 8, below the overall 9.
        var result = builder.build(new DistributionBuilder.Input(grades("A", 9.0), true, null,
                Map.of("A", DistributionBuilder.minSubgrade(8.5, 10.0, 10.0))));

        assertThat(result.distribution().asMap()).isEqualTo(builder.spread(9).asMap());
        assertThat(result.basis()).containsEntry("spreadUsed", Map.of("A", "default"))
                .containsEntry("minSubgrades", Map.of("A", 8));
    }

    @Test
    void halfSubgradesFoldDownBeforeTheComparison() {
        // Overall 9.5 folds to 9 and surface 9.5 folds to 9: equal, so tight.
        var result = builder.build(new DistributionBuilder.Input(grades("A", 9.5), true, null,
                Map.of("A", DistributionBuilder.minSubgrade(10.0, 10.0, 9.5))));

        assertThat(result.basis()).containsEntry("spreadUsed", Map.of("A", "tight"));
        assertThat(result.distribution().p(Grade.G9)).isEqualByComparingTo("0.60");
        assertThat(result.distribution().p(Grade.G10)).isEqualByComparingTo("0.10");
    }

    @Test
    void missingSubgradesKeepTheDefaultSpread() {
        assertThat(DistributionBuilder.minSubgrade(10.0, null, 10.0)).isNull();
        var result = builder.build(new DistributionBuilder.Input(grades("A", 9.0), true, null));

        assertThat(result.distribution().asMap()).isEqualTo(builder.spread(9).asMap());
        assertThat(result.basis()).containsEntry("spreadUsed", Map.of("A", "default"));
    }

    @Test
    void theTightSpreadNeverPutsMoreAboveTheGradeThanTheDefault() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ConditionProperties(null,
                        Map.of(1, new BigDecimal("0.20"), 0, new BigDecimal("0.80")), 1.5, new BigDecimal("0.10"),
                        new BigDecimal("0.25"), BigDecimal.ONE, BigDecimal.ZERO, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ConditionProperties(null,
                        Map.of(1, new BigDecimal("0.10"), 0, new BigDecimal("0.80")), 1.5, new BigDecimal("0.10"),
                        new BigDecimal("0.25"), BigDecimal.ONE, BigDecimal.ZERO, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disagreeingProvidersAreConfidenceC() {
        DistributionBuilder.Result result = builder.build(new DistributionBuilder.Input(grades("A", 6.0, "B", 9.0), true, null));

        assertThat(result.confidence()).isEqualTo(Confidence.C);
        assertThat(result.basis()).containsEntry("providerGap", 3.0);
    }

    @Test
    void photosWithoutAWholeCardAreConfidenceC() {
        assertThat(builder.build(new DistributionBuilder.Input(grades("A", 8.0, "B", 8.0), false, null)).confidence())
                .isEqualTo(Confidence.C);
    }

    /** No default surface shift: surface counts through the providers' grades (review D5). */
    @Test
    void agreeingProvidersAtConfidenceAAreTheSpreadUnchanged() {
        var d = builder.build(new DistributionBuilder.Input(grades("A", 8.0, "B", 8.0), true, null));

        assertThat(d.confidence()).isEqualTo(Confidence.A);
        assertThat(d.distribution().asMap()).isEqualTo(builder.spread(8).asMap());
        assertThat(d.basis()).doesNotContainKeys("modernHolo", "holoSurfacePenalty");
    }

    @Test
    void lowGradesKeepTheirMassOnGradeOne() {
        var d = builder.build(new DistributionBuilder.Input(grades("A", 1.5, "B", 2.0), true, null)).distribution();

        assertThat(d.p(Grade.G1)).isEqualByComparingTo("0.65"); // 1's own 0.5 + all its below-1 mass, plus 2's -1..-3
        assertThat(d.asMap().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1");
    }

    @Test
    void noAnswersBuildNothing() {
        assertThat(builder.build(new DistributionBuilder.Input(Map.of(), true, null))).isNull();
        // A centering cap alone has nothing to cap.
        assertThat(builder.build(new DistributionBuilder.Input(Map.of(), true, 8))).isNull();
    }

    @Test
    void centeringCapsTheDistributionAndIsNotAveragedIn() {
        var uncapped = builder.build(new DistributionBuilder.Input(grades("A", 9.0, "B", 9.0), true, null));
        var capped = builder.build(new DistributionBuilder.Input(grades("A", 9.0, "B", 9.0), true, 8));

        // Nothing above 8; the 10 and 9 mass lands on 8. Confidence and the rest are unchanged.
        assertThat(capped.distribution().p(Grade.G10)).isZero();
        assertThat(capped.distribution().p(Grade.G9)).isZero();
        assertThat(capped.distribution().p(Grade.G8)).isEqualByComparingTo("0.85");
        assertThat(capped.distribution().p(Grade.G7)).isEqualByComparingTo(uncapped.distribution().p(Grade.G7));
        assertThat(capped.confidence()).isEqualTo(uncapped.confidence());
        assertThat(capped.basis()).containsEntry("centeringCap", 8);
    }

    @Test
    void aCenteringCapAboveTheGradesChangesNothing() {
        var uncapped = builder.build(new DistributionBuilder.Input(grades("A", 6.0, "B", 6.0), true, null));
        var capped = builder.build(new DistributionBuilder.Input(grades("A", 6.0, "B", 6.0), true, 10));

        assertThat(capped.distribution()).isEqualTo(uncapped.distribution());
    }

    /** Review B2: the spread's edge rule used to turn a provider 10 into a 60% chance of a PSA 10. */
    @Test
    void twoAgreeingProvidersAtTenGiveAtMostTheCapOnTen() {
        var unguarded = builder.build(new DistributionBuilder.Input(grades("A", 10.0, "B", 10.0), true, null));
        assertThat(unguarded.distribution().p(Grade.G10)).as("the old edge rule").isEqualByComparingTo("0.60");

        var d = guarded.build(new DistributionBuilder.Input(grades("A", 10.0, "B", 10.0), true, null));

        assertThat(d.confidence()).isEqualTo(Confidence.A);
        assertThat(d.distribution().p(Grade.G10)).isLessThanOrEqualTo(new BigDecimal("0.25"));
        // 0.25 on 10 and the other 0.35 on 9, beside 9's own 0.25; then scaled by 0.98 for the U floor.
        assertThat(d.distribution().p(Grade.G10)).isEqualByComparingTo("0.245");
        assertThat(d.distribution().p(Grade.G9)).isEqualByComparingTo("0.588");
        assertThat(d.distribution().p(Grade.G8)).isEqualByComparingTo("0.098");
        assertThat(sum(d.distribution())).isEqualByComparingTo("1");
        assertThat(d.basis()).containsEntry("maxTenProbability", new BigDecimal("0.25"))
                .containsEntry("ungradableFloor", new BigDecimal("0.02"));
    }

    @Test
    void theCapOnlyMovesMassDown() {
        // A 10 and a 9 average to 0.35 on 10: the cap takes 0.10 down to 9 and changes nothing below.
        var d = new DistributionBuilder(properties(new BigDecimal("0.25"), BigDecimal.ZERO))
                .build(new DistributionBuilder.Input(grades("A", 10.0, "B", 9.0), true, null)).distribution();
        var unguarded = builder.build(new DistributionBuilder.Input(grades("A", 10.0, "B", 9.0), true, null)).distribution();

        assertThat(unguarded.p(Grade.G10)).isEqualByComparingTo("0.35");
        assertThat(d.p(Grade.G10)).isEqualByComparingTo("0.25");
        assertThat(d.p(Grade.G9)).isEqualByComparingTo(unguarded.p(Grade.G9).add(new BigDecimal("0.10")));
        assertThat(d.p(Grade.G8)).isEqualByComparingTo(unguarded.p(Grade.G8));
    }

    @Test
    void aDistributionUnderTheCapIsUntouchedByIt() {
        var d = guarded.build(new DistributionBuilder.Input(grades("A", 8.0, "B", 8.0), true, null)).distribution();

        // Only the U floor: every grade scaled by 0.98.
        assertThat(d.p(Grade.G9)).isEqualByComparingTo("0.098");
        assertThat(d.p(Grade.G8)).isEqualByComparingTo("0.49");
    }

    @Test
    void systemDistributionsGetTheUngradableFloorTakenProportionally() {
        var d = guarded.build(new DistributionBuilder.Input(grades("A", 6.0), false, null)).distribution();

        assertThat(d.p(Grade.U)).isEqualByComparingTo("0.02");
        assertThat(sum(d)).isEqualByComparingTo("1");
        // Applied last, so the confidence penalty can't move it, and the grades keep their proportions.
        var unguarded = builder.build(new DistributionBuilder.Input(grades("A", 6.0), false, null)).distribution();
        for (int g = 1; g <= 10; g++) {
            assertThat(d.p(Grade.of(g))).isEqualByComparingTo(unguarded.p(Grade.of(g)).multiply(new BigDecimal("0.98")));
        }
    }

    @Test
    void aUAlreadyAboveTheFloorIsKept() {
        var withU = com.pokemans.valuation.GradeDistribution.of(Map.of(Grade.U, new BigDecimal("0.10"), Grade.G8, new BigDecimal("0.90")));

        assertThat(DistributionBuilder.floorUngradable(withU, new BigDecimal("0.02"))).isEqualTo(withU);
    }

    @Test
    void outOfRangeGuardsAreRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties(new BigDecimal("1.1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties(BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties(BigDecimal.ONE, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

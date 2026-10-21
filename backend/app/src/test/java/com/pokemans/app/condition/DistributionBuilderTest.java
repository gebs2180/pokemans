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

    static ConditionProperties defaults() {
        return new ConditionProperties(null, 1.5, new BigDecimal("0.10"), new BigDecimal("0.25"), new BigDecimal("0.25"),
                new ConditionProperties.Worker(false, Duration.ofSeconds(15), 8, Duration.ofHours(24), Duration.ofMinutes(10), 10,
                        Duration.ofDays(7), Duration.ofMinutes(2)),
                new ConditionProperties.Ximilar(false, null, null, Duration.ofSeconds(30)),
                new ConditionProperties.CardGrade(false, null, null, "PSA", Duration.ofSeconds(30)));
    }

    private final DistributionBuilder builder = new DistributionBuilder(defaults());

    private static Map<String, Double> grades(Object... pairs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return map;
    }

    @Test
    void twoAgreeingProvidersOnWholeCardPhotosAreConfidenceAWithoutPenalty() {
        DistributionBuilder.Result result = builder.build(new DistributionBuilder.Input(grades("A", 8.5, "B", 8.0), true, false));

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
        DistributionBuilder.Result result = builder.build(new DistributionBuilder.Input(grades("A", 9.9, "B", 9.5), true, false));

        assertThat(result.distribution().median()).isEqualTo(Grade.G9);
        assertThat(result.distribution().p(Grade.G10)).isEqualByComparingTo("0.10");
    }

    @Test
    void oneProviderIsConfidenceBAndMovesSomeMassDown() {
        DistributionBuilder.Result result = builder.build(new DistributionBuilder.Input(grades("A", 8.0), true, false));

        assertThat(result.confidence()).isEqualTo(Confidence.B);
        // 10% of each grade's mass moves one down: 8 keeps 0.45 and gains 0.01 from 9.
        assertThat(result.distribution().p(Grade.G8)).isEqualByComparingTo("0.46");
        assertThat(result.distribution().p(Grade.G9)).isEqualByComparingTo("0.09");
    }

    @Test
    void disagreeingProvidersAreConfidenceC() {
        DistributionBuilder.Result result = builder.build(new DistributionBuilder.Input(grades("A", 6.0, "B", 9.0), true, false));

        assertThat(result.confidence()).isEqualTo(Confidence.C);
        assertThat(result.basis()).containsEntry("providerGap", 3.0);
    }

    @Test
    void photosWithoutAWholeCardAreConfidenceC() {
        assertThat(builder.build(new DistributionBuilder.Input(grades("A", 8.0, "B", 8.0), false, false)).confidence())
                .isEqualTo(Confidence.C);
    }

    @Test
    void modernHolosGetTheSurfaceShiftDown() {
        var plain = builder.build(new DistributionBuilder.Input(grades("A", 8.0, "B", 8.0), true, false)).distribution();
        var holo = builder.build(new DistributionBuilder.Input(grades("A", 8.0, "B", 8.0), true, true)).distribution();

        assertThat(holo.p(Grade.G8)).isLessThan(plain.p(Grade.G8));
        assertThat(holo.p(Grade.G7)).isGreaterThan(plain.p(Grade.G7));
        assertThat(holo.p(Grade.G1).add(holo.p(Grade.G5))).isGreaterThan(plain.p(Grade.G1).add(plain.p(Grade.G5)));
    }

    @Test
    void lowGradesKeepTheirMassOnGradeOne() {
        var d = builder.build(new DistributionBuilder.Input(grades("A", 1.5, "B", 2.0), true, false)).distribution();

        assertThat(d.p(Grade.G1)).isEqualByComparingTo("0.65"); // 1's own 0.5 + all its below-1 mass, plus 2's -1..-3
        assertThat(d.asMap().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1");
    }

    @Test
    void noAnswersBuildNothing() {
        assertThat(builder.build(new DistributionBuilder.Input(Map.of(), true, false))).isNull();
    }
}

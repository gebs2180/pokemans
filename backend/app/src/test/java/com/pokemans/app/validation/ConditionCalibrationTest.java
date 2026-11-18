package com.pokemans.app.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.app.validation.ConditionCalibration.Observation;
import com.pokemans.app.validation.ConditionCalibration.Report;
import com.pokemans.app.validation.ConditionCalibration.Row;
import com.pokemans.valuation.Grade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConditionCalibrationTest {

    /** 50/50 at 9 and 10: median 9, stressed median 7 at confidence B, 6 at C. */
    private static Map<Grade, BigDecimal> nineOrTen() {
        Map<Grade, BigDecimal> d = new EnumMap<>(Grade.class);
        for (Grade g : Grade.values()) {
            d.put(g, BigDecimal.ZERO);
        }
        d.put(Grade.G9, new BigDecimal("0.5"));
        d.put(Grade.G10, new BigDecimal("0.5"));
        return d;
    }

    private static Observation observed(String grader, String certStatus, String grade, String confidence, String author, String spread) {
        return new Observation(UUID.randomUUID(), UUID.randomUUID(), "CONTRIBUTOR", grader, certStatus,
                grade == null ? null : new BigDecimal(grade), UUID.randomUUID(), 1, author, confidence, spread, nineOrTen());
    }

    @Test
    void aRowScoresTheReturnedGradeAgainstTheMedianAndTheStressedMedian() {
        Row eightAndAHalf = ConditionCalibration.score(observed("PSA", null, "8.5", "B", "SYSTEM", "default"));
        assertThat(eightAndAHalf.median()).isEqualTo("9");
        assertThat(eightAndAHalf.stressedMedian()).isEqualTo("7");
        assertThat(eightAndAHalf.returned()).isEqualTo("8");
        assertThat(eightAndAHalf.error()).isEqualTo(-1);
        assertThat(eightAndAHalf.withinOne()).isTrue();
        assertThat(eightAndAHalf.belowStressed()).isFalse();

        // At C the stress shift is 3, so the stressed median is 6 and a 6 isn't below it.
        Row sixAtC = ConditionCalibration.score(observed("PSA", null, "6", "C", "SYSTEM", "default"));
        assertThat(sixAtC.stressShift()).isEqualTo(3);
        assertThat(sixAtC.stressedMedian()).isEqualTo("6");
        assertThat(sixAtC.belowStressed()).isFalse();

        // A result with no number ("Authentic") is U, below everything.
        Row authentic = ConditionCalibration.score(observed("PSA", null, null, "B", "SYSTEM", "default"));
        assertThat(authentic.returned()).isEqualTo("U");
        assertThat(authentic.belowStressed()).isTrue();
    }

    @Test
    void onlyPsaMakesTheHeadlineAndAMismatchedCertIsLeftOut() {
        Report report = ConditionCalibration.report(List.of(
                observed("PSA", "VERIFIED", "9", "A", "SYSTEM", "tight"),
                observed("PSA", null, "5", "B", "REVIEWER", "none"),
                observed("PSA", "MISMATCH", "1", "B", "SYSTEM", "default"),
                observed("CGC", null, "10", "B", "SYSTEM", "default")), 2);

        assertThat(report.psa().n()).isEqualTo(2);
        assertThat(report.psa().withinOne()).isEqualTo(1);
        assertThat(report.psa().belowStressed()).isEqualTo(1);
        assertThat(report.psaVerified().n()).isEqualTo(1);
        assertThat(report.mismatched()).isEqualTo(1);
        assertThat(report.unpredicted()).isEqualTo(2);
        assertThat(report.otherGraders()).containsOnlyKeys("CGC");
        assertThat(report.byAuthor()).containsOnlyKeys("REVIEWER", "SYSTEM");
        assertThat(report.bySpread()).containsOnlyKeys("none", "tight");
        assertThat(report.rows()).hasSize(3);

        // Two PSA cards, each half 9 and half 10: one expected at each; one came back 9, one 5.
        assertThat(report.psaGrades()).filteredOn(c -> c.grade().equals("9"))
                .singleElement().satisfies(c -> {
                    assertThat(c.expected()).isEqualByComparingTo("1.00");
                    assertThat(c.observed()).isOne();
                });
        assertThat(report.psaGrades()).filteredOn(c -> c.grade().equals("10"))
                .singleElement().satisfies(c -> assertThat(c.observed()).isZero());
    }

    @Test
    void theTargetsAreMetOnlyWhenTheBoundsClearThem() {
        // 9 of 10 within one is 90%, but at n = 10 the lower bound is far under 80%.
        List<Row> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(ConditionCalibration.score(observed("PSA", null, i == 0 ? "6" : "9", "B", "SYSTEM", "default")));
        }
        ConditionCalibration.Rate small = ConditionCalibration.rate(ten);
        assertThat(small.withinOne()).isEqualTo(9);
        assertThat(small.meetsTargets()).isFalse();

        // 285 of 300 within one (95%) and none below the stressed median clears both bounds.
        List<Row> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            many.add(ConditionCalibration.score(observed("PSA", null, i < 15 ? "7" : "9", "B", "SYSTEM", "default")));
        }
        ConditionCalibration.Rate large = ConditionCalibration.rate(many);
        assertThat(large.belowStressed()).isZero();
        assertThat(large.withinOneLow()).isGreaterThan(new BigDecimal("0.80"));
        assertThat(large.meetsTargets()).isTrue();
        assertThat(ConditionCalibration.rate(List.of()).meanError()).isNull();
    }

    @Test
    void wilsonBoundsMatchTheTextbook() {
        double[] b = ConditionCalibration.wilson(850, 1000);
        assertThat(b[0]).isBetween(0.826, 0.828);
        assertThat(b[1]).isBetween(0.870, 0.872);
        assertThat(ConditionCalibration.wilson(0, 20)[0]).isZero();
    }
}

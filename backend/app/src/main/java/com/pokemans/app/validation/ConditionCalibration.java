package com.pokemans.app.validation;

import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The condition target in CLAUDE.md, measured on every returned grade we hold: the actual grade is within
 * ±1 of the predicted median at least 80% of the time, and below the stressed median under 5% of the time
 * (docs/validation-plan.md, check 1). The prediction is the card's assessment in force before it was
 * graded, so nothing a reviewer learned from the slab leaks in. Read-only: it changes no parameter.
 *
 * <p>The distribution is on PSA's scale, so only PSA results make the headline. Other graders are listed
 * apart, for information. A cert the admin found MISMATCHED is left out everywhere.
 */
final class ConditionCalibration {

    /** The targets, for the report to say pass or fail against (CLAUDE.md, validation targets). */
    static final double WITHIN_ONE_TARGET = 0.80;
    static final double BELOW_STRESSED_TARGET = 0.05;
    private static final double Z95 = 1.96;

    /**
     * One returned grade and the assessment that was in force before it.
     *
     * @param certStatus    VERIFIED, MISMATCH, or null while unchecked
     * @param returnedGrade as returned, half grades kept; null for a result without a number ("Authentic")
     * @param spread        the spread the system distribution was built with: tight, default, mixed, or none
     *                      for a reviewer's distribution or one from before the spread was recorded
     */
    record Observation(UUID outcomeId, UUID cardItemId, String source, String grader, String certStatus,
                       BigDecimal returnedGrade, UUID assessmentId, int assessmentVersion, String authorType,
                       String confidence, String spread, Map<Grade, BigDecimal> distribution) {
    }

    /**
     * One observation scored.
     *
     * @param error returned grade minus predicted median (U counts as 0): negative means the card came back worse
     */
    record Row(UUID outcomeId, UUID cardItemId, String source, String grader, String certStatus, UUID assessmentId,
               int assessmentVersion, String authorType, String confidence, String spread, String median, int stressShift,
               String stressedMedian, String returned, int error, boolean withinOne, boolean belowStressed) {
    }

    /**
     * Hit rates over a group of rows.
     *
     * @param meanError          mean of returned minus median; null with no rows
     * @param withinOneLow       95% Wilson lower bound on the within-one rate; null with no rows
     * @param belowStressedHigh  95% Wilson upper bound on the below-stressed rate; null with no rows
     * @param meetsTargets       true only when the bounds themselves clear both targets, so a small sample
     *                           can't pass on a lucky rate
     */
    record Rate(int n, int withinOne, int belowStressed, BigDecimal meanError, BigDecimal withinOneLow,
                BigDecimal belowStressedHigh, boolean meetsTargets) {
    }

    /**
     * Calibration by grade: how many cards the distributions expected at a grade (the sum of its probability)
     * against how many came back at it. More expected than observed at 10 means the distributions are too
     * generous at the top; fewer expected than observed at U means the ungradable floor is too low.
     */
    record GradeCount(String grade, BigDecimal expected, int observed) {
    }

    /**
     * @param psa          PSA results, unchecked certs included: the headline
     * @param psaVerified  PSA results whose cert the admin checked
     * @param byConfidence PSA results by the assessment's confidence
     * @param byAuthor     PSA results by who made the distribution: SYSTEM or REVIEWER
     * @param bySpread     PSA results by the spread a system distribution was built with
     * @param otherGraders other graders' results, for information only: the distribution is on PSA's scale
     * @param psaGrades    PSA results by grade, expected against observed
     * @param mismatched   results left out because their cert didn't match the card
     * @param unpredicted  results left out because the card had no assessment before it was graded
     */
    record Report(Rate psa, Rate psaVerified, Map<String, Rate> byConfidence, Map<String, Rate> byAuthor,
                  Map<String, Rate> bySpread, Map<String, Rate> otherGraders, List<GradeCount> psaGrades,
                  int mismatched, int unpredicted, List<Row> rows) {
    }

    private ConditionCalibration() {
    }

    static Report report(List<Observation> observations, int unpredicted) {
        List<Row> rows = new ArrayList<>();
        List<Observation> psaObservations = new ArrayList<>();
        int mismatched = 0;
        for (Observation o : observations) {
            if ("MISMATCH".equals(o.certStatus())) {
                mismatched++;
                continue;
            }
            rows.add(score(o));
            if ("PSA".equals(o.grader())) {
                psaObservations.add(o);
            }
        }
        List<Row> psa = rows.stream().filter(r -> "PSA".equals(r.grader())).toList();
        return new Report(rate(psa), rate(psa.stream().filter(r -> "VERIFIED".equals(r.certStatus())).toList()),
                groups(psa, Row::confidence), groups(psa, Row::authorType), groups(psa, Row::spread),
                groups(rows.stream().filter(Predicate.not(r -> "PSA".equals(r.grader()))).toList(), Row::grader),
                gradeCounts(psaObservations), mismatched, unpredicted, rows);
    }

    /** The stress shift the methodology applies: 3 grades at confidence C or worse, else 2. */
    static int stressShift(String confidence) {
        return "C".equals(confidence) || "D".equals(confidence) ? 3 : 2;
    }

    /** Half grades fold down; a result with no number is U. */
    static Grade returned(BigDecimal grade) {
        return grade == null ? Grade.U : Grade.foldHalf(grade);
    }

    static Row score(Observation o) {
        GradeDistribution d = GradeDistribution.of(o.distribution());
        Grade median = d.median();
        int shift = stressShift(o.confidence());
        Grade stressed = d.shiftDown(shift).median();
        Grade r = returned(o.returnedGrade());
        int error = r.number() - median.number();
        return new Row(o.outcomeId(), o.cardItemId(), o.source(), o.grader(), o.certStatus(), o.assessmentId(),
                o.assessmentVersion(), o.authorType(), o.confidence(), o.spread(), median.toString(), shift, stressed.toString(),
                r.toString(), error, Math.abs(error) <= 1, r.number() < stressed.number());
    }

    static Rate rate(List<Row> rows) {
        int n = rows.size();
        int within = (int) rows.stream().filter(Row::withinOne).count();
        int below = (int) rows.stream().filter(Row::belowStressed).count();
        if (n == 0) {
            return new Rate(0, 0, 0, null, null, null, false);
        }
        double meanError = rows.stream().mapToInt(Row::error).average().orElse(0);
        double withinLow = wilson(within, n)[0];
        double belowHigh = wilson(below, n)[1];
        return new Rate(n, within, below, round(meanError), round(withinLow), round(belowHigh),
                withinLow >= WITHIN_ONE_TARGET && belowHigh < BELOW_STRESSED_TARGET);
    }

    /** 95% Wilson score interval for k successes in n: honest at small n and near 0 or 1. */
    static double[] wilson(int k, int n) {
        double p = (double) k / n;
        double z2 = Z95 * Z95;
        double centre = p + z2 / (2 * n);
        double half = Z95 * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n));
        double denominator = 1 + z2 / n;
        return new double[] {Math.max(0, (centre - half) / denominator), Math.min(1, (centre + half) / denominator)};
    }

    private static Map<String, Rate> groups(List<Row> rows, Function<Row, String> key) {
        Map<String, List<Row>> grouped = rows.stream()
                .collect(Collectors.groupingBy(r -> String.valueOf(key.apply(r)), java.util.TreeMap::new, Collectors.toList()));
        Map<String, Rate> out = new LinkedHashMap<>();
        grouped.forEach((k, v) -> out.put(k, rate(v)));
        return out;
    }

    private static List<GradeCount> gradeCounts(List<Observation> observations) {
        Map<Grade, BigDecimal> expected = new EnumMap<>(Grade.class);
        Map<Grade, Integer> observed = new EnumMap<>(Grade.class);
        for (Grade g : Grade.values()) {
            expected.put(g, BigDecimal.ZERO);
            observed.put(g, 0);
        }
        for (Observation o : observations) {
            o.distribution().forEach((g, p) -> expected.merge(g, p, BigDecimal::add));
            observed.merge(returned(o.returnedGrade()), 1, Integer::sum);
        }
        List<GradeCount> counts = new ArrayList<>();
        for (Grade g : Grade.values()) {
            counts.add(new GradeCount(g.toString(), expected.get(g).setScale(2, RoundingMode.HALF_UP), observed.get(g)));
        }
        return counts;
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }
}

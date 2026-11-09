package com.pokemans.app.offers;

import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An offer's predicted condition against the grade the card came back with: the validation targets in
 * CLAUDE.md (actual grade within ±1 of the predicted median at least 80% of the time; below the
 * stressed median under 5%).
 */
final class Calibration {

    private static final Pattern SHIFT = Pattern.compile("shifted (\\d+) grades? down");

    /**
     * What the offer predicted, from the distribution it was priced on.
     *
     * @param distribution U, 1 … 10, every grade listed
     * @param stressShift  how many grades the FLV stress shifted the distribution down
     */
    record Prediction(Map<String, BigDecimal> distribution, Grade median, int stressShift, Grade stressedMedian) {
    }

    /** How a returned grade compares with the prediction. */
    record Comparison(Grade returned, boolean withinOne, boolean belowStressed) {
    }

    private Calibration() {
    }

    /**
     * @param trace      the offer's stored trace, which names the stress shift it used
     * @param confidence the offer's confidence, for an offer whose trace doesn't name the shift
     */
    static Prediction predict(Map<Grade, BigDecimal> distribution, String confidence, String trace) {
        GradeDistribution d = GradeDistribution.of(distribution);
        int shift = stressShift(trace, confidence);
        Map<String, BigDecimal> ordered = new LinkedHashMap<>();
        for (Grade g : Grade.values()) {
            ordered.put(g.toString(), distribution.getOrDefault(g, BigDecimal.ZERO));
        }
        return new Prediction(ordered, d.median(), shift, d.shiftDown(shift).median());
    }

    /** The shift the offer's stress step names; otherwise the methodology's: 3 grades at confidence C or worse, else 2. */
    static int stressShift(String trace, String confidence) {
        if (trace != null) {
            Matcher m = SHIFT.matcher(trace);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return "C".equals(confidence) || "D".equals(confidence) ? 3 : 2;
    }

    /**
     * The returned grade as the methodology prices it: half grades fold down, and a result with no
     * number ("Authentic", "Altered") is U.
     */
    static Grade returned(BigDecimal grade) {
        return grade == null ? Grade.U : Grade.foldHalf(grade);
    }

    static Comparison compare(Prediction p, BigDecimal returnedGrade) {
        Grade r = returned(returnedGrade);
        return new Comparison(r, Math.abs(r.number() - p.median().number()) <= 1, r.number() < p.stressedMedian().number());
    }
}

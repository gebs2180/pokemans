package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Step 6: which raw cards to grade before borrowing.
 *
 * <pre>
 * Lift = (FLV_graded − FLV_raw − C) / C
 * </pre>
 *
 * FLV_graded is the expected FLV once the grade is known: Σ p_g · FLV(known grade g), with no
 * condition stress and gross graded prices. C is fee + shipping + insurance + waiting cost on the
 * expected graded price. FLV_raw here is the card's raw FLV computed from the same gross prices, so
 * the grading cost is counted exactly once, in C (Zach, 2026-10-18). The FLV the collateral base
 * uses is built from net prices and is lower; it is not the one compared here.
 */
public final class GradeFirstRanker {

    private final ParameterSet params;
    private final CardValuator valuator;

    public GradeFirstRanker(ParameterSet params) {
        this.params = Objects.requireNonNull(params);
        this.valuator = new CardValuator(params);
    }

    public record Candidate(CardValuator.Input input, GradingCost gradingCost) {
    }

    public record Recommendation(
            String cardId,
            Grade expectedGrade,
            BigDecimal cost,
            BigDecimal flvRaw,
            BigDecimal flvGraded,
            BigDecimal lift,
            boolean recommended,
            Trace trace) {
    }

    /** Every candidate scored, sorted by lift (highest first). */
    public List<Recommendation> score(List<Candidate> candidates) {
        List<Recommendation> all = new ArrayList<>();
        for (Candidate c : candidates) {
            all.add(score(c));
        }
        all.sort(Comparator.comparing(Recommendation::lift).reversed().thenComparing(Recommendation::cardId));
        return all;
    }

    /** The top N with lift above the threshold, as shown in the report. */
    public List<Recommendation> topRecommendations(List<Candidate> candidates) {
        return score(candidates).stream()
                .filter(Recommendation::recommended)
                .limit(params.gradeFirstTopN())
                .toList();
    }

    Recommendation score(Candidate c) {
        Trace trace = new Trace();
        GradeDistribution dist = c.input().distribution();
        BigDecimal flvGraded = BigDecimal.ZERO;
        BigDecimal expectedGross = BigDecimal.ZERO;
        for (Grade grade : Grade.values()) {
            BigDecimal p = dist.p(grade);
            if (p.signum() == 0) {
                continue;
            }
            flvGraded = flvGraded.add(p.multiply(valuator.flvAtKnownGrade(c.input(), grade), Money.MC));
            expectedGross = expectedGross.add(p.multiply(c.input().prices().gross(grade), Money.MC));
        }
        flvGraded = Money.cents(flvGraded);
        trace.add("FLV_graded", "sum p x FLV at known grade (no condition stress)", flvGraded, "");

        BigDecimal cost = Money.cents(c.gradingCost().total(expectedGross));
        trace.add("grading_cost", "fee + shipping + insurance + waiting", cost,
                "fixed " + c.gradingCost().fixed() + ", waiting on expected gross " + Money.cents(expectedGross));

        BigDecimal flvRaw = valuator.value(atGross(c.input())).flv();
        BigDecimal lift = cost.signum() == 0
                ? BigDecimal.ZERO
                : flvGraded.subtract(flvRaw).subtract(cost).divide(cost, Money.MC).setScale(4, java.math.RoundingMode.HALF_EVEN);
        boolean recommended = lift.compareTo(params.gradeFirstMinLift()) > 0;
        trace.add("lift", "(FLV_graded - FLV_raw - C) / C", lift,
                "FLV_raw at gross prices " + flvRaw + (recommended ? ", above " : ", not above ") + params.gradeFirstMinLift());
        return new Recommendation(c.input().cardId(), dist.median(), cost, flvRaw, flvGraded, lift, recommended, trace);
    }

    /** The same input with every grade's net price set to its gross price. */
    private static CardValuator.Input atGross(CardValuator.Input in) {
        return new CardValuator.Input(in.cardId(), in.distribution(), in.prices().atGross(),
                in.monthlyVolatility(), in.salesPerMonth(), in.copiesHeld(), in.photoConfidence(), in.penalties(), in.knownGrade());
    }
}

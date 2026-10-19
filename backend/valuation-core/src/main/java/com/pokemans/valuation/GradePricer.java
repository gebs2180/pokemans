package com.pokemans.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Step 3: per-grade prices from sold comps.
 *
 * <ul>
 *   <li>Recency-weighted median (half-life from the parameter set) over the window; the window
 *       extends when a grade has too few sales.
 *   <li>Outliers trimmed from both ends once a grade has enough sales.
 *   <li>Missing grades: log interpolation between observed grades on both sides; the era step-down
 *       ratio from the nearest observed grade above; or, with only lower grades observed, the lower
 *       grade's price unchanged. Never stepped up.
 *   <li>Grade 1 is priced from its own sales only and never anchors another grade: PSA 1s have
 *       their own collectors and often sell above grades 2 to 4, so using them would step those up.
 *       For the same reason there is no era 1-to-2 ratio: a grade 1 with no sales of its own steps
 *       down from grade 2 with the era's 2-to-3 ratio, else the card's own ladder.
 *   <li>Below the lowest observed grade with no era ratio yet: the card's own ladder. With sales in
 *       enough grades across enough of a spread, the steepest per-grade ratio between its observed
 *       grades steps the lower grades down. Otherwise the card is refused rather than guessed.
 *   <li>U from damaged/altered raw sales, else zero. There is no bulk floor: low-value cards are
 *       kept out by the portfolio's FMV minimum, not propped up here.
 *   <li>No graded sales in any grade: refused, so the caller leaves the card unpriced.
 *   <li>Net of grading costs, unless the raw-sale price for that condition is higher.
 *   <li>Per-grade confidence: observed prices from their comp count, derived prices one level below
 *       the grades they came from (so a step-down chain drops once per step), U with no sales D.
 * </ul>
 */
public final class GradePricer {

    private final ParameterSet params;

    public GradePricer(ParameterSet params) {
        this.params = Objects.requireNonNull(params);
    }

    /**
     * @param asOf               valuation date; comps after it are ignored
     * @param gradedComps        sold comps per gradable grade (half grades already folded)
     * @param damagedRawComps    raw sales of damaged or altered copies, used for U
     * @param stepDownRatios     for grade g (2 to 9), the era median ratio of price(g) to price(g + 1);
     *                           a grade 1 entry is ignored, and grade 1 uses grade 2's
     * @param gradingCost        cost of grading, netted from graded prices
     * @param rawConditionPrices optional raw-sale price for a card in the condition of each grade
     */
    public record Input(
            LocalDate asOf,
            Map<Grade, List<Comp>> gradedComps,
            List<Comp> damagedRawComps,
            Map<Grade, BigDecimal> stepDownRatios,
            GradingCost gradingCost,
            Map<Grade, BigDecimal> rawConditionPrices) {
        public Input {
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(gradedComps, "gradedComps (pass an empty map, not null)");
            Objects.requireNonNull(damagedRawComps, "damagedRawComps (pass an empty list, not null)");
            Objects.requireNonNull(stepDownRatios, "stepDownRatios (pass an empty map, not null)");
            Objects.requireNonNull(gradingCost, "gradingCost");
            Objects.requireNonNull(rawConditionPrices, "rawConditionPrices (pass an empty map, not null)");
        }
    }

    public GradePriceTable price(Input input) {
        Trace trace = new Trace();
        EnumMap<Grade, BigDecimal> gross = new EnumMap<>(Grade.class);
        EnumMap<Grade, GradePrice.Method> method = new EnumMap<>(Grade.class);
        EnumMap<Grade, Observed> observedComps = new EnumMap<>(Grade.class);
        EnumMap<Grade, List<Grade>> basis = new EnumMap<>(Grade.class);
        EnumMap<Grade, BigDecimal> ratioUsed = new EnumMap<>(Grade.class);

        for (int g = 1; g <= 10; g++) {
            Grade grade = Grade.of(g);
            List<Comp> comps = input.gradedComps().getOrDefault(grade, List.of());
            Observed observed = observe("price[" + grade + "]", comps, input.asOf(), trace);
            if (observed != null) {
                gross.put(grade, observed.price());
                method.put(grade, GradePrice.Method.OBSERVED);
                observedComps.put(grade, observed);
            }
        }

        fillMissingGrades(input, gross, method, basis, ratioUsed, trace);

        Observed damaged = observe("price[U]", input.damagedRawComps(), input.asOf(), trace);
        if (damaged != null) {
            gross.put(Grade.U, damaged.price());
            method.put(Grade.U, GradePrice.Method.OBSERVED);
            observedComps.put(Grade.U, damaged);
        } else {
            gross.put(Grade.U, BigDecimal.ZERO);
            method.put(Grade.U, GradePrice.Method.NO_SALES);
            trace.add("price[U]", "no sales, valued at zero", BigDecimal.ZERO, "no damaged/altered raw sales in window");
        }

        EnumMap<Grade, Confidence> confidence = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            confidenceOf(grade, method, observedComps, basis, confidence);
        }

        EnumMap<Grade, GradePrice> prices = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            Observed observed = observedComps.get(grade);
            List<Comp> comps = observed == null ? List.of() : observed.comps();
            List<String> ids = comps.stream().map(Comp::id).toList();
            int window = observed == null ? 0 : observed.windowDays();
            int inWindow = observed == null ? 0 : observed.salesInWindow();
            List<Grade> from = basis.getOrDefault(grade, List.of());
            BigDecimal g = gross.get(grade);
            if (grade.isUngradable()) {
                // U is already a raw sale price: no grading costs to net.
                prices.put(grade, new GradePrice(grade, g, g, method.get(grade), comps.size(), ids, false, window, inWindow, from,
                        confidence.get(grade), null));
                continue;
            }
            BigDecimal netGraded = input.gradingCost().netPrice(g);
            BigDecimal raw = input.rawConditionPrices().get(grade);
            boolean rawWins = raw != null && raw.compareTo(netGraded) > 0;
            BigDecimal net = rawWins ? raw : netGraded;
            trace.add("net[" + grade + "]", rawWins ? "raw-sale price higher than net graded" : "net of grading cost", net,
                    "gross " + g + " - fixed " + input.gradingCost().fixed() + " - time-to-cash "
                            + Money.cents(input.gradingCost().waitingCost(g)) + (raw == null ? "" : "; raw " + raw));
            prices.put(grade, new GradePrice(grade, g, net, method.get(grade), comps.size(), ids, rawWins, window, inWindow, from,
                    confidence.get(grade), ratioUsed.get(grade)));
        }
        return new GradePriceTable(prices, trace);
    }

    /**
     * Observed (recency-weighted median) price per gradable grade, for grades with sales in the
     * window. Feeds the era step-down ratio table ({@link StepDownRatios}); no missing-grade rule.
     */
    public Map<Grade, BigDecimal> observedPrices(LocalDate asOf, Map<Grade, List<Comp>> gradedComps) {
        Trace ignored = new Trace();
        EnumMap<Grade, BigDecimal> observed = new EnumMap<>(Grade.class);
        for (int g = 1; g <= 10; g++) {
            Grade grade = Grade.of(g);
            Observed o = observe("price[" + grade + "]", gradedComps.getOrDefault(grade, List.of()), asOf, ignored);
            if (o != null) {
                observed.put(grade, o.price());
            }
        }
        return observed;
    }

    /** Confidence for one grade's price, from comp count, basis or floor (memoised in {@code out}). */
    private Confidence confidenceOf(Grade grade, Map<Grade, GradePrice.Method> method, Map<Grade, Observed> observed,
                                    Map<Grade, List<Grade>> basis, Map<Grade, Confidence> out) {
        Confidence known = out.get(grade);
        if (known != null) {
            return known;
        }
        Confidence c = switch (method.get(grade)) {
            case OBSERVED -> fromCompCount(observed.get(grade).salesInWindow());
            case NO_SALES -> Confidence.D;
            case INTERPOLATED, STEP_DOWN, CARD_STEP_DOWN, CARRIED_DOWN -> {
                Confidence worst = Confidence.A;
                for (Grade from : basis.get(grade)) {
                    worst = worst.worse(confidenceOf(from, method, observed, basis, out));
                }
                yield worst.drop();
            }
        };
        out.put(grade, c);
        return c;
    }

    private Confidence fromCompCount(int n) {
        BigDecimal count = BigDecimal.valueOf(n);
        if (count.compareTo(params.compsForA()) >= 0) {
            return Confidence.A;
        }
        if (count.compareTo(params.compsForB()) >= 0) {
            return Confidence.B;
        }
        if (count.compareTo(params.compsForC()) >= 0) {
            return Confidence.C;
        }
        return Confidence.D;
    }

    private void fillMissingGrades(Input input, EnumMap<Grade, BigDecimal> gross, EnumMap<Grade, GradePrice.Method> method,
                                   EnumMap<Grade, List<Grade>> basis, EnumMap<Grade, BigDecimal> ratioUsed, Trace trace) {
        // Observed grades only: derived prices never anchor other derived prices except step-down chains.
        EnumMap<Grade, BigDecimal> observed = new EnumMap<>(gross);
        if (observed.isEmpty()) {
            throw new IllegalArgumentException("No graded sales for any grade");
        }
        // Grade 1 sales price grade 1 only: a PSA 1 premium must not lift grades 2 to 4.
        EnumMap<Grade, BigDecimal> anchors = new EnumMap<>(observed);
        anchors.remove(Grade.G1);
        if (anchors.isEmpty()) {
            throw new IllegalArgumentException("Only grade 1 sales: grade 1 prices can't price the other grades");
        }
        BigDecimal cardRatio = cardLadderRatio(anchors);
        // Walk downward so step-down chains use the already-derived grade above.
        for (int g = 10; g >= 1; g--) {
            Grade grade = Grade.of(g);
            if (observed.containsKey(grade)) {
                continue;
            }
            Grade below = nearestObserved(anchors, g - 1, -1);
            Grade above = nearestObserved(anchors, g + 1, +1);
            String subject = "price[" + grade + "]";
            if (below != null && above != null) {
                BigDecimal interpolated = logInterpolate(below, anchors.get(below), above, anchors.get(above), grade);
                // Out-of-order anchors (the lower grade sold for more) would put this grade above the grade
                // over it. Never step up: cap at the higher grade's price.
                BigDecimal price = interpolated.min(anchors.get(above));
                gross.put(grade, price);
                method.put(grade, GradePrice.Method.INTERPOLATED);
                basis.put(grade, List.of(below, above));
                trace.add(subject, "log interpolation", price, "between grade " + below + " (" + anchors.get(below)
                        + ") and grade " + above + " (" + anchors.get(above) + ")"
                        + (price.compareTo(interpolated) < 0 ? "; capped at grade " + above + "'s price" : ""));
            } else if (above != null) {
                Grade next = Grade.of(g + 1);
                // No era 1-to-2 ratio, even if the caller passes one: grade 1 prices are their own market
                // (PSA 1 collectors), so it would carry other cards' grade 1 premiums into this card.
                // StepDownRatios never builds one. Grade 1 reuses the era's 2-to-3 ratio instead, built
                // from grades 2 and 3 only; ladders flatten at the bottom, so that errs low.
                BigDecimal ratio = input.stepDownRatios().get(grade == Grade.G1 ? Grade.G2 : grade);
                GradePrice.Method stepMethod = GradePrice.Method.STEP_DOWN;
                if (ratio == null) {
                    if (cardRatio == null) {
                        throw new IllegalArgumentException("No era step-down ratio for grade " + grade + " to " + next
                                + (grade == Grade.G1 ? " (grade 1 borrows the era's 2-to-3 ratio: there is none)" : "")
                                + ", and sales in too few grades to step down from the card's own prices (need "
                                + params.cardLadderMinGrades() + " grades at least " + params.cardLadderMinSpan() + " apart)");
                    }
                    ratio = cardRatio;
                    stepMethod = GradePrice.Method.CARD_STEP_DOWN;
                }
                if (ratio.compareTo(BigDecimal.ONE) > 0 || ratio.signum() <= 0) {
                    throw new IllegalArgumentException("Step-down ratio must be above 0 and at most 1 for grade " + grade + ": " + ratio);
                }
                BigDecimal price = Money.cents(gross.get(next).multiply(ratio, Money.MC));
                gross.put(grade, price);
                method.put(grade, stepMethod);
                basis.put(grade, List.of(next));
                ratioUsed.put(grade, ratio);
                trace.add(subject, stepMethod == GradePrice.Method.CARD_STEP_DOWN ? "card's own step-down"
                                : grade == Grade.G1 ? "era step-down (the 2-to-3 ratio; grade 1 has none)" : "era step-down", price,
                        "grade " + next + " price " + gross.get(next) + " x " + ratio);
            } else {
                BigDecimal price = anchors.get(below);
                gross.put(grade, price);
                method.put(grade, GradePrice.Method.CARRIED_DOWN);
                basis.put(grade, List.of(below));
                trace.add(subject, "lower grade price, never stepped up", price, "from grade " + below);
            }
        }
    }

    /**
     * The card's own per-grade step-down: between each pair of neighbouring observed grades, the
     * per-grade ratio (lower price / higher price) ^ (1 / grades apart); the steepest (smallest) wins,
     * capped at 1 so it never steps up. Null without enough grades across enough of a spread.
     */
    private BigDecimal cardLadderRatio(Map<Grade, BigDecimal> observed) {
        List<Grade> grades = observed.keySet().stream().filter(g -> !g.isUngradable()).sorted().toList();
        if (grades.size() < params.cardLadderMinGrades()
                || grades.getLast().number() - grades.getFirst().number() < params.cardLadderMinSpan()) {
            return null;
        }
        double steepest = 1;
        for (int i = 1; i < grades.size(); i++) {
            Grade lo = grades.get(i - 1);
            Grade hi = grades.get(i);
            double perGrade = Math.pow(observed.get(lo).doubleValue() / observed.get(hi).doubleValue(),
                    1.0 / (hi.number() - lo.number()));
            steepest = Math.min(steepest, perGrade);
        }
        return BigDecimal.valueOf(steepest).setScale(6, RoundingMode.FLOOR);
    }

    private static Grade nearestObserved(Map<Grade, BigDecimal> observed, int start, int direction) {
        for (int g = start; g >= 1 && g <= 10; g += direction) {
            if (observed.containsKey(Grade.of(g))) {
                return Grade.of(g);
            }
        }
        return null;
    }

    private static BigDecimal logInterpolate(Grade lo, BigDecimal loPrice, Grade hi, BigDecimal hiPrice, Grade target) {
        double fraction = (double) (target.number() - lo.number()) / (hi.number() - lo.number());
        double logPrice = Math.log(loPrice.doubleValue())
                + fraction * (Math.log(hiPrice.doubleValue()) - Math.log(loPrice.doubleValue()));
        return Money.cents(BigDecimal.valueOf(Math.exp(logPrice)));
    }

    private record Observed(BigDecimal price, List<Comp> comps, int windowDays, int salesInWindow) {
    }

    /** Recency-weighted median of the comps in the window, or null if there are none. */
    private Observed observe(String subject, List<Comp> comps, LocalDate asOf, Trace trace) {
        if (comps == null || comps.isEmpty()) {
            return null;
        }
        int window = params.windowDays();
        List<Comp> inWindow = within(comps, asOf, window);
        if (inWindow.size() < params.minSalesInWindow()) {
            window = params.extendedWindowDays();
            inWindow = within(comps, asOf, window);
        }
        if (inWindow.isEmpty()) {
            return null;
        }
        List<Comp> sorted = new ArrayList<>(inWindow);
        sorted.sort(Comparator.comparing(Comp::price).thenComparing(Comp::soldDate).thenComparing(Comp::id));
        int n = sorted.size();
        String trim = "";
        if (n >= params.outlierTrimMinSales()) {
            int cut = params.outlierTrimFraction().multiply(BigDecimal.valueOf(n)).setScale(0, RoundingMode.FLOOR).intValueExact();
            sorted = sorted.subList(cut, n - cut);
            trim = ", trimmed " + cut + " from each end";
        }
        BigDecimal price = weightedMedian(sorted, asOf);
        trace.add(subject, "recency-weighted median", price,
                n + " sales in " + window + "-day window" + trim + ", half-life " + params.halfLifeDays() + "d");
        return new Observed(price, List.copyOf(sorted), window, n);
    }

    private static List<Comp> within(List<Comp> comps, LocalDate asOf, int days) {
        LocalDate from = asOf.minusDays(days);
        return comps.stream()
                .filter(c -> !c.soldDate().isAfter(asOf) && !c.soldDate().isBefore(from))
                .toList();
    }

    /**
     * Weighted median with weight 0.5^(age / half-life). Takes the lower median when the cumulative
     * weight lands exactly on half (conservative). Weights are relative, so double precision is fine.
     */
    private BigDecimal weightedMedian(List<Comp> sortedByPrice, LocalDate asOf) {
        double[] weights = new double[sortedByPrice.size()];
        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            long age = ChronoUnit.DAYS.between(sortedByPrice.get(i).soldDate(), asOf);
            weights[i] = Math.pow(0.5, (double) age / params.halfLifeDays());
            total += weights[i];
        }
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (cumulative >= total / 2 - 1e-12) {
                return sortedByPrice.get(i).price();
            }
        }
        return sortedByPrice.getLast().price();
    }
}

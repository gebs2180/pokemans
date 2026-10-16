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
 *   <li>U from damaged/altered raw sales, else the bulk floor.
 *   <li>Net of grading costs, unless the raw-sale price for that condition is higher.
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
     * @param bulkFloor          U price when no damaged raw sales exist
     * @param stepDownRatios     for grade g, the era median ratio of price(g) to price(g + 1)
     * @param gradingCost        cost of grading, netted from graded prices
     * @param rawConditionPrices optional raw-sale price for a card in the condition of each grade
     */
    public record Input(
            LocalDate asOf,
            Map<Grade, List<Comp>> gradedComps,
            List<Comp> damagedRawComps,
            BigDecimal bulkFloor,
            Map<Grade, BigDecimal> stepDownRatios,
            GradingCost gradingCost,
            Map<Grade, BigDecimal> rawConditionPrices) {
    }

    public GradePriceTable price(Input input) {
        Trace trace = new Trace();
        EnumMap<Grade, BigDecimal> gross = new EnumMap<>(Grade.class);
        EnumMap<Grade, GradePrice.Method> method = new EnumMap<>(Grade.class);
        EnumMap<Grade, List<Comp>> used = new EnumMap<>(Grade.class);

        for (int g = 1; g <= 10; g++) {
            Grade grade = Grade.of(g);
            List<Comp> comps = input.gradedComps().getOrDefault(grade, List.of());
            Observed observed = observe("price[" + grade + "]", comps, input.asOf(), trace);
            if (observed != null) {
                gross.put(grade, observed.price());
                method.put(grade, GradePrice.Method.OBSERVED);
                used.put(grade, observed.comps());
            }
        }

        fillMissingGrades(input, gross, method, trace);

        Observed damaged = observe("price[U]", input.damagedRawComps(), input.asOf(), trace);
        if (damaged != null) {
            gross.put(Grade.U, damaged.price());
            method.put(Grade.U, GradePrice.Method.OBSERVED);
            used.put(Grade.U, damaged.comps());
        } else {
            gross.put(Grade.U, input.bulkFloor());
            method.put(Grade.U, GradePrice.Method.FLOOR);
            trace.add("price[U]", "bulk floor", input.bulkFloor(), "no damaged/altered raw sales in window");
        }

        EnumMap<Grade, GradePrice> prices = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            List<Comp> comps = used.getOrDefault(grade, List.of());
            List<String> ids = comps.stream().map(Comp::id).toList();
            BigDecimal g = gross.get(grade);
            if (grade.isUngradable()) {
                // U is already a raw sale price: no grading costs to net.
                prices.put(grade, new GradePrice(grade, g, g, method.get(grade), comps.size(), ids, false));
                continue;
            }
            BigDecimal netGraded = Money.max(BigDecimal.ZERO, Money.cents(input.gradingCost().net(g)));
            BigDecimal raw = input.rawConditionPrices() == null ? null : input.rawConditionPrices().get(grade);
            boolean rawWins = raw != null && raw.compareTo(netGraded) > 0;
            BigDecimal net = rawWins ? raw : netGraded;
            trace.add("net[" + grade + "]", rawWins ? "raw-sale price higher than net graded" : "net of grading cost", net,
                    "gross " + g + " - fixed " + input.gradingCost().fixed() + " - time-to-cash "
                            + Money.cents(input.gradingCost().waitingCost(g)) + (raw == null ? "" : "; raw " + raw));
            prices.put(grade, new GradePrice(grade, g, net, method.get(grade), comps.size(), ids, rawWins));
        }
        return new GradePriceTable(prices, trace);
    }

    private void fillMissingGrades(Input input, EnumMap<Grade, BigDecimal> gross,
                                   EnumMap<Grade, GradePrice.Method> method, Trace trace) {
        // Observed grades only: derived prices never anchor other derived prices except step-down chains.
        EnumMap<Grade, BigDecimal> observed = new EnumMap<>(gross);
        if (observed.isEmpty()) {
            for (int g = 1; g <= 10; g++) {
                Grade grade = Grade.of(g);
                gross.put(grade, input.bulkFloor());
                method.put(grade, GradePrice.Method.FLOOR);
                trace.add("price[" + grade + "]", "bulk floor", input.bulkFloor(), "no graded sales for any grade");
            }
            return;
        }
        // Walk downward so step-down chains use the already-derived grade above.
        for (int g = 10; g >= 1; g--) {
            Grade grade = Grade.of(g);
            if (observed.containsKey(grade)) {
                continue;
            }
            Grade below = nearestObserved(observed, g - 1, -1);
            Grade above = nearestObserved(observed, g + 1, +1);
            String subject = "price[" + grade + "]";
            if (below != null && above != null) {
                BigDecimal price = logInterpolate(below, observed.get(below), above, observed.get(above), grade);
                gross.put(grade, price);
                method.put(grade, GradePrice.Method.INTERPOLATED);
                trace.add(subject, "log interpolation", price, "between grade " + below + " (" + observed.get(below)
                        + ") and grade " + above + " (" + observed.get(above) + ")");
            } else if (above != null) {
                Grade next = Grade.of(g + 1);
                BigDecimal ratio = input.stepDownRatios().get(grade);
                if (ratio == null) {
                    throw new IllegalArgumentException("No era step-down ratio for grade " + grade + " to " + next);
                }
                if (ratio.compareTo(BigDecimal.ONE) > 0) {
                    throw new IllegalArgumentException("Step-down ratio above 1 for grade " + grade + ": " + ratio);
                }
                BigDecimal price = Money.cents(gross.get(next).multiply(ratio, Money.MC));
                gross.put(grade, price);
                method.put(grade, GradePrice.Method.STEP_DOWN);
                trace.add(subject, "era step-down", price, "grade " + next + " price " + gross.get(next) + " x " + ratio);
            } else {
                BigDecimal price = observed.get(below);
                gross.put(grade, price);
                method.put(grade, GradePrice.Method.CARRIED_DOWN);
                trace.add(subject, "lower grade price, never stepped up", price, "from grade " + below);
            }
        }
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

    private record Observed(BigDecimal price, List<Comp> comps) {
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
        return new Observed(price, List.copyOf(sorted));
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

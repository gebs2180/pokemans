package com.pokemans.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Raw-sale floors for the per-grade net price (step 3: "use the raw-sale price for that condition if it's
 * higher"; deep review B1 step 2, Zach 2026-11-11, parameter set 1.8.0).
 *
 * <p>A raw condition is the seller's listing label, not a grade, and sellers label optimistically, so the
 * mapping is narrow and the price is the low end of the condition's recent sale days:
 * <ul>
 *   <li>Each condition floors only the grades the parameter set maps it to (1.8.0: near mint 7 and 8,
 *       lightly played 5 and 6; 9 and 10 can be listed). Never 1, its own market: {@link ParameterSet} refuses it.
 *   <li>The floor is the recency-weighted {@code rawFloorQuantile} (1.8.0: the 25th percentile) of the
 *       condition's sale days in the 90-day window, extended to 180 when it has fewer than
 *       {@code rawFloorMinSaleDays}; still fewer, no floor. Never the average, never asking prices.
 *   <li>A worse condition's floor is never above a better one's: it is capped at it, never stepped up.
 * </ul>
 * Damaged and altered raw sales are not here: they price U ({@link GradePricer}).
 */
public final class RawFloors {

    /** Raw listing conditions that can floor a grade, best first. */
    public enum Condition { NEAR_MINT, LIGHTLY_PLAYED, MODERATELY_PLAYED, HEAVILY_PLAYED }

    /**
     * One grade's raw floor.
     *
     * @param condition  the raw condition whose sales set it
     * @param price      the floor, a raw sale price (no grading cost to net)
     * @param compIds    the raw sale days in the window
     * @param windowDays 90, or 180 when extended
     * @param saleDays   sale days in the window
     */
    public record Floor(Condition condition, BigDecimal price, List<String> compIds, int windowDays, int saleDays) {
        public Floor {
            compIds = List.copyOf(compIds);
        }
    }

    private RawFloors() {
    }

    /**
     * Floors per grade from raw comps per condition. Empty when the parameter set has no raw floors (before 1.8.0).
     * Adds a {@code raw_floor[<condition>]} trace step per condition with comps.
     */
    public static Map<Grade, Floor> compute(ParameterSet params, LocalDate asOf, Map<Condition, List<Comp>> rawComps, Trace trace) {
        EnumMap<Grade, Floor> floors = new EnumMap<>(Grade.class);
        if (!params.hasRawFloors()) {
            return floors;
        }
        BigDecimal better = null;
        for (Condition condition : Condition.values()) {
            List<Grade> grades = params.rawFloorGrades(condition);
            List<Comp> comps = rawComps.getOrDefault(condition, List.of());
            String subject = "raw_floor[" + condition + "]";
            int window = params.windowDays();
            List<Comp> inWindow = within(comps, asOf, window);
            if (inWindow.size() < params.rawFloorMinSaleDays()) {
                window = params.extendedWindowDays();
                inWindow = within(comps, asOf, window);
            }
            if (inWindow.size() < params.rawFloorMinSaleDays()) {
                if (!comps.isEmpty()) {
                    trace.add(subject, "no floor: too few raw sale days", BigDecimal.ZERO,
                            inWindow.size() + " in " + window + " days, need " + params.rawFloorMinSaleDays());
                }
                continue;
            }
            BigDecimal price = weightedQuantile(inWindow, asOf, params.halfLifeDays(), params.rawFloorQuantile());
            String capped = "";
            if (better != null && price.compareTo(better) > 0) {
                capped = "; capped at the better condition's " + better;
                price = better;
            }
            better = price;
            trace.add(subject, "recency-weighted " + params.rawFloorQuantile() + " quantile of raw sale days", price,
                    inWindow.size() + " sale days in " + window + "-day window, half-life " + params.halfLifeDays() + "d"
                            + capped + (grades.isEmpty() ? "; floors no grade" : "; floors grades " + grades));
            List<String> ids = inWindow.stream().map(Comp::id).toList();
            for (Grade grade : grades) {
                floors.put(grade, new Floor(condition, price, ids, window, inWindow.size()));
            }
        }
        return floors;
    }

    private static List<Comp> within(List<Comp> comps, LocalDate asOf, int days) {
        LocalDate from = asOf.minusDays(days);
        return comps.stream().filter(c -> !c.soldDate().isAfter(asOf) && !c.soldDate().isBefore(from)).toList();
    }

    /**
     * The lowest price whose cumulative recency weight (0.5^(age / half-life)) reaches {@code q} of the total:
     * the same weighting as the graded median, read lower down.
     */
    static BigDecimal weightedQuantile(List<Comp> comps, LocalDate asOf, int halfLifeDays, BigDecimal q) {
        List<Comp> sorted = new ArrayList<>(comps);
        sorted.sort(Comparator.comparing(Comp::price).thenComparing(Comp::soldDate).thenComparing(Comp::id));
        double[] weights = new double[sorted.size()];
        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            long age = ChronoUnit.DAYS.between(sorted.get(i).soldDate(), asOf);
            weights[i] = Math.pow(0.5, (double) age / halfLifeDays);
            total += weights[i];
        }
        double target = total * q.doubleValue();
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (cumulative >= target - 1e-12) {
                return sorted.get(i).price();
            }
        }
        return sorted.getLast().price();
    }
}

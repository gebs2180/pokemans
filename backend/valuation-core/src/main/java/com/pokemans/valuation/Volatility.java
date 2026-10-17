package com.pokemans.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Step 3: monthly price volatility σ, stored per card for FLV. */
public final class Volatility {

    /** Length of one price change. */
    public static final int PERIOD_DAYS = 30;

    private Volatility() {
    }

    /**
     * Sample standard deviation of 30-day relative price changes.
     *
     * @param pricesAt30DayIntervals oldest first, e.g. 13 points covering 12 months
     */
    public static BigDecimal monthly(List<BigDecimal> pricesAt30DayIntervals) {
        if (pricesAt30DayIntervals.size() < 3) {
            throw new IllegalArgumentException("Need at least 3 price points for two changes");
        }
        return sampleStdDev(changes(pricesAt30DayIntervals));
    }

    /**
     * @param sigma   monthly σ
     * @param changes 30-day changes it was computed from
     */
    public record Estimate(BigDecimal sigma, int changes) {
    }

    /**
     * σ from sold comps over the last {@code months} 30-day periods. Each grade's comps go into
     * 30-day buckets ending on {@code asOf}, and each bucket's price is its median sale (the lower
     * one for an even count). A change counts only between two adjacent non-empty buckets of the
     * same grade, so a gap is never bridged. Changes from every grade are pooled: σ is a property of
     * the card's market, and grades move together.
     *
     * @return empty when fewer than two changes exist; the caller must not invent a σ
     */
    public static Optional<Estimate> fromComps(Map<Grade, List<Comp>> compsByGrade, LocalDate asOf, int months) {
        return fromSeries(compsByGrade.values(), asOf, months);
    }

    /**
     * As {@link #fromComps}, over any set of price series, e.g. one per grading company and grade.
     * Each series must be one market (one grader's one grade), so a change never compares two.
     */
    public static Optional<Estimate> fromSeries(Collection<List<Comp>> series, LocalDate asOf, int months) {
        if (months < 2) {
            throw new IllegalArgumentException("Need at least 2 months: " + months);
        }
        List<BigDecimal> pooled = new ArrayList<>();
        for (List<Comp> comps : series) {
            BigDecimal[] points = bucketMedians(comps, asOf, months);
            for (int i = 1; i < points.length; i++) {
                if (points[i - 1] != null && points[i] != null) {
                    pooled.add(points[i].divide(points[i - 1], Money.MC).subtract(BigDecimal.ONE));
                }
            }
        }
        if (pooled.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(new Estimate(sampleStdDev(pooled), pooled.size()));
    }

    /** Median price per bucket, oldest first: bucket i ends {@code (months - i) * 30} days before asOf. */
    private static BigDecimal[] bucketMedians(Collection<Comp> comps, LocalDate asOf, int months) {
        BigDecimal[] medians = new BigDecimal[months + 1];
        for (int i = 0; i <= months; i++) {
            LocalDate end = asOf.minusDays((long) (months - i) * PERIOD_DAYS);
            LocalDate start = end.minusDays(PERIOD_DAYS);
            List<BigDecimal> prices = comps.stream()
                    .filter(c -> c.soldDate().isAfter(start) && !c.soldDate().isAfter(end))
                    .map(Comp::price)
                    .sorted()
                    .toList();
            medians[i] = prices.isEmpty() ? null : prices.get((prices.size() - 1) / 2);
        }
        return medians;
    }

    private static List<BigDecimal> changes(List<BigDecimal> prices) {
        List<BigDecimal> changes = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            changes.add(prices.get(i).divide(prices.get(i - 1), Money.MC).subtract(BigDecimal.ONE));
        }
        return changes;
    }

    private static BigDecimal sampleStdDev(List<BigDecimal> values) {
        BigDecimal mean = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), Money.MC);
        BigDecimal sumSquares = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            BigDecimal d = value.subtract(mean);
            sumSquares = sumSquares.add(d.multiply(d, Money.MC));
        }
        return sumSquares.divide(BigDecimal.valueOf(values.size() - 1L), Money.MC).sqrt(Money.MC);
    }
}

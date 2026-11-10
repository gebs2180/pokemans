package com.pokemans.app.pricing;

import com.pokemans.valuation.Grade;
import com.pokemans.valuation.ParameterSet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Which PokeTrace sale days are left out of pricing (review B4, parameter set 1.5.0). A PokeTrace comp is
 * one day's average, so a single wild sale on a busy day becomes a wild comp, and below 10 sale days the
 * median isn't trimmed. A day is left out, never shifted, when:
 * <ul>
 *   <li>{@link #INCONSISTENT}: its average lies outside its own low–high range, or its low isn't positive;</li>
 *   <li>{@link #WIDE_DAY}: its high is more than {@code dayMaxSpread} times its low;</li>
 *   <li>{@link #HIGH_DAY}: its average is more than {@code dayMaxMultipleOfTier} times the median of at least
 *       {@code dayGuardMinOtherDays} other sale days of its tier (any source) within
 *       {@code dayGuardNeighbourhoodDays} either side.</li>
 * </ul>
 * Only high days are caught by the last rule: leaving out a low day would push the price up, which the
 * principles forbid. Comps from other sources are never left out, only used as neighbours. Sets before
 * 1.5.0 have no guards, and every comp is kept.
 */
final class PokeTraceDayGuard {

    static final String INCONSISTENT = "INCONSISTENT";
    static final String WIDE_DAY = "WIDE_DAY";
    static final String HIGH_DAY = "HIGH_DAY";

    /** A comp left out of pricing, the rule that caught it and why, in words. */
    record Excluded(SoldComp comp, String rule, String reason) {
    }

    record Result(List<SoldComp> kept, List<Excluded> excluded) {
    }

    private static final BigDecimal CENT = new BigDecimal("0.01");

    private PokeTraceDayGuard() {
    }

    static Result apply(List<SoldComp> comps, ParameterSet params) {
        List<Excluded> excluded = new ArrayList<>();
        List<SoldComp> passed = new ArrayList<>();
        for (SoldComp c : comps) {
            Excluded e = isPokeTrace(c) ? ownRow(c, params) : null;
            if (e == null) {
                passed.add(c);
            } else {
                excluded.add(e);
            }
        }
        if (params.dayMaxMultipleOfTier() == null || params.dayGuardMinOtherDays() == null
                || params.dayGuardNeighbourhoodDays() == null) {
            return new Result(passed, excluded);
        }
        Map<String, List<SoldComp>> byTier = passed.stream().collect(Collectors.groupingBy(PokeTraceDayGuard::tier));
        Map<SoldComp, Excluded> high = new LinkedHashMap<>();
        for (SoldComp c : passed) {
            if (!isPokeTrace(c)) {
                continue;
            }
            List<BigDecimal> others = byTier.get(tier(c)).stream()
                    .filter(o -> o != c && Math.abs(ChronoUnit.DAYS.between(o.getSoldOn(), c.getSoldOn()))
                            <= params.dayGuardNeighbourhoodDays())
                    .map(SoldComp::getPrice)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (others.size() < params.dayGuardMinOtherDays()) {
                continue;
            }
            BigDecimal median = median(others);
            BigDecimal limit = median.multiply(params.dayMaxMultipleOfTier());
            if (c.getPrice().compareTo(limit) > 0) {
                high.put(c, new Excluded(c, HIGH_DAY, "day average " + c.getPrice() + " is over "
                        + params.dayMaxMultipleOfTier().stripTrailingZeros().toPlainString() + "x the median "
                        + median.setScale(2, RoundingMode.HALF_EVEN) + " of " + others.size() + " other sale days within "
                        + params.dayGuardNeighbourhoodDays() + " days"));
            }
        }
        // Neighbours are judged against every day that passed its own row's checks, so one high day can't hide another.
        excluded.addAll(high.values());
        return new Result(passed.stream().filter(c -> !high.containsKey(c)).toList(), excluded);
    }

    /** The day's own row: average inside low–high, and a spread no wider than the set allows. */
    private static Excluded ownRow(SoldComp c, ParameterSet params) {
        if (params.dayMaxSpread() == null) {
            return null;
        }
        BigDecimal low = money(c.getRaw() == null ? null : c.getRaw().get("low"));
        BigDecimal high = money(c.getRaw() == null ? null : c.getRaw().get("high"));
        if (low == null || high == null) {
            return null; // nothing to check against
        }
        if (low.signum() <= 0 || high.compareTo(low) < 0
                || c.getPrice().compareTo(low.subtract(CENT)) < 0 || c.getPrice().compareTo(high.add(CENT)) > 0) {
            return new Excluded(c, INCONSISTENT, "day average " + c.getPrice() + " doesn't fit the day's range " + low + " to " + high);
        }
        if (high.compareTo(low.multiply(params.dayMaxSpread())) > 0) {
            BigDecimal ratio = high.divide(low, 1, RoundingMode.HALF_EVEN);
            return new Excluded(c, WIDE_DAY, "day's high " + high + " is " + ratio + "x its low " + low + " (over "
                    + params.dayMaxSpread().stripTrailingZeros().toPlainString() + "x): one sale can't be told from the rest");
        }
        return null;
    }

    private static boolean isPokeTrace(SoldComp c) {
        return PokeTraceSource.NAME.equals(c.getSource());
    }

    /** A tier as pricing sees it: grading company and folded grade, or raw condition. */
    private static String tier(SoldComp c) {
        return c.getGrade() != null ? c.getGradingCompany() + ":" + Grade.foldHalf(c.getGrade()) : "RAW:" + c.getRawCondition();
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.TWO, 4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal money(Object o) {
        return o instanceof Number n ? new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_EVEN) : null;
    }
}

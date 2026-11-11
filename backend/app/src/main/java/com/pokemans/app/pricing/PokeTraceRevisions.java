package com.pokemans.app.pricing;

import com.pokemans.valuation.ParameterSet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Revised PokeTrace days (a PR #114 follow-up, parameter set 1.7.0). PokeTrace can send a different average,
 * low or high for a day we already stored. The stored row is never edited (store everything): ingestion appends
 * the new values as a revision, with external sale id {@code <day id>#r<n>}, where the day's first row is
 * revision 0 and keeps the plain day id.
 *
 * <p>From 1.7.0 a day's newest revision is its comp, and every older row of the day is left out of pricing with
 * the revision that replaced it, so both stay in the trace. The day guards then judge the newest one. Sets before
 * 1.7.0 ignore revisions: the day's first row stays its comp, exactly as before.
 */
final class PokeTraceRevisions {

    /** The rule a superseded row is stored under in {@code comp_exclusion}. */
    static final String SUPERSEDED = "SUPERSEDED";

    private static final Pattern REVISION = Pattern.compile("^(.*)#r(\\d+)$");

    private PokeTraceRevisions() {
    }

    /** The external sale id of the day a PokeTrace row belongs to, without its revision suffix. */
    static String day(String externalSaleId) {
        Matcher m = REVISION.matcher(externalSaleId);
        return m.matches() ? m.group(1) : externalSaleId;
    }

    /** The row's revision number: 0 for the day's first row. */
    static int revision(String externalSaleId) {
        Matcher m = REVISION.matcher(externalSaleId);
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    /** The external sale id of revision {@code n} of a day. */
    static String revisionId(String dayId, int n) {
        return n == 0 ? dayId : dayId + "#r" + n;
    }

    /**
     * True when PokeTrace's new row for a day differs from the stored one in a value pricing reads: the average
     * (the comp's price), or the low or high the day guards check. The noised sale count isn't one of them.
     */
    static boolean changed(BigDecimal storedPrice, Map<String, Object> storedRaw, PriceSource.Sale fresh) {
        if (storedPrice.compareTo(fresh.price()) != 0) {
            return true;
        }
        for (String key : List.of("low", "high")) {
            BigDecimal before = money(storedRaw == null ? null : storedRaw.get(key));
            BigDecimal after = money(fresh.raw() == null ? null : fresh.raw().get(key));
            if (before == null ? after != null : after == null || before.compareTo(after) != 0) {
                return true;
            }
        }
        return false;
    }

    /** @param superseded rows left out because a newer revision of their day replaced them */
    record Result(List<SoldComp> current, List<PokeTraceDayGuard.Excluded> superseded) {
    }

    /**
     * Each PokeTrace day's comp under the parameter set: its newest revision from 1.7.0 on, its first row before.
     * Comps from other sources pass through.
     */
    static Result apply(List<SoldComp> comps, ParameterSet params) {
        if (!params.pricesPokeTraceRevisions()) {
            return new Result(comps.stream()
                    .filter(c -> !isPokeTrace(c) || revision(c.getExternalSaleId()) == 0).toList(), List.of());
        }
        Map<String, List<SoldComp>> byDay = new LinkedHashMap<>();
        comps.stream().filter(PokeTraceRevisions::isPokeTrace)
                .forEach(c -> byDay.computeIfAbsent(day(c.getExternalSaleId()), k -> new ArrayList<>()).add(c));
        Map<SoldComp, PokeTraceDayGuard.Excluded> superseded = new LinkedHashMap<>();
        for (List<SoldComp> rows : byDay.values()) {
            rows.sort(Comparator.comparingInt((SoldComp c) -> revision(c.getExternalSaleId())));
            SoldComp newest = rows.getLast();
            for (SoldComp old : rows.subList(0, rows.size() - 1)) {
                superseded.put(old, new PokeTraceDayGuard.Excluded(old, SUPERSEDED, "PokeTrace revised this day: average "
                        + old.getPrice() + range(old) + " was replaced by " + newest.getPrice() + range(newest) + " (revision "
                        + revision(newest.getExternalSaleId()) + ", comp " + newest.getId() + ")"));
            }
        }
        return new Result(comps.stream().filter(c -> !superseded.containsKey(c)).toList(), List.copyOf(superseded.values()));
    }

    private static boolean isPokeTrace(SoldComp c) {
        return PokeTraceSource.NAME.equals(c.getSource());
    }

    private static String range(SoldComp c) {
        BigDecimal low = money(c.getRaw() == null ? null : c.getRaw().get("low"));
        BigDecimal high = money(c.getRaw() == null ? null : c.getRaw().get("high"));
        return low == null || high == null || Objects.equals(low, high) && low.compareTo(c.getPrice()) == 0 ? ""
                : " (" + low + " to " + high + ")";
    }

    private static BigDecimal money(Object o) {
        return o instanceof Number n ? new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_EVEN) : null;
    }
}

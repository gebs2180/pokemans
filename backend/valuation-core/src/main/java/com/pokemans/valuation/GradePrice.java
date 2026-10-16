package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.List;

/**
 * Price for one grade of one card.
 *
 * @param gross       graded (or, for U, raw damaged) sale price before grading costs
 * @param net         price used in FMV: gross net of grading costs, or the raw-sale price if higher
 * @param method      how the gross price was obtained
 * @param salesUsed   number of sold comps behind an observed price (0 when derived)
 * @param compIds     the comps used, for traceability
 * @param rawOverride true when the raw-sale price beat the net graded price
 */
public record GradePrice(
        Grade grade,
        BigDecimal gross,
        BigDecimal net,
        Method method,
        int salesUsed,
        List<String> compIds,
        boolean rawOverride) {

    public GradePrice {
        compIds = List.copyOf(compIds);
    }

    /** Method names match GradePriceSnapshot.method in the HLD. */
    public enum Method {
        /** Recency-weighted median of this grade's own sold comps. */
        OBSERVED,
        /** Log-price interpolation between observed grades on both sides. */
        INTERPOLATED,
        /** Higher observed grade times the era step-down ratio. */
        STEP_DOWN,
        /** Only lower grades observed: the lower grade's price, unchanged (never stepped up). */
        CARRIED_DOWN,
        /** No usable comps: bulk floor. */
        FLOOR
    }

    /** True for prices from the missing-grade rule; these drop confidence one level. */
    public boolean derived() {
        return method == Method.INTERPOLATED || method == Method.STEP_DOWN || method == Method.CARRIED_DOWN;
    }

    /** A price given directly (e.g. by the reviewer or a test), with the net already computed. */
    public static GradePrice given(Grade grade, BigDecimal net, Method method, int salesUsed) {
        return new GradePrice(grade, net, net, method, salesUsed, List.of(), false);
    }
}

package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Step 3: monthly price volatility σ, stored per card for FLV. */
public final class Volatility {

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
        List<BigDecimal> changes = new ArrayList<>();
        for (int i = 1; i < pricesAt30DayIntervals.size(); i++) {
            BigDecimal previous = pricesAt30DayIntervals.get(i - 1);
            BigDecimal current = pricesAt30DayIntervals.get(i);
            changes.add(current.divide(previous, Money.MC).subtract(BigDecimal.ONE));
        }
        BigDecimal mean = changes.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(changes.size()), Money.MC);
        BigDecimal sumSquares = BigDecimal.ZERO;
        for (BigDecimal change : changes) {
            BigDecimal d = change.subtract(mean);
            sumSquares = sumSquares.add(d.multiply(d, Money.MC));
        }
        return sumSquares.divide(BigDecimal.valueOf(changes.size() - 1L), Money.MC).sqrt(Money.MC);
    }
}

package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Prices for all 11 outcomes of one card, plus the trace of how they were set. */
public final class GradePriceTable {

    private final EnumMap<Grade, GradePrice> prices;
    private final Trace trace;

    public GradePriceTable(Map<Grade, GradePrice> prices, Trace trace) {
        EnumMap<Grade, GradePrice> copy = new EnumMap<>(Grade.class);
        copy.putAll(prices);
        for (Grade grade : Grade.values()) {
            GradePrice price = copy.get(grade);
            if (price == null) {
                throw new IllegalArgumentException("Missing price for grade " + grade);
            }
            if (price.grade() != grade) {
                throw new IllegalArgumentException("Price for grade " + price.grade() + " filed under " + grade);
            }
        }
        this.prices = copy;
        this.trace = trace;
    }

    public GradePrice get(Grade grade) {
        return prices.get(grade);
    }

    public BigDecimal net(Grade grade) {
        return prices.get(grade).net();
    }

    public BigDecimal gross(Grade grade) {
        return prices.get(grade).gross();
    }

    public Collection<GradePrice> all() {
        return Collections.unmodifiableCollection(prices.values());
    }

    public Trace trace() {
        return trace;
    }
}

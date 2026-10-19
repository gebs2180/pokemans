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

    /**
     * A table from gross snapshot prices (pricing stores them gross), netted of {@code cost} the way
     * {@link GradePricer} nets: each gradable grade at its {@link GradingCost#netPrice}, U unchanged
     * because it is already a raw sale price. Snapshots carry no raw-condition prices, so there is no
     * raw-sale override here.
     */
    public static GradePriceTable netOf(Collection<GradePrice> grossPrices, GradingCost cost) {
        Trace trace = new Trace();
        EnumMap<Grade, GradePrice> netted = new EnumMap<>(Grade.class);
        for (GradePrice p : grossPrices) {
            BigDecimal net = p.grade().isUngradable() ? p.gross() : cost.netPrice(p.gross());
            if (!p.grade().isUngradable()) {
                trace.add("net[" + p.grade() + "]", "net of grading cost", net, "gross " + p.gross() + " - fixed " + cost.fixed()
                        + " - time-to-cash " + Money.cents(cost.waitingCost(p.gross())));
            }
            netted.put(p.grade(), p.withNet(net));
        }
        return new GradePriceTable(netted, trace);
    }

    /** The same prices with every net set to its gross: a graded card, or the grade-first lift's raw FLV. */
    public GradePriceTable atGross() {
        EnumMap<Grade, GradePrice> gross = new EnumMap<>(Grade.class);
        prices.forEach((grade, p) -> gross.put(grade, p.withNet(p.gross())));
        return new GradePriceTable(gross, trace);
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

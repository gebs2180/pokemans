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
        this.floors = new EnumMap<>(Grade.class);
    }

    /**
     * A table from gross snapshot prices (pricing stores them gross), netted of {@code cost} the way
     * {@link GradePricer} nets: each gradable grade at its {@link GradingCost#netPrice}, U unchanged
     * because it is already a raw sale price. No raw-sale floors.
     */
    public static GradePriceTable netOf(Collection<GradePrice> grossPrices, GradingCost cost) {
        return netOf(grossPrices, cost, Map.of());
    }

    /**
     * As {@link #netOf(Collection, GradingCost)}, with raw-sale floors (parameter set 1.8.0, step 3: "use the
     * raw-sale price for that condition if it's higher"): a floored grade nets at the higher of its net graded
     * price and its floor, which is a raw sale price and so carries no grading cost. A grade priced at its floor
     * is marked {@link GradePrice#rawOverride()}. U is never floored here (damaged sales already price it).
     */
    public static GradePriceTable netOf(Collection<GradePrice> grossPrices, GradingCost cost, Map<Grade, RawFloors.Floor> floors) {
        Trace trace = new Trace();
        EnumMap<Grade, GradePrice> netted = new EnumMap<>(Grade.class);
        EnumMap<Grade, RawFloors.Floor> kept = new EnumMap<>(Grade.class);
        for (GradePrice p : grossPrices) {
            if (p.grade().isUngradable()) {
                netted.put(p.grade(), p.withNet(p.gross()));
                continue;
            }
            BigDecimal net = cost.netPrice(p.gross());
            trace.add("net[" + p.grade() + "]", "net of grading cost", net, "gross " + p.gross() + " - fixed " + cost.fixed()
                    + " - time-to-cash " + Money.cents(cost.waitingCost(p.gross())));
            GradePrice priced = p.withNet(net);
            RawFloors.Floor floor = floors.get(p.grade());
            if (floor != null) {
                kept.put(p.grade(), floor);
                if (floor.price().compareTo(net) > 0) {
                    priced = priced.atRawFloor(floor.price());
                    trace.add("net[" + p.grade() + "]", "raw-sale floor: " + floor.condition() + " sells above the net graded price",
                            floor.price(), "net graded " + net + " < " + floor.condition() + " floor " + floor.price() + " ("
                                    + floor.saleDays() + " sale days in " + floor.windowDays() + " days)");
                }
            }
            netted.put(p.grade(), priced);
        }
        return new GradePriceTable(netted, trace, kept);
    }

    private final EnumMap<Grade, RawFloors.Floor> floors;

    private GradePriceTable(Map<Grade, GradePrice> prices, Trace trace, Map<Grade, RawFloors.Floor> floors) {
        this(prices, trace);
        this.floors.putAll(floors);
    }

    /**
     * The same prices with every net set to its gross: a graded card, or the grade-first lift's raw FLV. A raw floor
     * still applies to the gross (a raw card can always be sold raw), which only lowers the lift.
     */
    public GradePriceTable atGross() {
        EnumMap<Grade, GradePrice> gross = new EnumMap<>(Grade.class);
        prices.forEach((grade, p) -> {
            RawFloors.Floor floor = floors.get(grade);
            gross.put(grade, floor != null && floor.price().compareTo(p.gross()) > 0
                    ? p.withNet(p.gross()).atRawFloor(floor.price())
                    : p.withNet(p.gross()));
        });
        return new GradePriceTable(gross, trace, floors);
    }

    /** The grade's raw-sale floor, or null for none. */
    public RawFloors.Floor rawFloor(Grade grade) {
        return floors.get(grade);
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

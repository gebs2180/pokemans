package com.pokemans.valuation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * An old price table still prices the card, at lower confidence (Zach, 2026-10-18). Tables are
 * re-priced nightly; one that wasn't replaced (its sales aged out of the window, or were purged)
 * loses one confidence level once it is past the grace period and one more each period after, so
 * it falls to D (out of the collateral base) instead of being trusted indefinitely.
 */
public final class Staleness {

    private final ParameterSet params;

    public Staleness(ParameterSet params) {
        this.params = Objects.requireNonNull(params);
    }

    /** Confidence levels to drop for a table priced as of {@code asOf}, seen on {@code today}. */
    public int levels(LocalDate asOf, LocalDate today) {
        long age = ChronoUnit.DAYS.between(asOf, today);
        if (age <= params.staleGraceDays()) {
            return 0;
        }
        return (int) Math.min(Confidence.values().length - 1, 1 + (age - params.staleGraceDays() - 1) / params.staleDaysPerLevel());
    }

    /** {@code confidence} dropped by {@code levels}, floored at D. */
    public static Confidence drop(Confidence confidence, int levels) {
        Confidence c = confidence;
        for (int i = 0; i < levels; i++) {
            c = c.drop();
        }
        return c;
    }
}

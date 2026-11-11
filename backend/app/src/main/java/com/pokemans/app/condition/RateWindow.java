package com.pokemans.app.condition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A provider's per-minute limit, kept on our side so we don't hit it: at most {@code limit} calls in
 * any 60 seconds. One instance per app; a second instance falls back on the provider's own 429.
 */
final class RateWindow {

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final int limit;
    private final Clock clock;
    private final Deque<Instant> calls = new ArrayDeque<>();

    RateWindow(int limit, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("A rate limit must allow at least one call a minute, got " + limit);
        }
        this.limit = limit;
        this.clock = clock;
    }

    /** Takes a slot now, or returns how long until one frees up without taking it. */
    synchronized Duration tryAcquire() {
        Instant now = clock.instant();
        while (!calls.isEmpty() && !calls.peekFirst().plus(WINDOW).isAfter(now)) {
            calls.removeFirst();
        }
        if (calls.size() < limit) {
            calls.addLast(now);
            return Duration.ZERO;
        }
        return Duration.between(now, calls.peekFirst().plus(WINDOW));
    }
}

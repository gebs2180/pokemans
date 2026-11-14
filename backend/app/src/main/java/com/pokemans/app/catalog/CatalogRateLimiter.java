package com.pokemans.app.catalog;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Keeps one signed-in user from hammering TCGdex through the collector's card picker: at most
 * {@code limit} catalog calls per user per window, counted in this process (one app instance).
 * A typeahead that waits for a pause in typing stays well under it.
 */
class CatalogRateLimiter {

    private record Window(long startMillis, int count) {
    }

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    CatalogRateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /** Counts one call for {@code user}; 429 when they're over the limit for this window. */
    void acquire(String user) {
        long now = clock.millis();
        Window w = windows.compute(user, (k, old) -> old == null || now - old.startMillis() >= windowMillis
                ? new Window(now, 1)
                : new Window(old.startMillis(), old.count() + 1));
        if (w.count() > limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many searches at once. Wait a moment and try again.");
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= windowMillis);
        }
    }
}

package com.pokemans.app.storage;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The turns at decoding a full-size photo, shared by everything on this instance that decodes one (the
 * photo checks, our centering measurement): a 48 MP photo needs about 150 MB, so only
 * {@link StorageProperties#photoChecksAtOnce()} run at once, whoever asks. Fair, so a steady stream of
 * one kind can't starve the other.
 */
@Component
public class PhotoDecodes {

    /** No turn came up within the wait. */
    public static final class Busy extends RuntimeException {
        Busy() {
            super("No photo decode turn came up in time");
        }
    }

    private final Semaphore turns;

    public PhotoDecodes(StorageProperties storage) {
        this.turns = new Semaphore(storage.photoChecksAtOnce(), true);
    }

    /** How many callers are waiting for a turn now, roughly: for tests and monitoring. */
    public int waiting() {
        return turns.getQueueLength();
    }

    /**
     * Runs {@code work} once a turn comes up, waiting at most {@code wait}.
     *
     * @throws Busy if no turn came up in time, or the thread was interrupted while waiting
     */
    public <T> T withTurn(Duration wait, Supplier<T> work) {
        boolean turn;
        try {
            turn = turns.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            turn = false;
        }
        if (!turn) {
            throw new Busy();
        }
        try {
            return work.get();
        } finally {
            turns.release();
        }
    }
}

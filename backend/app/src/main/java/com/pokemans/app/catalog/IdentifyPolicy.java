package com.pokemans.app.catalog;

import java.time.Duration;
import java.time.Instant;

/**
 * When a collector identifying a card added photos first is shown the reader's suggestions, and when
 * they search the catalog instead. The one place this is decided: change the fallback here.
 *
 * <p>Search comes in when the reader is off, the card wasn't read, the reading failed or is slow, or
 * nothing in the catalog matched what it read. "Not my card" is the collector's own way to search,
 * from the suggestions, and needs no rule. Describing the card stays the last resort, below the search.
 */
final class IdentifyPolicy {

    /** A reading still pending this long after it was queued is left to finish in the background, and the collector searches. */
    static final Duration READING_SLOW = Duration.ofMinutes(2);

    /**
     * Suggestions are matched in their own transaction just after the reading is stored: a reading done
     * this recently with none yet may still get them.
     */
    static final Duration MATCH_GRACE = Duration.ofSeconds(30);

    enum Step {
        /** The admin has confirmed what the card is: nothing for the collector to do. */
        IDENTIFIED,
        /** Front and back still to take: the reader reads once both pass. */
        PHOTOS,
        /** The reader is reading the front, or the catalog is being matched: wait. */
        READING,
        /** "Is this your card?": the reader's suggestions. */
        SUGGEST,
        /** Search the catalog (or describe the card). */
        PICK,
        /** The collector has said what the card is. */
        PROPOSED
    }

    /** Why the collector searches instead of choosing a suggestion. */
    enum Why {
        READER_OFF, NOT_READ, READING_SLOW, READING_FAILED, NO_MATCH
    }

    record Decision(Step step, Why why) {
    }

    /**
     * What the card's latest reading says, or null if it has none.
     *
     * @param matchFailed the reading was done but matching it to the catalog failed
     */
    record ReadingState(CardReading.Status status, Instant createdAt, Instant finishedAt, boolean matchFailed,
                        int suggestions) {
    }

    /**
     * @param identified      the admin has confirmed an identity for the card
     * @param proposed        the collector has said what the card is (and isn't changing it)
     * @param captureComplete front and back have passed
     * @param capturedAt      when the last of them was uploaded, or null
     * @param readerEnabled   a card reader is configured
     * @param catalogEnabled  a catalog provider is configured, so readings get suggestions
     * @param reading         the card's latest reading, or null
     */
    static Decision decide(boolean identified, boolean proposed, boolean captureComplete, Instant capturedAt, boolean readerEnabled,
                           boolean catalogEnabled, ReadingState reading, Instant now) {
        if (identified) {
            return new Decision(Step.IDENTIFIED, null);
        }
        if (proposed) {
            return new Decision(Step.PROPOSED, null);
        }
        if (!captureComplete) {
            return new Decision(Step.PHOTOS, null);
        }
        if (!readerEnabled) {
            return pick(Why.READER_OFF);
        }
        if (reading == null) {
            // The reading is queued just after the capture commits; one that never came isn't coming.
            boolean queueing = capturedAt != null && now.isBefore(capturedAt.plus(READING_SLOW));
            return queueing ? new Decision(Step.READING, null) : pick(Why.NOT_READ);
        }
        return switch (reading.status()) {
            case PENDING -> now.isBefore(reading.createdAt().plus(READING_SLOW))
                    ? new Decision(Step.READING, null) : pick(Why.READING_SLOW);
            case FAILED -> pick(Why.READING_FAILED);
            case DONE -> {
                if (reading.suggestions() > 0) {
                    yield new Decision(Step.SUGGEST, null);
                }
                if (reading.matchFailed()) {
                    yield pick(Why.READING_FAILED);
                }
                Instant done = reading.finishedAt() != null ? reading.finishedAt() : reading.createdAt();
                yield catalogEnabled && now.isBefore(done.plus(MATCH_GRACE))
                        ? new Decision(Step.READING, null) : pick(Why.NO_MATCH);
            }
        };
    }

    private static Decision pick(Why why) {
        return new Decision(Step.PICK, why);
    }

    private IdentifyPolicy() {
    }
}

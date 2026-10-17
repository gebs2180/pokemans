package com.pokemans.app.condition;

import java.util.Map;

/**
 * A pre-grading API (HLD: {@code PreGradeProvider}). Calls are asynchronous at both providers we
 * use: submit, then poll until done. A provider that answers at once returns {@link Poll#done} from
 * {@link #poll} on the first call.
 *
 * <p>Check the provider's terms before enabling it: we store every answer and train on it
 * (docs/pregrade-providers.md).
 */
interface PreGradeProvider {

    /** Stable name stored with every request and prediction, e.g. "XIMILAR". */
    String name();

    /**
     * Sends the photos. {@code idempotencyKey} is unique per request, so a retry after a timeout
     * doesn't pay twice where the provider supports it.
     *
     * @return the provider's job id
     */
    String submit(Photos photos, String idempotencyKey);

    Poll poll(String externalRequestId);

    /** Original front (required) and back (may be null) photos. */
    record Photos(Image front, Image back) {
    }

    record Image(byte[] bytes, String contentType) {
    }

    /** Where a job stands. */
    sealed interface Poll {

        static Poll pending() {
            return new Pending();
        }

        static Poll done(Reading reading, Map<String, Object> raw) {
            return new Done(reading, raw);
        }

        static Poll failed(String reason, Map<String, Object> raw) {
            return new Failed(reason, raw);
        }
    }

    record Pending() implements Poll {
    }

    record Done(Reading reading, Map<String, Object> raw) implements Poll {
    }

    /** The provider looked and refused or couldn't grade (bad photo, sleeve): a final answer, not an outage. */
    record Failed(String reason, Map<String, Object> raw) implements Poll {
    }

    /**
     * Our reading of a provider answer. Grades on the provider's 1–10 scale; null where it gives none.
     *
     * @param modelVersion the provider's model version, when it reports one
     * @param psaGrade     a PSA-specific prediction, when the provider gives one
     * @param details      other fields worth keeping in normalized form (centering ratios, damage tag, ...)
     */
    record Reading(String modelVersion, Double overallGrade, Double psaGrade, Double centering, Double corners,
                   Double edges, Double surface, Map<String, Object> details) {
    }
}

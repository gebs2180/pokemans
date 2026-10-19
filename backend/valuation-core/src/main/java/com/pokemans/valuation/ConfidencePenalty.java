package com.pokemans.valuation;

import java.util.Objects;

/**
 * A confidence drop for a reason outside photo quality and comp counts, such as a stale price table
 * or a fallback σ. Applied to the base confidence before the FLV stress is chosen.
 *
 * @param reason why, as shown in the trace and the report
 * @param levels levels to drop, at least 0
 */
public record ConfidencePenalty(String reason, int levels) {
    public ConfidencePenalty {
        Objects.requireNonNull(reason, "reason");
        if (levels < 0) {
            throw new IllegalArgumentException("A penalty can't raise confidence: " + levels);
        }
    }
}

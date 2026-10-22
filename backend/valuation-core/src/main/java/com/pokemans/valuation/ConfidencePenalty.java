package com.pokemans.valuation;

import java.util.Objects;

/**
 * A confidence drop for a reason outside photo quality and comp counts, such as a stale price table
 * or a fallback σ. Applied to the base confidence before the FLV stress is chosen.
 *
 * @param reason  why, as shown in the trace and the report
 * @param levels  levels to drop, at least 0
 * @param flvOnly true when the reason only bears on the FLV (a fallback σ feeds only its volatility
 *                shock), so the instant offer's confidence doesn't take it (Zach, 2026-10-21)
 */
public record ConfidencePenalty(String reason, int levels, boolean flvOnly) {

    /** A penalty on every confidence. */
    public ConfidencePenalty(String reason, int levels) {
        this(reason, levels, false);
    }

    public ConfidencePenalty {
        Objects.requireNonNull(reason, "reason");
        if (levels < 0) {
            throw new IllegalArgumentException("A penalty can't raise confidence: " + levels);
        }
    }
}

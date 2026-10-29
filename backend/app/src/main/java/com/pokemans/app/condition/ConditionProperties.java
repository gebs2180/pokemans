package com.pokemans.app.condition;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Pre-grading providers and how a system distribution is built from their answers. Starting values,
 * to be tuned against returned grades (M5): every assessment stores the values it was built with.
 *
 * @param spread               how one provider grade becomes a distribution: probability per offset from the
 *                             (half-folded) grade, e.g. {-1: 0.25}. Must sum to 1.
 * @param disagreementForB     largest gap between provider grades that still allows confidence B
 * @param confidencePenaltyB   share of each grade's probability moved one grade down at confidence B
 * @param confidencePenaltyC   the same at confidence C
 * @param holoSurfacePenalty   moved one grade down for modern holos until a close-up clears the surface
 */
@ConfigurationProperties("pokemans.condition")
record ConditionProperties(
        Map<Integer, BigDecimal> spread,
        @DefaultValue("1.5") double disagreementForB,
        @DefaultValue("0.10") BigDecimal confidencePenaltyB,
        @DefaultValue("0.25") BigDecimal confidencePenaltyC,
        @DefaultValue("0.25") BigDecimal holoSurfacePenalty,
        @DefaultValue Worker worker,
        @DefaultValue Ximilar ximilar,
        @DefaultValue CardGrade cardgrade,
        @DefaultValue Centering centering) {

    /** Mostly on the provider's grade, more below than above: conservative by default. */
    static final Map<Integer, BigDecimal> DEFAULT_SPREAD = Map.of(
            1, new BigDecimal("0.10"), 0, new BigDecimal("0.50"), -1, new BigDecimal("0.25"),
            -2, new BigDecimal("0.10"), -3, new BigDecimal("0.05"));

    ConditionProperties {
        spread = spread == null || spread.isEmpty() ? DEFAULT_SPREAD : Map.copyOf(spread);
        BigDecimal sum = spread.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("pokemans.condition.spread must sum to 1, got " + sum);
        }
    }

    /**
     * @param enabled      run the background job (off in tests, which drive it directly)
     * @param pollInterval how often due requests are submitted or polled
     * @param maxAttempts  give up on a request after this many failed calls
     * @param maxPending   give up on a request still running at the provider after this long
     * @param lease        how long a claimed request stays hidden from other workers; longer than a
     *                     batch of provider calls takes, so a crashed worker's claims come back
     * @param claimBatch   requests claimed at a time
     * @param sweepWindow  how far back the sweep looks for completed captures that never got requests
     * @param sweepMinAge  how long a capture must have been complete before the sweep queues it, so it
     *                     doesn't race the capture-completed listener
     */
    record Worker(@DefaultValue("true") boolean enabled,
                  @DefaultValue("15s") Duration pollInterval,
                  @DefaultValue("8") int maxAttempts,
                  @DefaultValue("24h") Duration maxPending,
                  @DefaultValue("10m") Duration lease,
                  @DefaultValue("10") int claimBatch,
                  @DefaultValue("7d") Duration sweepWindow,
                  @DefaultValue("2m") Duration sweepMinAge) {
    }

    /** Off until the account's terms are confirmed in writing and a key is set. */
    record Ximilar(@DefaultValue("false") boolean enabled,
                   String apiKey,
                   @DefaultValue("https://api.ximilar.com") URI baseUrl,
                   @DefaultValue("30s") Duration timeout) {

        /** Never prints the key. */
        @Override
        public String toString() {
            return "Ximilar[enabled=" + enabled + ", apiKey=" + redacted(apiKey) + ", baseUrl=" + baseUrl + "]";
        }
    }

    /**
     * Off until the account's terms are confirmed in writing, AI training is opted out in its
     * settings, and a full-access key is set.
     *
     * @param gradingCompany grading standard to grade against; PSA matches the outcomes we collect
     */
    record CardGrade(@DefaultValue("false") boolean enabled,
                     String apiKey,
                     @DefaultValue("https://cardgrade.io") URI baseUrl,
                     @DefaultValue("PSA") String gradingCompany,
                     @DefaultValue("30s") Duration timeout) {

        /** Never prints the key. */
        @Override
        public String toString() {
            return "CardGrade[enabled=" + enabled + ", apiKey=" + redacted(apiKey) + ", baseUrl=" + baseUrl
                    + ", gradingCompany=" + gradingCompany + "]";
        }
    }

    /**
     * Our own centering measurement from the passing front and back ({@link CenteringPreGradeProvider}).
     * Off until it has been checked against returned grades.
     *
     * @param measurementMargin percentage points added to each measured larger share before PSA's
     *                          table is applied, for measurement error: conservative by default
     */
    record Centering(@DefaultValue("false") boolean enabled,
                     @DefaultValue("1.0") double measurementMargin) {
    }

    private static String redacted(String secret) {
        return secret == null || secret.isBlank() ? "unset" : "***";
    }
}

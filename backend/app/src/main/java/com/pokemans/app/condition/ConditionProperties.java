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
 * @param tightSpread          the spread used instead when the provider's corners, edges and surface (folded)
 *                             are all at or above its overall grade: nothing it saw pulls the card down, so
 *                             less mass goes below. Its centering sub-grade is never read (only our own
 *                             measurement caps). Must sum to 1 and put no more above the grade than
 *                             {@code spread} does (Zach, 2026-11-10; weights are defaults to confirm)
 * @param disagreementForB     largest gap between provider grades that still allows confidence B
 * @param confidencePenaltyB   share of each grade's probability moved one grade down at confidence B
 * @param confidencePenaltyC   the same at confidence C
 * @param maxTenProbability    the most probability a system distribution puts on 10; the rest moves down to 9.
 *                             Raw cards that look perfect in photos grade 10 far less often than the spread's
 *                             edge rule implies (review B2). A starting guess, not calibrated
 * @param ungradableFloor      the least probability a system distribution puts on U, taken proportionally from
 *                             the other grades: a photo model can't see trimming or recolouring (review B2).
 *                             A starting guess, not calibrated
 */
@ConfigurationProperties("pokemans.condition")
record ConditionProperties(
        Map<Integer, BigDecimal> spread,
        Map<Integer, BigDecimal> tightSpread,
        @DefaultValue("1.5") double disagreementForB,
        @DefaultValue("0.10") BigDecimal confidencePenaltyB,
        @DefaultValue("0.25") BigDecimal confidencePenaltyC,
        @DefaultValue("0.25") BigDecimal maxTenProbability,
        @DefaultValue("0.02") BigDecimal ungradableFloor,
        @DefaultValue Worker worker,
        @DefaultValue CardGrade cardgrade,
        @DefaultValue Centering centering) {

    /** Mostly on the provider's grade, more below than above: conservative by default. */
    static final Map<Integer, BigDecimal> DEFAULT_SPREAD = Map.of(
            1, new BigDecimal("0.10"), 0, new BigDecimal("0.50"), -1, new BigDecimal("0.25"),
            -2, new BigDecimal("0.10"), -3, new BigDecimal("0.05"));

    /** For a card whose subgrades are no lower than its overall grade; still more below than above. */
    static final Map<Integer, BigDecimal> DEFAULT_TIGHT_SPREAD = Map.of(
            1, new BigDecimal("0.10"), 0, new BigDecimal("0.60"), -1, new BigDecimal("0.25"), -2, new BigDecimal("0.05"));

    ConditionProperties {
        spread = spread == null || spread.isEmpty() ? DEFAULT_SPREAD : Map.copyOf(spread);
        tightSpread = tightSpread == null || tightSpread.isEmpty() ? DEFAULT_TIGHT_SPREAD : Map.copyOf(tightSpread);
        requireSumsToOne("spread", spread);
        requireSumsToOne("tight-spread", tightSpread);
        // Never more probability above the provider's grade than the default spread allows.
        if (above(tightSpread).compareTo(above(spread)) > 0) {
            throw new IllegalArgumentException("pokemans.condition.tight-spread puts " + above(tightSpread)
                    + " above the grade, more than spread's " + above(spread));
        }
        if (maxTenProbability == null || maxTenProbability.signum() < 0 || maxTenProbability.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("pokemans.condition.max-ten-probability must be in [0, 1], got " + maxTenProbability);
        }
        // Below 1: a floor of 1 would leave nothing for the grades.
        if (ungradableFloor == null || ungradableFloor.signum() < 0 || ungradableFloor.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("pokemans.condition.ungradable-floor must be in [0, 1), got " + ungradableFloor);
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

    /**
     * Off until the account's terms are confirmed in writing, AI training is opted out in its
     * settings, and a full-access key is set.
     *
     * @param gradingCompany  the label CardGrade prints on its certificate; it doesn't change the scale. PSA
     *                        matches the outcomes we collect, and {@code psaPrediction} is read either way
     * @param gradesPerMinute submits allowed per minute: the plan's limit (Pro 10, Business 30)
     * @param readsPerMinute  polls allowed per minute: the plan's read limit (Pro 50, Business 150)
     * @param outOfCreditsPause how long submits stop after CardGrade says the credits have run out, so
     *                        queued cards fail without each calling it
     */
    record CardGrade(@DefaultValue("false") boolean enabled,
                     String apiKey,
                     @DefaultValue("https://cardgrade.io") URI baseUrl,
                     @DefaultValue("PSA") String gradingCompany,
                     @DefaultValue("30s") Duration timeout,
                     @DefaultValue("10") int gradesPerMinute,
                     @DefaultValue("50") int readsPerMinute,
                     @DefaultValue("1h") Duration outOfCreditsPause) {

        /** Never prints the key. */
        @Override
        public String toString() {
            return "CardGrade[enabled=" + enabled + ", apiKey=" + redacted(apiKey) + ", baseUrl=" + baseUrl
                    + ", gradingCompany=" + gradingCompany + ", gradesPerMinute=" + gradesPerMinute
                    + ", readsPerMinute=" + readsPerMinute + "]";
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

    private static void requireSumsToOne(String name, Map<Integer, BigDecimal> weights) {
        BigDecimal sum = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("pokemans.condition." + name + " must sum to 1, got " + sum);
        }
    }

    private static BigDecimal above(Map<Integer, BigDecimal> weights) {
        return weights.entrySet().stream().filter(e -> e.getKey() > 0).map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String redacted(String secret) {
        return secret == null || secret.isBlank() ? "unset" : "***";
    }
}

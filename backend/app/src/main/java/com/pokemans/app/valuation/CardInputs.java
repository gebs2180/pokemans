package com.pokemans.app.valuation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradePrice;
import com.pokemans.valuation.RawFloors;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a valuation run used for one card item, as stored on the run (`card_valuation.inputs`).
 * The engine values a card from this alone, so a stored run can be replayed and its report rebuilt
 * without reading any other table.
 *
 * @param identity        null if the card isn't identified
 * @param assessment      null if there is no current assessment (or the card is graded and doesn't need one)
 * @param graded          null for a raw card
 * @param prices          the table the card was valued on (PSA for raw cards, the grader's own for graded), or null
 * @param copiesHeld      items in the run with the same catalog card and graded state: the depth discount's numerator
 * @param unvaluedReason  why the card couldn't be valued, or null
 */
public record CardInputs(
        UUID cardItemId,
        String label,
        boolean lot,
        int quantity,
        Identity identity,
        Assessment assessment,
        Graded graded,
        Prices prices,
        int copiesHeld,
        String setKey,
        String characterKey,
        ProvenanceState provenance,
        List<Photo> photos,
        String unvaluedReason) {

    /** @param dexIds National Pokedex numbers the catalog gave for the card; empty if unknown */
    public record Identity(UUID catalogCardId, String name, String setName, String number, String language,
                           LocalDate setReleaseDate, String edition, String variant, boolean promo, String authenticityRisk,
                           List<Integer> dexIds) {
    }

    /**
     * @param photoConfidence the assessment's photo confidence
     * @param oneProvider     a system assessment built from a single pre-grade provider: its A holds only up to
     *                        the parameter set's FMV threshold (Zach, 2026-11-10). True or null, never false, and
     *                        left out of the JSON while null: runs and offers stored before it read back, re-serialize
     *                        and hash exactly as they did. Read it with {@link #fromOneProvider()}.
     */
    public record Assessment(UUID assessmentId, int version, Map<Grade, BigDecimal> distribution, Confidence photoConfidence,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Boolean oneProvider) {

        public Assessment {
            oneProvider = Boolean.TRUE.equals(oneProvider) ? Boolean.TRUE : null;
        }

        public Assessment(UUID assessmentId, int version, Map<Grade, BigDecimal> distribution, Confidence photoConfidence) {
            this(assessmentId, version, distribution, photoConfidence, null);
        }

        /** True when the condition was built from a single pre-grade provider. */
        public boolean fromOneProvider() {
            return Boolean.TRUE.equals(oneProvider);
        }
    }

    /**
     * A returned grade whose cert was checked and matches.
     *
     * @param grade       as returned (half grades kept)
     * @param valuedGrade the grade it is valued at: half grades fold down
     */
    public record Graded(UUID outcomeId, String gradingCompany, BigDecimal grade, Grade valuedGrade, String certNumber) {
    }

    /**
     * A price table as pricing stored it: gross, before grading costs.
     *
     * @param sigma       monthly σ, or null when pricing had too few 30-day changes to measure one
     * @param staleLevels confidence levels the table lost to age on the run date
     */
    public record Prices(UUID snapshotSetId, String gradingCompany, LocalDate asOf, long daysOld, int staleLevels,
                         List<PriceRow> grades, BigDecimal sigma, int sigmaChanges, BigDecimal salesPerMonth) {
    }

    /**
     * @param n        sales in the window before trimming
     * @param rawFloor the grade's raw-sale floor (parameter set 1.8.0), or null for none. Left out of the JSON while
     *                 null: runs and offers stored before it read back, re-serialize and hash exactly as they did.
     */
    public record PriceRow(Grade grade, BigDecimal gross, GradePrice.Method method, int n, List<String> compIds, Integer windowDays,
                           List<Grade> basis, BigDecimal stepDownRatio, Confidence confidence,
                           @JsonInclude(JsonInclude.Include.NON_NULL) RawFloors.Floor rawFloor) {

        public PriceRow(Grade grade, BigDecimal gross, GradePrice.Method method, int n, List<String> compIds, Integer windowDays,
                        List<Grade> basis, BigDecimal stepDownRatio, Confidence confidence) {
            this(grade, gross, method, n, compIds, windowDays, basis, stepDownRatio, confidence, null);
        }
    }

    public record ProvenanceState(boolean authFlagUnresolved, String authState, boolean custodyVerified, String custodyState,
                                  Instant custodyVerifiedUntil) {
    }

    /** A passing photo, as pre-loss proof on the insurer report. */
    public record Photo(UUID photoId, String side, String storageKey, String cropKey, String contentType, Instant capturedAt,
                        Instant uploadedAt, String sha256) {
    }

    /** True for a card whose authenticity has been doubted: insured at $0 and not worth grading. */
    public boolean doubted() {
        return provenance != null && ("COUNTERFEIT".equals(provenance.authState()) || "SUSPECTED_COUNTERFEIT".equals(provenance.authState()));
    }
}

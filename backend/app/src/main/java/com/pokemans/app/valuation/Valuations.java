package com.pokemans.app.valuation;

import com.pokemans.valuation.GradingCost;
import com.pokemans.valuation.Trace;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * What other modules (reporting) may know about valuation runs: a stored run, read back exactly as
 * it was valued. No access checks: callers have done them.
 */
@Service
public class Valuations {

    /** A run's portfolio figures. α is a suggested range only: the lender sets it. */
    public record RunSummary(UUID id, UUID orgId, UUID collectionId, String collectionName, LocalDate asOf, String parameterSetVersion,
                             String gradingCompany, GradingCost gradingCost, String inputsSha256, int cardCount, int valuedCount,
                             int eligibleCount, BigDecimal eligibleFlv, BigDecimal collateralBase, BigDecimal rawShare,
                             BigDecimal gradedShare, BigDecimal alphaLow, BigDecimal alphaHigh, BigDecimal advanceLow,
                             BigDecimal advanceHigh, boolean capsApplied, int capsMinCards, BigDecimal totalRv, String note,
                             Instant createdAt) {
    }

    /**
     * One card's line.
     *
     * @param flvRawForLift  the raw FLV the grade-first lift compares against: at gross prices, so grading
     *                       cost counts once (it is above {@code flv}, which is built from net prices)
     * @param gradeFirstRank position on the grade-first list (1 to 20), or null
     */
    public record CardLine(UUID cardItemId, int position, String status, String unvaluedReason, boolean graded, CardInputs inputs,
                           BigDecimal fmv, BigDecimal rv, BigDecimal qsv, BigDecimal stressedFmv, BigDecimal flv, String confidence,
                           boolean eligible, List<String> exclusionReasons, BigDecimal contribution, BigDecimal insuredRv,
                           String insurerNote, BigDecimal flvGraded, BigDecimal flvRawForLift, BigDecimal gradingCost, BigDecimal lift, Integer gradeFirstRank,
                           List<Trace.Step> trace) {
    }

    /**
     * @param confidenceMix valued cards per confidence grade
     */
    public record RunDetail(RunSummary run, List<CardLine> cards, List<Trace.Step> portfolioTrace, Map<String, Long> confidenceMix) {

        /** The grade-first list, in rank order. */
        public List<CardLine> gradeFirst() {
            return cards.stream().filter(c -> c.gradeFirstRank() != null)
                    .sorted(java.util.Comparator.comparing(CardLine::gradeFirstRank)).toList();
        }
    }

    private final ValuationRunRepository runs;
    private final CardValuationRecordRepository lines;

    Valuations(ValuationRunRepository runs, CardValuationRecordRepository lines) {
        this.runs = runs;
        this.lines = lines;
    }

    @Transactional(readOnly = true)
    public RunDetail detail(UUID runId) {
        ValuationRun run = require(runId);
        List<CardLine> cards = lines.findByRunIdOrderByPositionAsc(runId).stream().map(Valuations::line).toList();
        Map<String, Long> mix = new TreeMap<>();
        cards.stream().filter(c -> c.confidence() != null).forEach(c -> mix.merge(c.confidence(), 1L, Long::sum));
        return new RunDetail(summary(run), cards, ValuationService.steps(run.getPortfolioTrace()), mix);
    }

    @Transactional(readOnly = true)
    public RunSummary summary(UUID runId) {
        return summary(require(runId));
    }

    @Transactional(readOnly = true)
    public List<RunSummary> forCollection(UUID orgId, UUID collectionId) {
        return runs.findByOrgIdAndCollectionIdOrderByCreatedAtDesc(orgId, collectionId).stream().map(Valuations::summary).toList();
    }

    ValuationRun require(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Valuation run not found"));
    }

    static RunSummary summary(ValuationRun r) {
        com.pokemans.valuation.ParameterSet params = ValuationService.JSON.readValue(r.getParameterSet(),
                com.pokemans.valuation.ParameterSet.class);
        return new RunSummary(r.getId(), r.getOrgId(), r.getCollectionId(), r.getCollectionName(), r.getAsOf(), r.getParameterSetVersion(),
                r.getGradingCompany(), ValuationService.JSON.readValue(r.getGradingCost(), GradingCost.class), r.getInputsSha256(),
                r.getCardCount(), r.getValuedCount(), r.getEligibleCount(), r.getEligibleFlv(), r.getCollateralBase(), r.getRawShare(),
                r.getGradedShare(), r.getAlphaLow(), r.getAlphaHigh(), r.getAdvanceLow(), r.getAdvanceHigh(), r.isCapsApplied(),
                params.capsMinCards(), r.getTotalRv(), r.getNote(), r.getCreatedAt());
    }

    private static CardLine line(CardValuationRecord l) {
        return new CardLine(l.getCardItemId(), l.getPosition(), l.getStatus().name(), l.getUnvaluedReason(), l.isGraded(),
                ValuationService.JSON.readValue(l.getInputs(), CardInputs.class), l.getFmv(), l.getRv(), l.getQsv(), l.getStressedFmv(),
                l.getFlv(), l.getConfidence(), l.isEligible(), l.getExclusionReasons(), l.getContribution(), l.getInsuredRv(),
                l.getInsurerNote(), l.getFlvGraded(), l.getFlvRawForLift(), l.getGradingCost(), l.getLift(), l.getGradeFirstRank(),
                ValuationService.steps(l.getTrace()));
    }
}

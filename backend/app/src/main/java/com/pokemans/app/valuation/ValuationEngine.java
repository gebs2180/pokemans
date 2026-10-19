package com.pokemans.app.valuation;

import com.pokemans.valuation.CardValuation;
import com.pokemans.valuation.CardValuator;
import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.ConfidencePenalty;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import com.pokemans.valuation.GradeFirstRanker;
import com.pokemans.valuation.GradePrice;
import com.pokemans.valuation.GradePriceTable;
import com.pokemans.valuation.GradingCost;
import com.pokemans.valuation.ParameterSet;
import com.pokemans.valuation.PortfolioEvaluator;
import com.pokemans.valuation.Trace;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Values a collection from its stored inputs alone: steps 4 to 6 of the methodology, all in
 * valuation-core. No Spring and no I/O, so a stored run replays to the same numbers.
 */
final class ValuationEngine {

    /**
     * One card's outcome.
     *
     * @param valuation      null for an unvalued card
     * @param contribution   null for an unvalued card
     * @param gradeFirst     null unless the card is a raw grade-first candidate
     * @param gradeFirstRank 1-based position on the recommended list, or null
     */
    record CardResult(CardInputs inputs, CardValuation valuation, PortfolioEvaluator.Contribution contribution,
                      BigDecimal insuredRv, String insurerNote, GradeFirstRanker.Recommendation gradeFirst, Integer gradeFirstRank,
                      List<Trace.Step> trace) {
    }

    record Result(List<CardResult> cards, PortfolioEvaluator.Result portfolio, BigDecimal totalRv, boolean capsApplied,
                  int eligibleCount) {
    }

    private static final BigDecimal NONE = BigDecimal.ZERO.setScale(2);

    private final ParameterSet params;
    private final GradingCost cost;

    ValuationEngine(ParameterSet params, GradingCost cost) {
        if (params.fallbackSigma() == null) {
            throw new IllegalArgumentException("Parameter set " + params.version() + " has no fallback sigma: runs need 1.1.0 or later");
        }
        this.params = params;
        this.cost = cost;
    }

    Result value(List<CardInputs> cards) {
        CardValuator valuator = new CardValuator(params);
        Map<UUID, CardValuation> valuations = new HashMap<>();
        Map<UUID, Trace> traces = new HashMap<>();
        List<PortfolioEvaluator.Card> portfolioCards = new ArrayList<>();
        List<GradeFirstRanker.Candidate> candidates = new ArrayList<>();

        for (CardInputs card : cards) {
            if (card.unvaluedReason() != null) {
                continue;
            }
            Trace trace = new Trace();
            GradePriceTable table = table(card, trace);
            CardValuator.Input in = input(card, table, trace);
            CardValuation v = valuator.value(in);
            trace.addAll(v.trace());
            valuations.put(card.cardItemId(), v);
            traces.put(card.cardItemId(), trace);
            portfolioCards.add(new PortfolioEvaluator.Card(v, card.setKey(), card.characterKey(), card.graded() != null,
                    card.provenance().authFlagUnresolved(), card.provenance().custodyVerified()));
            // Doubted cards aren't worth grading. Unverified ones are: a grader's slab verifies them.
            if (card.graded() == null && !card.doubted()) {
                candidates.add(new GradeFirstRanker.Candidate(in, cost));
            }
        }

        PortfolioEvaluator.Result portfolio = new PortfolioEvaluator(params).evaluate(portfolioCards, Optional.empty());
        Map<String, PortfolioEvaluator.Contribution> contributions = new HashMap<>();
        portfolio.cards().forEach(c -> contributions.put(c.cardId(), c));

        Map<String, GradeFirstRanker.Recommendation> scored = new HashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        for (GradeFirstRanker.Recommendation r : new GradeFirstRanker(params).score(candidates)) {
            scored.put(r.cardId(), r);
            if (r.recommended() && rank.size() < params.gradeFirstTopN()) {
                rank.put(r.cardId(), rank.size() + 1);
            }
        }

        List<CardResult> results = new ArrayList<>();
        BigDecimal totalRv = BigDecimal.ZERO;
        for (CardInputs card : cards) {
            String id = card.cardItemId().toString();
            CardValuation v = valuations.get(card.cardItemId());
            if (v == null) {
                results.add(new CardResult(card, null, null, NONE, "not covered: " + card.unvaluedReason(), null, null, List.of()));
                continue;
            }
            BigDecimal insured = card.doubted() ? NONE : v.rv();
            String note = card.doubted() ? "authenticity doubted (" + card.provenance().authState() + "): not insured"
                    : card.provenance().authFlagUnresolved() ? "authenticity not yet verified (" + card.provenance().authState() + ")"
                    : null;
            totalRv = totalRv.add(insured);
            Trace trace = traces.get(card.cardItemId());
            GradeFirstRanker.Recommendation gf = scored.get(id);
            if (gf != null) {
                trace.addAll(gf.trace());
            }
            results.add(new CardResult(card, v, contributions.get(id), insured, note, gf, rank.get(id), trace.steps()));
        }
        int eligible = (int) portfolio.cards().stream().filter(PortfolioEvaluator.Contribution::eligible).count();
        return new Result(results, portfolio, totalRv, eligible >= params.capsMinCards(), eligible);
    }

    /** Gross snapshot prices: raw cards are netted of grading cost; graded cards are already graded. */
    private GradePriceTable table(CardInputs card, Trace trace) {
        EnumMap<Grade, GradePrice> gross = new EnumMap<>(Grade.class);
        for (CardInputs.PriceRow row : card.prices().grades()) {
            int window = row.windowDays() == null ? 0 : row.windowDays();
            int used = row.method() == GradePrice.Method.OBSERVED ? row.compIds().size() : 0;
            gross.put(row.grade(), new GradePrice(row.grade(), row.gross(), row.gross(), row.method(), used, row.compIds(), false,
                    window, row.n(), row.basis(), row.confidence(), row.stepDownRatio()));
        }
        if (card.graded() != null) {
            trace.add("prices", card.prices().gradingCompany() + " table as of " + card.prices().asOf()
                    + ", gross: the card is already graded", card.prices().snapshotSetId(), "");
            return new GradePriceTable(gross, new Trace());
        }
        trace.add("prices", card.prices().gradingCompany() + " table as of " + card.prices().asOf() + ", netted of grading cost",
                card.prices().snapshotSetId(), "");
        GradePriceTable netted = GradePriceTable.netOf(gross.values(), cost);
        trace.addAll(netted.trace());
        return netted;
    }

    private CardValuator.Input input(CardInputs card, GradePriceTable table, Trace trace) {
        List<ConfidencePenalty> penalties = new ArrayList<>();
        if (card.prices().staleLevels() > 0) {
            penalties.add(new ConfidencePenalty("price table " + card.prices().daysOld() + " days old", card.prices().staleLevels()));
        }
        BigDecimal sigma = card.prices().sigma();
        if (sigma == null) {
            sigma = params.fallbackSigma();
            penalties.add(new ConfidencePenalty("fallback sigma " + sigma, 1));
            trace.add("sigma", "fallback: too few 30-day price changes to measure", sigma, card.prices().sigmaChanges() + " changes");
        }
        GradeDistribution distribution;
        Confidence photo;
        if (card.graded() != null) {
            distribution = GradeDistribution.certain(card.graded().valuedGrade());
            photo = Confidence.A; // the grade is known, so photo quality doesn't bear on it
        } else {
            distribution = GradeDistribution.of(card.assessment().distribution());
            photo = card.assessment().photoConfidence();
        }
        return new CardValuator.Input(card.cardItemId().toString(), distribution, table, sigma, card.prices().salesPerMonth(),
                card.copiesHeld(), photo, penalties, card.graded() != null);
    }
}

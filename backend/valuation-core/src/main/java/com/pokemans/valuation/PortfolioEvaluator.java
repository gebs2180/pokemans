package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Step 5: eligibility, concentration caps, correlation haircut and the collateral base.
 *
 * <p>Caps are measured against the eligible base before capping (one pass), so a small collection
 * is not driven to zero by its own caps. Order: single-card cap, top-five cap, then the correlation
 * haircut on the capped values. A card in an over-concentrated set and an over-concentrated
 * character takes both haircuts.
 *
 * <p>α is the lender's choice: the result suggests a range and computes an advance only for an α the
 * caller supplies.
 */
public final class PortfolioEvaluator {

    private final ParameterSet params;

    public PortfolioEvaluator(ParameterSet params) {
        this.params = Objects.requireNonNull(params);
    }

    /** One card in the collection with the facts eligibility and correlation need. */
    public record Card(
            CardValuation valuation,
            String setKey,
            String characterKey,
            boolean graded,
            boolean authFlagUnresolved,
            boolean custodyVerified) {
    }

    /** A card's path from FLV to its contribution to the base. */
    public record Contribution(
            String cardId,
            BigDecimal flv,
            boolean eligible,
            List<String> exclusionReasons,
            BigDecimal contribution) {
    }

    public record Result(
            List<Contribution> cards,
            BigDecimal eligibleFlv,
            BigDecimal collateralBase,
            BigDecimal rawShare,
            BigDecimal gradedShare,
            BigDecimal suggestedAdvanceLow,
            BigDecimal suggestedAdvanceHigh,
            Optional<BigDecimal> advance,
            Trace trace) {
    }

    public Result evaluate(List<Card> cards, Optional<BigDecimal> lenderAlpha) {
        Trace trace = new Trace();
        trace.add("parameters", "parameter set", params.version(), "");

        Map<String, List<String>> reasons = new LinkedHashMap<>();
        Map<String, BigDecimal> value = new LinkedHashMap<>();
        List<Card> eligible = new ArrayList<>();
        for (Card card : cards) {
            CardValuation v = card.valuation();
            List<String> why = new ArrayList<>();
            if (v.confidence() == Confidence.D) {
                why.add("confidence D");
            }
            if (card.authFlagUnresolved()) {
                why.add("unresolved authenticity flag");
            }
            if (!card.custodyVerified()) {
                why.add("custody not verified");
            }
            if (v.fmv().compareTo(params.minFmv()) < 0) {
                why.add("FMV below " + params.minFmv());
            }
            reasons.put(v.cardId(), why);
            if (why.isEmpty()) {
                eligible.add(card);
                value.put(v.cardId(), v.flv());
            } else {
                trace.add("excluded[" + v.cardId() + "]", "eligibility", BigDecimal.ZERO, String.join("; ", why));
            }
        }

        BigDecimal eligibleFlv = sum(value.values());
        trace.add("eligible_flv", "sum of eligible FLV", eligibleFlv, eligible.size() + " cards");

        // Single-card cap.
        BigDecimal singleCap = Money.cents(eligibleFlv.multiply(params.singleCardCap(), Money.MC));
        for (Map.Entry<String, BigDecimal> e : value.entrySet()) {
            if (e.getValue().compareTo(singleCap) > 0) {
                trace.add("capped[" + e.getKey() + "]", "single-card cap " + params.singleCardCap() + " of eligible FLV",
                        singleCap, "FLV " + e.getValue());
                e.setValue(singleCap);
            }
        }

        // Top-five cap.
        BigDecimal topFiveLimit = eligibleFlv.multiply(params.topFiveCap(), Money.MC);
        List<String> topFive = value.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
        BigDecimal topFiveSum = sum(topFive.stream().map(value::get).toList());
        if (topFiveSum.compareTo(topFiveLimit) > 0) {
            BigDecimal scale = topFiveLimit.divide(topFiveSum, Money.MC);
            for (String id : topFive) {
                value.put(id, Money.cents(value.get(id).multiply(scale, Money.MC)));
            }
            trace.add("top_five_cap", "top five scaled to " + params.topFiveCap() + " of eligible FLV",
                    Money.cents(topFiveLimit), "top five were " + topFiveSum + ", scale " + Money.cents(scale.movePointRight(2)) + "%");
        }

        // Correlation haircut by set and by character, shares measured on the capped base.
        BigDecimal capped = sum(value.values());
        Map<String, BigDecimal> haircut = new HashMap<>();
        applyCorrelation("set", eligible, Card::setKey, value, capped, haircut, trace);
        applyCorrelation("character", eligible, Card::characterKey, value, capped, haircut, trace);
        for (Map.Entry<String, BigDecimal> e : haircut.entrySet()) {
            value.put(e.getKey(), Money.cents(value.get(e.getKey()).multiply(e.getValue(), Money.MC)));
        }

        BigDecimal base = sum(value.values());
        trace.add("collateral_base", "sum of capped eligible FLV", base, "");

        BigDecimal graded = sum(eligible.stream().filter(Card::graded).map(c -> value.get(c.valuation().cardId())).toList());
        BigDecimal gradedShare = base.signum() == 0 ? BigDecimal.ZERO : graded.divide(base, Money.MC);
        BigDecimal rawShare = base.signum() == 0 ? BigDecimal.ZERO : BigDecimal.ONE.subtract(gradedShare);
        trace.add("raw_share", "raw share of base", rawShare.setScale(4, java.math.RoundingMode.HALF_EVEN), "graded " + graded);

        BigDecimal low = Money.cents(base.multiply(params.suggestedAlphaLow(), Money.MC));
        BigDecimal high = Money.cents(base.multiply(params.suggestedAlphaHigh(), Money.MC));
        trace.add("suggested_advance", "suggested alpha range " + params.suggestedAlphaLow() + "-" + params.suggestedAlphaHigh()
                + " (lender decides)", low + " to " + high, "");

        Optional<BigDecimal> advance = lenderAlpha.map(alpha -> {
            BigDecimal a = advance(base, alpha);
            trace.add("advance", "lender alpha x base", a, "alpha " + alpha);
            return a;
        });

        List<Contribution> contributions = new ArrayList<>();
        for (Card card : cards) {
            String id = card.valuation().cardId();
            List<String> why = reasons.get(id);
            contributions.add(new Contribution(id, card.valuation().flv(), why.isEmpty(), List.copyOf(why),
                    value.getOrDefault(id, BigDecimal.ZERO)));
        }
        return new Result(contributions, eligibleFlv, base, rawShare, gradedShare, low, high, advance, trace);
    }

    /** Advance = α · base, for an α the lender supplies. */
    public static BigDecimal advance(BigDecimal base, BigDecimal alpha) {
        if (alpha.signum() < 0 || alpha.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Alpha must be in [0, 1]: " + alpha);
        }
        return Money.cents(base.multiply(alpha, Money.MC));
    }

    private void applyCorrelation(String dimension, List<Card> eligible, Function<Card, String> key,
                                  Map<String, BigDecimal> value, BigDecimal cappedBase,
                                  Map<String, BigDecimal> haircut, Trace trace) {
        if (cappedBase.signum() == 0) {
            return;
        }
        Map<String, BigDecimal> slices = new LinkedHashMap<>();
        for (Card card : eligible) {
            String k = key.apply(card);
            if (k != null) {
                slices.merge(k, value.get(card.valuation().cardId()), BigDecimal::add);
            }
        }
        BigDecimal factor = BigDecimal.ONE.subtract(params.correlationHaircut());
        slices.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(slice -> {
                    BigDecimal share = slice.getValue().divide(cappedBase, Money.MC);
                    if (share.compareTo(params.correlationThreshold()) <= 0) {
                        return;
                    }
                    for (Card card : eligible) {
                        if (slice.getKey().equals(key.apply(card))) {
                            haircut.merge(card.valuation().cardId(), factor, (a, b) -> a.multiply(b, Money.MC));
                        }
                    }
                    trace.add("correlation[" + dimension + ":" + slice.getKey() + "]",
                            "slice above " + params.correlationThreshold() + " of base, " + params.correlationHaircut() + " off",
                            Money.cents(slice.getValue().multiply(params.correlationHaircut(), Money.MC)),
                            "share " + share.setScale(4, java.math.RoundingMode.HALF_EVEN));
                });
    }

    private static BigDecimal sum(Iterable<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            total = total.add(v);
        }
        return total;
    }
}

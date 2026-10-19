package com.pokemans.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * <p>Concentration caps are measured against the eligible FLV before caps, at every collection size
 * (Zach, 2026-10-19): every eligible card is capped at one level, the highest at which no card is
 * above 20% of the eligible FLV and the top five are at most 50% of it. There is no minimum card
 * count, so no cliff where one more card switches the caps on; a small pledge is held back by its
 * concentration instead: one card counts at most 20% of its FLV, and five or fewer at most 50% of
 * theirs. The correlation haircut then applies to the capped values.
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
            if (reasons.containsKey(v.cardId())) {
                throw new IllegalArgumentException("Duplicate card id " + v.cardId() + ": each copy needs its own id");
            }
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

        // Concentration caps, against the eligible FLV before caps: one cap level for every card, the highest at
        // which no card is above 20% of it and the top five are at most 50% of it.
        BigDecimal level = capLevel(value.values(), eligibleFlv);
        for (Map.Entry<String, BigDecimal> e : value.entrySet()) {
            if (e.getValue().compareTo(level) > 0) {
                trace.add("capped[" + e.getKey() + "]", "concentration cap level", level, "FLV " + e.getValue());
                e.setValue(level);
            }
        }
        trace.add("concentration_caps", "single card at most " + params.singleCardCap() + " and top five at most "
                + params.topFiveCap() + " of the eligible FLV " + eligibleFlv, level, "cap level per card");

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

    /**
     * The highest per-card cap level, in cents, at which capping every card at it keeps each card at most
     * the single-card share and the top five at most the top-five share of {@code eligibleFlv}, the total
     * before caps. Lowering the level only lowers the top five, so a binary search finds it.
     */
    private BigDecimal capLevel(java.util.Collection<BigDecimal> flvs, BigDecimal eligibleFlv) {
        List<BigDecimal> sorted = flvs.stream().sorted(Comparator.reverseOrder()).toList();
        if (sorted.isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal topFiveLimit = eligibleFlv.multiply(params.topFiveCap(), Money.MC);
        long lo = 0;
        long hi = cents(Money.min(sorted.getFirst(), eligibleFlv.multiply(params.singleCardCap(), Money.MC)));
        if (topFive(sorted, BigDecimal.valueOf(hi, 2)).compareTo(topFiveLimit) <= 0) {
            return BigDecimal.valueOf(hi, 2);
        }
        while (hi - lo > 1) {
            long mid = lo + (hi - lo) / 2;
            if (topFive(sorted, BigDecimal.valueOf(mid, 2)).compareTo(topFiveLimit) <= 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return BigDecimal.valueOf(lo, 2);
    }

    private static BigDecimal topFive(List<BigDecimal> sortedDesc, BigDecimal level) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < Math.min(5, sortedDesc.size()); i++) {
            total = total.add(sortedDesc.get(i).min(level));
        }
        return total;
    }

    private static long cents(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.DOWN).longValueExact();
    }

    private static BigDecimal sum(Iterable<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            total = total.add(v);
        }
        return total;
    }
}

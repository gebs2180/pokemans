package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Step 4: FMV, RV, QSV and FLV for one card, with a confidence grade.
 *
 * <pre>
 * FMV  = Σ p_g · P_g
 * RV   = FMV · (1 + b + s)                       central distribution
 * QSV  = FMV · (1 − f) · (1 − d)
 * FLV  = FMV↓ · (1 − f) · (1 − k·σ·√t) · (1 − ℓ)   FMV↓: shifted 2 grades down (3 at confidence C)
 * </pre>
 */
public final class CardValuator {

    private final ParameterSet params;

    public CardValuator(ParameterSet params) {
        this.params = Objects.requireNonNull(params);
    }

    /**
     * @param distribution      central condition distribution (after photo and surface adjustments)
     * @param prices            net price per grade
     * @param monthlyVolatility σ, monthly
     * @param salesPerMonth     market sales of this card per month
     * @param copiesHeld        copies in the collection that would need to sell
     * @param photoConfidence   confidence from photo quality
     * @param penalties         further confidence drops applied to the base confidence before the stress
     *                          is chosen, each with its reason (a stale price table, a fallback σ)
     * @param knownGrade        true for a graded card whose grade is known: its distribution is certain
     *                          and gets no condition stress shift
     */
    public record Input(
            String cardId,
            GradeDistribution distribution,
            GradePriceTable prices,
            BigDecimal monthlyVolatility,
            BigDecimal salesPerMonth,
            int copiesHeld,
            Confidence photoConfidence,
            List<ConfidencePenalty> penalties,
            boolean knownGrade) {

        /** A raw card with no confidence penalties. */
        public Input(String cardId, GradeDistribution distribution, GradePriceTable prices, BigDecimal monthlyVolatility,
                     BigDecimal salesPerMonth, int copiesHeld, Confidence photoConfidence) {
            this(cardId, distribution, prices, monthlyVolatility, salesPerMonth, copiesHeld, photoConfidence, List.of(), false);
        }

        public Input {
            penalties = List.copyOf(Objects.requireNonNull(penalties, "penalties (pass an empty list, not null)"));
            Objects.requireNonNull(cardId, "cardId");
            Objects.requireNonNull(distribution, "distribution");
            Objects.requireNonNull(prices, "prices");
            Objects.requireNonNull(monthlyVolatility, "monthlyVolatility");
            Objects.requireNonNull(salesPerMonth, "salesPerMonth");
            Objects.requireNonNull(photoConfidence, "photoConfidence");
            if (monthlyVolatility.signum() < 0) {
                throw new IllegalArgumentException("Monthly volatility can't be negative: " + monthlyVolatility);
            }
            // Zero is allowed: no recent sales takes the depth discount's cap.
            if (salesPerMonth.signum() < 0) {
                throw new IllegalArgumentException("Sales per month can't be negative: " + salesPerMonth);
            }
            if (copiesHeld < 1) {
                throw new IllegalArgumentException("Copies held must be at least 1: " + copiesHeld);
            }
        }
    }

    public CardValuation value(Input in) {
        Trace trace = new Trace();
        trace.add("parameters", "parameter set", params.version(), "");

        BigDecimal fmv = expectedValue(in.distribution(), in.prices());
        trace.add("FMV", "sum p x net price", Money.cents(fmv), describe(in.distribution(), in.prices()));

        Confidence confidence = confidence(in, trace);
        Confidence offerConfidence = offerConfidence(in, trace);

        ParameterSet.LiquidityTier tier = params.liquidityTier(in.salesPerMonth());
        BigDecimal s = params.searchPremium(tier);
        BigDecimal d = params.speedDiscount(tier);
        BigDecimal f = params.sellingFees();
        BigDecimal b = params.buyerCosts();

        BigDecimal rv = fmv.multiply(BigDecimal.ONE.add(b).add(s), Money.MC);
        trace.add("RV", "FMV x (1 + b + s)", Money.cents(rv),
                "b " + b + ", s " + s + " (" + tier + " liquidity, " + in.salesPerMonth() + " sales/mo)");

        BigDecimal qsv = fmv.multiply(BigDecimal.ONE.subtract(f), Money.MC)
                .multiply(BigDecimal.ONE.subtract(d), Money.MC);
        trace.add("QSV", "FMV x (1 - f) x (1 - d)", Money.cents(qsv), "f " + f + ", d " + d + " (" + tier + " liquidity)");

        int shift = in.knownGrade() ? 0 : stressShift(confidence);
        GradeDistribution stressed = in.distribution().shiftDown(shift);
        Map<Grade, BigDecimal> stressedPrices = neverUp(in.prices());
        BigDecimal stressedFmv = expectedValue(stressed, stressedPrices);
        trace.add("FMV_stressed", in.knownGrade()
                        ? "known grade: no condition stress; each grade priced at most the grade above"
                        : "distribution shifted " + shift + " grades down, floor 1, U held; each grade priced at most the grade above", Money.cents(stressedFmv), describe(stressed, stressedPrices));

        BigDecimal shock = volatilityShock(in.monthlyVolatility());
        trace.add("volatility_shock", "1 - k x sigma x sqrt(t)", shock,
                "k " + params.stressK() + ", sigma " + in.monthlyVolatility() + ", t " + params.stressMonths() + " months");

        BigDecimal depth = depthDiscount(in.copiesHeld(), in.salesPerMonth());
        trace.add("depth_discount", "copies / monthly sales, floor " + params.depthDiscountFloor() + ", cap "
                + params.depthDiscountCap(), depth, in.copiesHeld() + " copies, " + in.salesPerMonth() + " sales/mo");

        BigDecimal formula = stressedFmv.multiply(BigDecimal.ONE.subtract(f), Money.MC)
                .multiply(shock, Money.MC)
                .multiply(BigDecimal.ONE.subtract(depth), Money.MC);
        // A 60-day forced sale never recovers more than a 30-day quick sale (Zach, 2026-10-18). The 1.0.0
        // parameters keep the depth discount at or above d, so this only binds if a parameter set changes that.
        BigDecimal flv = formula.min(qsv);
        trace.add("FLV", "FMV_stressed x (1 - f) x shock x (1 - l), at most QSV", Money.cents(flv),
                "f " + f + (flv.compareTo(formula) < 0 ? "; formula gave " + Money.cents(formula) + ", capped at QSV" : ""));

        return new CardValuation(in.cardId(), Money.cents(fmv), Money.cents(rv), Money.cents(qsv),
                Money.cents(stressedFmv), Money.cents(flv), confidence, shift, tier, s, d, shock, depth, trace, offerConfidence);
    }

    /** FLV for the same card once its grade is known: no condition stress, gross graded prices. */
    BigDecimal flvAtKnownGrade(Input in, Grade grade) {
        BigDecimal price = in.prices().gross(grade);
        return price.multiply(BigDecimal.ONE.subtract(params.sellingFees()), Money.MC)
                .multiply(volatilityShock(in.monthlyVolatility()), Money.MC)
                .multiply(BigDecimal.ONE.subtract(depthDiscount(in.copiesHeld(), in.salesPerMonth())), Money.MC);
    }

    static BigDecimal expectedValue(GradeDistribution distribution, GradePriceTable prices) {
        return expectedValue(distribution, net(prices));
    }

    static BigDecimal expectedValue(GradeDistribution distribution, Map<Grade, BigDecimal> prices) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Grade grade : Grade.values()) {
            sum = sum.add(distribution.p(grade).multiply(prices.get(grade), Money.MC));
        }
        return sum;
    }

    private static Map<Grade, BigDecimal> net(GradePriceTable prices) {
        EnumMap<Grade, BigDecimal> net = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            net.put(grade, prices.net(grade));
        }
        return net;
    }

    /**
     * Net prices for the stressed value: each gradable grade at most the grade above it. Shifting a
     * distribution down must never raise its value, but some grades sell above the grades over them
     * (PSA 1s have their own collectors), and the stress pushes probability into grade 1. U is kept.
     */
    static Map<Grade, BigDecimal> neverUp(GradePriceTable prices) {
        EnumMap<Grade, BigDecimal> capped = new EnumMap<>(Grade.class);
        capped.put(Grade.U, prices.net(Grade.U));
        BigDecimal cap = null;
        for (int g = 10; g >= 1; g--) {
            Grade grade = Grade.of(g);
            BigDecimal price = prices.net(grade);
            cap = cap == null ? price : Money.min(cap, price);
            capped.put(grade, cap);
        }
        return capped;
    }

    BigDecimal volatilityShock(BigDecimal sigma) {
        BigDecimal sqrtT = params.stressMonths().sqrt(Money.MC);
        BigDecimal shock = BigDecimal.ONE.subtract(params.stressK().multiply(sigma, Money.MC).multiply(sqrtT, Money.MC));
        return Money.max(BigDecimal.ZERO, shock);
    }

    BigDecimal depthDiscount(int copiesHeld, BigDecimal salesPerMonth) {
        if (salesPerMonth.signum() <= 0) {
            return params.depthDiscountCap();
        }
        BigDecimal share = BigDecimal.valueOf(copiesHeld).divide(salesPerMonth, Money.MC);
        return Money.clamp(share, params.depthDiscountFloor(), params.depthDiscountCap());
    }

    /** Grades the FLV stress shifts the distribution down: more at confidence C (and D). */
    private int stressShift(Confidence confidence) {
        return confidence == Confidence.C || confidence == Confidence.D
                ? params.stressShiftGradesConfidenceC()
                : params.stressShiftGrades();
    }

    /**
     * Base confidence is the worse of photo confidence and comp confidence (probability-weighted count
     * of observed sales behind the prices). Any derived price that carries probability in the central
     * or stressed distribution drops it one level. The stress checked is the one the base confidence
     * gets (3 grades at C or D, else 2): a drop from B to C deepens the stress, but by then it has
     * already dropped, so checking the base's stress is enough.
     */
    private Confidence confidence(Input in, Trace trace) {
        BigDecimal weightedComps = weightedComps(in);
        Confidence fromComps = fromComps(weightedComps);
        Confidence base = in.photoConfidence().worse(fromComps);
        trace.add("confidence_base", "worse of photo and comps", base,
                "photo " + in.photoConfidence() + ", comps " + fromComps + " (weighted sales " + Money.cents(weightedComps) + ")");
        for (ConfidencePenalty penalty : in.penalties()) {
            if (penalty.levels() == 0) {
                continue;
            }
            base = Staleness.drop(base, penalty.levels());
            trace.add("confidence_penalty", penalty.reason() + ", drop " + penalty.levels(), base, "");
        }

        GradeDistribution stressed = in.distribution().shiftDown(in.knownGrade() ? 0 : stressShift(base));
        StringBuilder derived = new StringBuilder();
        for (Grade grade : Grade.values()) {
            boolean inPlay = in.distribution().p(grade).signum() > 0 || stressed.p(grade).signum() > 0;
            if (inPlay && in.prices().get(grade).derived()) {
                derived.append(derived.isEmpty() ? "" : ", ").append(grade);
            }
        }
        if (derived.isEmpty()) {
            trace.add("confidence", "no derived prices in play", base, "");
            return base;
        }
        Confidence dropped = base.drop();
        trace.add("confidence", "derived price in play, drop one level", dropped, "derived grades " + derived);
        return dropped;
    }

    /**
     * The confidence an instant offer is priced at (Zach, 2026-10-21). The offer uses the central
     * distribution and net prices, never σ or the stressed distribution, so it starts from the same
     * base (photo and comps) and takes the penalties that bear on prices (a stale table), but not a
     * fallback σ, and it drops a level for a derived price only where the central distribution puts
     * probability.
     */
    private Confidence offerConfidence(Input in, Trace trace) {
        Confidence c = in.photoConfidence().worse(fromComps(weightedComps(in)));
        for (ConfidencePenalty penalty : in.penalties()) {
            if (penalty.levels() == 0 || penalty.flvOnly()) {
                continue;
            }
            c = Staleness.drop(c, penalty.levels());
            trace.add("offer_confidence_penalty", penalty.reason() + ", drop " + penalty.levels(), c, "");
        }
        StringBuilder derived = new StringBuilder();
        for (Grade grade : Grade.values()) {
            if (in.distribution().p(grade).signum() > 0 && in.prices().get(grade).derived()) {
                derived.append(derived.isEmpty() ? "" : ", ").append(grade);
            }
        }
        if (derived.isEmpty()) {
            trace.add("offer_confidence", "base and price penalties; no derived price where the card is likely", c,
                    "FLV-only penalties (fallback sigma, the stress) left out");
            return c;
        }
        Confidence dropped = c.drop();
        trace.add("offer_confidence", "derived price where the card is likely, drop one level", dropped,
                "derived grades " + derived + "; FLV-only penalties (fallback sigma, the stress) left out");
        return dropped;
    }

    /** Probability-weighted count of the observed sales behind the prices. */
    private static BigDecimal weightedComps(Input in) {
        BigDecimal weighted = BigDecimal.ZERO;
        for (Grade grade : Grade.values()) {
            GradePrice price = in.prices().get(grade);
            if (price.method() == GradePrice.Method.OBSERVED) {
                weighted = weighted.add(in.distribution().p(grade).multiply(BigDecimal.valueOf(price.salesInWindow()), Money.MC));
            }
        }
        return weighted;
    }

    private Confidence fromComps(BigDecimal weightedComps) {
        if (weightedComps.compareTo(params.compsForA()) >= 0) {
            return Confidence.A;
        } else if (weightedComps.compareTo(params.compsForB()) >= 0) {
            return Confidence.B;
        } else if (weightedComps.compareTo(params.compsForC()) >= 0) {
            return Confidence.C;
        }
        return Confidence.D;
    }

    private static String describe(GradeDistribution distribution, GradePriceTable prices) {
        return describe(distribution, net(prices));
    }

    private static String describe(GradeDistribution distribution, Map<Grade, BigDecimal> prices) {
        StringBuilder sb = new StringBuilder();
        for (Grade grade : Grade.values()) {
            BigDecimal p = distribution.p(grade);
            if (p.signum() > 0) {
                sb.append(sb.isEmpty() ? "" : " + ").append(p.stripTrailingZeros().toPlainString())
                        .append(" x ").append(prices.get(grade)).append(" [").append(grade).append(']');
            }
        }
        return sb.toString();
    }
}

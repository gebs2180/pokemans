package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

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
        // A 60-day forced sale never recovers more than a 30-day quick sale. This binds when the stress
        // shock and depth discount together take off less than the quick-sale discount d.
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
     * of observed sales behind the prices; from 1.3.0 also no better than the count behind the modal
     * grade, see {@link #compsConfidence}). Any derived price that carries probability in the central
     * or stressed distribution drops it one level. The stress checked is the one the base confidence
     * gets (3 grades at C or D, else 2): a drop from B to C deepens the stress, but by then it has
     * already dropped, so checking the base's stress is enough.
     */
    private Confidence confidence(Input in, Trace trace) {
        BigDecimal weightedComps = weightedComps(in);
        Confidence fromComps = compsConfidence(in);
        Confidence base = in.photoConfidence().worse(fromComps);
        trace.add("confidence_base", "worse of photo and comps", base,
                "photo " + in.photoConfidence() + ", comps " + fromComps + " (weighted sales " + Money.cents(weightedComps)
                        + (params.usesModalGradeConfidence() ? modalDetail(in) : "") + ")");
        base = applyPenalties(base, in, false, "confidence_penalty", trace);

        GradeDistribution stressed = in.distribution().shiftDown(in.knownGrade() ? 0 : stressShift(base));
        String derived = derivedGrades(in, params, g -> in.distribution().p(g).signum() > 0 || stressed.p(g).signum() > 0);
        if (derived.isEmpty()) {
            trace.add("confidence", "no derived prices in play", base, "");
            return base;
        }
        Confidence dropped = base.drop();
        trace.add("confidence", "derived price in play, drop one level", dropped, "derived grades " + derived);
        return dropped;
    }

    /**
     * The confidence an instant offer is priced at. The offer uses the central
     * distribution and net prices, never σ or the stressed distribution, so it starts from the same
     * base (photo and comps) and takes the penalties that bear on prices (a stale table), but not a
     * fallback σ, and it drops a level for a derived price only where the central distribution puts
     * probability.
     */
    private Confidence offerConfidence(Input in, Trace trace) {
        Confidence c = in.photoConfidence().worse(compsConfidence(in));
        c = applyPenalties(c, in, true, "offer_confidence_penalty", trace);
        String derived = derivedGrades(in, params, g -> in.distribution().p(g).signum() > 0);
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

    /**
     * Confidence from comps: the probability-weighted count, and from 1.3.0 the worse of that and the count
     * behind the modal grade's price, so a deep neighbour can't hide a thin price where the card most
     * likely grades (review B5).
     */
    private Confidence compsConfidence(Input in) {
        Confidence weighted = params.fromCompCount(weightedComps(in));
        if (!params.usesModalGradeConfidence()) {
            return weighted;
        }
        return weighted.worse(params.fromCompCount(BigDecimal.valueOf(salesBehind(in.prices(), modalGrade(in)))));
    }

    /**
     * The gradable grade with the most probability. Ties go to the grade with fewer sales behind its
     * price, so a tie never hides the thinner one.
     */
    private static Grade modalGrade(Input in) {
        Grade modal = null;
        for (int g = 1; g <= 10; g++) {
            Grade grade = Grade.of(g);
            if (modal == null) {
                modal = grade;
                continue;
            }
            int cmp = in.distribution().p(grade).compareTo(in.distribution().p(modal));
            if (cmp > 0 || (cmp == 0 && salesBehind(in.prices(), grade) < salesBehind(in.prices(), modal))) {
                modal = grade;
            }
        }
        return modal;
    }

    /**
     * Sales behind one grade's price: its own count when observed (thin or not), else the thinnest of the
     * grades it was derived from; zero with no sales or no recorded basis.
     */
    private static int salesBehind(GradePriceTable prices, Grade grade) {
        GradePrice price = prices.get(grade);
        if (price.method() == GradePrice.Method.OBSERVED) {
            return price.salesInWindow();
        }
        if (!price.derived() || price.basis().isEmpty()) {
            return 0;
        }
        int thinnest = Integer.MAX_VALUE;
        for (Grade from : price.basis()) {
            thinnest = Math.min(thinnest, salesBehind(prices, from));
        }
        return thinnest;
    }

    private static String modalDetail(Input in) {
        Grade modal = modalGrade(in);
        return "; modal grade " + modal + " with " + salesBehind(in.prices(), modal) + " sales behind its price";
    }

    /** Probability-weighted count of the observed sales behind the prices; from 1.3.0, thin grades don't count. */
    private BigDecimal weightedComps(Input in) {
        BigDecimal weighted = BigDecimal.ZERO;
        for (Grade grade : Grade.values()) {
            GradePrice price = in.prices().get(grade);
            if (price.method() == GradePrice.Method.OBSERVED && !params.isThin(price)) {
                weighted = weighted.add(in.distribution().p(grade).multiply(BigDecimal.valueOf(price.salesInWindow()), Money.MC));
            }
        }
        return weighted;
    }

    /** Drops the confidence for each penalty, leaving out those that bear only on FLV when pricing an offer. */
    private static Confidence applyPenalties(Confidence c, Input in, boolean offer, String step, Trace trace) {
        for (ConfidencePenalty penalty : in.penalties()) {
            if (penalty.levels() == 0 || (offer && penalty.flvOnly())) {
                continue;
            }
            c = Staleness.drop(c, penalty.levels());
            trace.add(step, penalty.reason() + ", drop " + penalty.levels(), c, "");
        }
        return c;
    }

    /**
     * The grades in play whose price is derived, as "G9, G10", or empty if none. From 1.3.0 a thin observed
     * price counts, marked "(thin)".
     */
    private static String derivedGrades(Input in, ParameterSet params, Predicate<Grade> inPlay) {
        StringBuilder derived = new StringBuilder();
        for (Grade grade : Grade.values()) {
            GradePrice price = in.prices().get(grade);
            boolean thin = params.isThin(price);
            if (inPlay.test(grade) && (price.derived() || thin)) {
                derived.append(derived.isEmpty() ? "" : ", ").append(grade).append(thin ? " (thin)" : "");
            }
        }
        return derived.toString();
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

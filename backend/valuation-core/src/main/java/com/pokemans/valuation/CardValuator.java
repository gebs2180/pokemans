package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.EnumMap;
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
     */
    public record Input(
            String cardId,
            GradeDistribution distribution,
            GradePriceTable prices,
            BigDecimal monthlyVolatility,
            BigDecimal salesPerMonth,
            int copiesHeld,
            Confidence photoConfidence) {
        public Input {
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

        int shift = stressShift(confidence);
        GradeDistribution stressed = in.distribution().shiftDown(shift);
        Map<Grade, BigDecimal> stressedPrices = neverUp(in.prices());
        BigDecimal stressedFmv = expectedValue(stressed, stressedPrices);
        trace.add("FMV_stressed", "distribution shifted " + shift + " grades down, floor 1, U held; each grade priced"
                        + " at most the grade above", Money.cents(stressedFmv), describe(stressed, stressedPrices));

        BigDecimal shock = volatilityShock(in.monthlyVolatility());
        trace.add("volatility_shock", "1 - k x sigma x sqrt(t)", shock,
                "k " + params.stressK() + ", sigma " + in.monthlyVolatility() + ", t " + params.stressMonths() + " months");

        BigDecimal depth = depthDiscount(in.copiesHeld(), in.salesPerMonth());
        trace.add("depth_discount", "copies / monthly sales, floor " + params.depthDiscountFloor() + ", cap "
                + params.depthDiscountCap(), depth, in.copiesHeld() + " copies, " + in.salesPerMonth() + " sales/mo");

        BigDecimal flv = stressedFmv.multiply(BigDecimal.ONE.subtract(f), Money.MC)
                .multiply(shock, Money.MC)
                .multiply(BigDecimal.ONE.subtract(depth), Money.MC);
        trace.add("FLV", "FMV_stressed x (1 - f) x shock x (1 - l)", Money.cents(flv), "f " + f);

        return new CardValuation(in.cardId(), Money.cents(fmv), Money.cents(rv), Money.cents(qsv),
                Money.cents(stressedFmv), Money.cents(flv), confidence, shift, tier, s, d, shock, depth, trace);
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
        BigDecimal weightedComps = BigDecimal.ZERO;
        for (Grade grade : Grade.values()) {
            GradePrice price = in.prices().get(grade);
            if (price.method() == GradePrice.Method.OBSERVED) {
                weightedComps = weightedComps.add(in.distribution().p(grade).multiply(BigDecimal.valueOf(price.salesInWindow()), Money.MC));
            }
        }
        Confidence fromComps;
        if (weightedComps.compareTo(params.compsForA()) >= 0) {
            fromComps = Confidence.A;
        } else if (weightedComps.compareTo(params.compsForB()) >= 0) {
            fromComps = Confidence.B;
        } else if (weightedComps.compareTo(params.compsForC()) >= 0) {
            fromComps = Confidence.C;
        } else {
            fromComps = Confidence.D;
        }
        Confidence base = in.photoConfidence().worse(fromComps);
        trace.add("confidence_base", "worse of photo and comps", base,
                "photo " + in.photoConfidence() + ", comps " + fromComps + " (weighted sales " + Money.cents(weightedComps) + ")");

        GradeDistribution stressed = in.distribution().shiftDown(stressShift(base));
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

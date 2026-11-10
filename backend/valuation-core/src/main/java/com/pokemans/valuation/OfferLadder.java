package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An instant offer on a raw card: one firm price, the expected value over its condition distribution
 * of what a buyer could pay for it at each grade. The buyer accepts it or doesn't;
 * no grade is settled afterwards, so the buyer keeps the upside of a 10 and carries the downside.
 *
 * <pre>
 * offer_g = P↓_g · (1 − f) · (1 − d) · (1 − m)
 * price   = Σ p_g · offer_g
 * </pre>
 *
 * P↓_g is the grade's net price (net of grading cost, as in FMV), capped at the grade above so a
 * grade 1 premium never pays more for a worse card; (1 − f)(1 − d) is the quick-sale haircut a buyer
 * faces reselling within 30 days; m is the buyer's margin. The rungs are kept to show how the price was
 * reached.
 *
 * <p>From parameter set 1.4.0 the 10 rung is at most M times the 9 rung, {@code offer_10 = min(offer_10,
 * M · offer_9)}: a PSA 10 often sells for several times a 9, and a modest P(10) would otherwise lift the
 * offer far above what a sure 9 earns (Zach, 2026-11-09). A 9 rung of zero leaves the 10 alone. This is the
 * offer's limit only: FMV, RV, QSV, FLV and the grade-first list keep the full ladder.
 */
public final class OfferLadder {

    /** One rung: what a buyer could pay for the card if it is {@code grade}, and how likely that is. */
    public record Rung(Grade grade, BigDecimal probability, BigDecimal price) {
    }

    /**
     * The 10 rung's cap, when it bound.
     *
     * @param uncapped what the 10 rung would have paid
     * @param cap      M x the 9 rung, which the 10 rung pays instead
     * @param multiple M, the parameter set's most a 10 pays as a multiple of a 9
     */
    public record TenCap(BigDecimal uncapped, BigDecimal cap, BigDecimal multiple) {
    }

    /**
     * @param price  the firm price: Σ p · rung price, in dollars, rounded to cents
     * @param tenCap the 10 rung's cap when it bound, else null
     */
    public record Offer(List<Rung> rungs, BigDecimal price, ParameterSet.LiquidityTier liquidityTier, Trace trace, TenCap tenCap) {
    }

    private final ParameterSet params;
    private final BigDecimal buyerMargin;

    /** @param buyerMargin share of the quick-sale value kept by the buyer, in [0, 1) */
    public OfferLadder(ParameterSet params, BigDecimal buyerMargin) {
        this.params = Objects.requireNonNull(params);
        Objects.requireNonNull(buyerMargin, "buyerMargin");
        if (buyerMargin.signum() < 0 || buyerMargin.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("Buyer margin must be in [0, 1): " + buyerMargin);
        }
        this.buyerMargin = buyerMargin;
    }

    /**
     * @param distribution  the card's condition distribution (certain at its grade for a graded card)
     * @param prices        net prices per grade (gross for a graded card, which has no grading cost to come)
     * @param salesPerMonth market sales of the card per month, for the speed discount
     */
    public Offer offer(GradeDistribution distribution, GradePriceTable prices, BigDecimal salesPerMonth) {
        Objects.requireNonNull(distribution, "distribution");
        Objects.requireNonNull(prices, "prices");
        Objects.requireNonNull(salesPerMonth, "salesPerMonth");
        Trace trace = new Trace();
        ParameterSet.LiquidityTier tier = params.liquidityTier(salesPerMonth);
        BigDecimal f = params.sellingFees();
        BigDecimal d = params.speedDiscount(tier);
        BigDecimal factor = BigDecimal.ONE.subtract(f).multiply(BigDecimal.ONE.subtract(d), Money.MC)
                .multiply(BigDecimal.ONE.subtract(buyerMargin), Money.MC);
        trace.add("offer_factor", "(1 - f) x (1 - d) x (1 - m)", factor,
                "f " + f + ", d " + d + " (" + tier + " liquidity, " + salesPerMonth + " sales/mo), m " + buyerMargin);

        Map<Grade, BigDecimal> capped = CardValuator.neverUp(prices);
        Map<Grade, BigDecimal> rungPrices = new java.util.EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            rungPrices.put(grade, Money.cents(capped.get(grade).multiply(factor, Money.MC)));
        }
        TenCap tenCap = capTen(rungPrices);
        List<Rung> rungs = new ArrayList<>();
        BigDecimal expected = BigDecimal.ZERO;
        for (int g = 10; g >= 0; g--) {
            Grade grade = Grade.of(g);
            BigDecimal price = rungPrices.get(grade);
            BigDecimal p = distribution.p(grade);
            rungs.add(new Rung(grade, p, price));
            expected = expected.add(p.multiply(price, Money.MC));
            trace.add("offer[" + grade + "]", "net price at most the grade above, x factor", price, "net " + capped.get(grade));
            if (grade == Grade.G10 && tenCap != null) {
                trace.add("offer_ten_cap", "10 rung at most M x the 9 rung (offers only)", tenCap.cap(),
                        "M " + tenCap.multiple() + "; uncapped 10 rung " + tenCap.uncapped() + ", 9 rung " + rungPrices.get(Grade.G9));
            }
        }
        expected = Money.cents(expected);
        trace.add("firm_price", "sum p x offer at each grade", expected, "one price: no grade is settled after acceptance");
        return new Offer(List.copyOf(rungs), expected, tier, trace, tenCap);
    }

    /**
     * Caps the 10 rung at M x the 9 rung in place, from 1.4.0. Null when the set has no cap, the 9 rung is
     * zero (no price to measure the 10 against), or the 10 is already within it.
     */
    private TenCap capTen(Map<Grade, BigDecimal> rungPrices) {
        BigDecimal multiple = params.offerTenToNineCap().orElse(null);
        BigDecimal nine = rungPrices.get(Grade.G9);
        if (multiple == null || nine.signum() <= 0) {
            return null;
        }
        if (multiple.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalStateException("Parameter set " + params.version() + ": a 10 must pay at least a 9, M " + multiple);
        }
        BigDecimal ten = rungPrices.get(Grade.G10);
        BigDecimal cap = Money.cents(nine.multiply(multiple, Money.MC));
        if (ten.compareTo(cap) <= 0) {
            return null;
        }
        rungPrices.put(Grade.G10, cap);
        return new TenCap(ten, cap, multiple);
    }
}

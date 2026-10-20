package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An instant offer on a raw card: one firm price, the expected value over its condition distribution
 * of what a buyer could pay for it at each grade (Zach, 2026-10-19). The buyer accepts it or doesn't;
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
 */
public final class OfferLadder {

    /** One rung: what a buyer could pay for the card if it is {@code grade}, and how likely that is. */
    public record Rung(Grade grade, BigDecimal probability, BigDecimal price) {
    }

    /** @param price the firm price: Σ p · rung price, in cents */
    public record Offer(List<Rung> rungs, BigDecimal price, ParameterSet.LiquidityTier liquidityTier, Trace trace) {
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
        List<Rung> rungs = new ArrayList<>();
        BigDecimal expected = BigDecimal.ZERO;
        for (int g = 10; g >= 0; g--) {
            Grade grade = Grade.of(g);
            BigDecimal price = Money.cents(capped.get(grade).multiply(factor, Money.MC));
            BigDecimal p = distribution.p(grade);
            rungs.add(new Rung(grade, p, price));
            expected = expected.add(p.multiply(price, Money.MC));
            trace.add("offer[" + grade + "]", "net price at most the grade above, x factor", price, "net " + capped.get(grade));
        }
        expected = Money.cents(expected);
        trace.add("firm_price", "sum p x offer at each grade", expected, "one price: no grade is settled after acceptance");
        return new Offer(List.copyOf(rungs), expected, tier, trace);
    }
}

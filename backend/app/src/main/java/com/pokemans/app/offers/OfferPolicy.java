package com.pokemans.app.offers;

import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.ParameterSet;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The numbers the offer terms are written around, from where they are set, so copy shown to people never drifts from what
 * the code does (review finding 47).
 */
@Component
class OfferPolicy {

    /**
     * @param minFmv          the smallest FMV that gets an offer, in dollars
     * @param maxPrice        the highest firm price we offer, in dollars
     * @param offerValidDays  how long an offer stands
     * @param listingOpenDays how long a posted card waits for a buyer
     * @param shipWithinDays  how long the seller has to ship once the label is bought
     * @param payWithinDays   how long the buyer has to pay once the card arrives
     * @param margins         the buyer's margin by confidence, A to C, as a share; empty before parameter set 1.2.0
     */
    record Terms(BigDecimal minFmv, BigDecimal maxPrice, long offerValidDays, long listingOpenDays, long shipWithinDays,
                 long payWithinDays, Map<String, BigDecimal> margins) {
    }

    private final Terms terms;

    OfferPolicy(ParameterSet params, OffersProperties properties) {
        this.terms = new Terms(params.minFmv().stripTrailingZeros(), properties.maxPrice().stripTrailingZeros(),
                properties.offerValidFor().toDays(), properties.listingOpenFor().toDays(),
                ListingStateMachine.SHIP_WITHIN.toDays(), ListingStateMachine.PAY_WITHIN.toDays(),
                margins(params));
    }

    /** Empty for a parameter set from before offers had margins (1.2.0), which can't make offers anyway. */
    private static Map<String, BigDecimal> margins(ParameterSet params) {
        try {
            return Map.of("A", params.offerMargin(Confidence.A), "B", params.offerMargin(Confidence.B),
                    "C", params.offerMargin(Confidence.C));
        } catch (IllegalStateException noMargins) {
            return Map.of();
        }
    }

    Terms terms() {
        return terms;
    }

    /** "$25": the minimum, in whole dollars, for copy. */
    String minFmvText() {
        return "$" + terms.minFmv().toPlainString();
    }
}

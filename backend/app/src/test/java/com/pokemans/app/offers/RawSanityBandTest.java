package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.valuation.OfferLadder;
import com.pokemans.valuation.ParameterSet;
import com.pokemans.valuation.Trace;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The lowball flag compares the firm price with what the offer's own formula would pay for a card certain to be worth
 * the lowest raw near-mint sale, not with the sale itself (decision sheet item 12, Zach 2026-11-18).
 */
class RawSanityBandTest {

    private static final ParameterSet PARAMS = ParameterSet.load("1.8.0");

    private static OfferLadder.Offer offer(String price, ParameterSet.LiquidityTier tier) {
        return new OfferLadder.Offer(List.of(), new BigDecimal(price), tier, new Trace(), null);
    }

    @Test
    void theBenchmarkIsTheLowestSaleThroughTheOffersOwnFactor() {
        // f 15%, d 10% (mid), m 13%: 100 x 0.85 x 0.90 x 0.87 = 66.555 -> 66.56
        assertThat(OfferService.netLowest(new BigDecimal("100"), PARAMS, offer("50", ParameterSet.LiquidityTier.MID), new BigDecimal("0.13")))
                .isEqualByComparingTo("66.56");
        // f 15%, d 20% (low), m 10%: 100 x 0.85 x 0.80 x 0.90 = 61.20
        assertThat(OfferService.netLowest(new BigDecimal("100"), PARAMS, offer("50", ParameterSet.LiquidityTier.LOW), new BigDecimal("0.10")))
                .isEqualByComparingTo("61.20");
    }

    @Test
    void anOfferUnderTheRawSaleButAtOrAboveTheBenchmarkIsNotFlagged() {
        CardOffer.RawCheck raw = new CardOffer.RawCheck(new BigDecimal("100"), 3, LocalDate.EPOCH, new BigDecimal("66.56"));

        assertThat(raw.flags(new BigDecimal("90"))).isFalse(); // under the $100 sale, as every offer is by its haircut
        assertThat(raw.flags(new BigDecimal("66.56"))).isFalse();
        assertThat(raw.flags(new BigDecimal("66.55"))).isTrue();
    }

    @Test
    void noRawSaleNoBenchmarkNoFlag() {
        assertThat(OfferService.netLowest(null, PARAMS, offer("50", ParameterSet.LiquidityTier.MID), new BigDecimal("0.10"))).isNull();
        assertThat(new CardOffer.RawCheck(null, 0, LocalDate.EPOCH, null).flags(new BigDecimal("1"))).isFalse();
    }
}

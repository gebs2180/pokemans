package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** B3's threshold: more than 10% either way has moved; exactly 10% still holds. */
class RepriceThresholdTest {

    private static final BigDecimal TEN = new BigDecimal("0.10");

    private static boolean moved(String was, String now) {
        return OfferService.moved(new BigDecimal(was), new BigDecimal(now), TEN);
    }

    @Test
    void withinTenPercentEitherWayHolds() {
        assertThat(moved("100.00", "100.00")).isFalse();
        assertThat(moved("100.00", "109.99")).isFalse();
        assertThat(moved("100.00", "90.01")).isFalse();
    }

    @Test
    void exactlyTenPercentHolds() {
        assertThat(moved("100.00", "110.00")).isFalse();
        assertThat(moved("100.00", "90.00")).isFalse();
    }

    @Test
    void moreThanTenPercentEitherWayMoved() {
        assertThat(moved("100.00", "110.01")).isTrue();
        assertThat(moved("100.00", "89.99")).isTrue();
        assertThat(moved("250.00", "500.00")).isTrue();
    }

    @Test
    void aZeroPriceHoldsOnlyAtZero() {
        assertThat(moved("0", "0.00")).isFalse();
        assertThat(moved("0", "0.01")).isTrue();
    }
}

package com.pokemans.app.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Stored words read back to the code that wrote them (architecture review #27). */
class UnvaluedReasonTest {

    @Test
    void readsBackEveryReasonItWrites() {
        for (UnvaluedReason r : UnvaluedReason.values()) {
            if (r != UnvaluedReason.NO_PRICE_TABLE && r != UnvaluedReason.OTHER) {
                assertThat(UnvaluedReason.of(r.text())).isEqualTo(r);
            }
        }
        assertThat(UnvaluedReason.of(UnvaluedReason.noPriceTable("PSA"))).isEqualTo(UnvaluedReason.NO_PRICE_TABLE);
        assertThat(UnvaluedReason.of(null)).isNull();
    }

    @Test
    void wordsItDidntWriteAreNeverMistakenForAKnownReason() {
        assertThat(UnvaluedReason.of("something new")).isEqualTo(UnvaluedReason.OTHER);
        assertThat(UnvaluedReason.of("no  price table")).isEqualTo(UnvaluedReason.OTHER);
        assertThat(UnvaluedReason.of("Not identified")).isEqualTo(UnvaluedReason.OTHER);
    }
}

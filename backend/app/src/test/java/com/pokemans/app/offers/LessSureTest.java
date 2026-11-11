package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Why an offer is less than very sure, read from its stored trace (Zach, 2026-11-10: the one-provider value gate). */
class LessSureTest {

    private static CardOffer offer(String trace) {
        return new CardOffer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false,
                "{}", "1.6.0", new BigDecimal("0.13"), "{}", new BigDecimal("900"), new BigDecimal("1001"), "B", false, trace,
                UUID.randomUUID(), Instant.EPOCH, Instant.EPOCH, new CardOffer.RawCheck(null, 0, null));
    }

    @Test
    void aOneProviderACappedByTheCardsValueSaysSoInsteadOfBlamingThePhotos() {
        String trace = """
                [{"subject":"photo_confidence_value_gate","rule":"one pre-grade provider and FMV above the threshold for A: capped at B","detail":"FMV 1001.00, threshold 1000.00"},
                 {"subject":"confidence_base","rule":"worse of photo and comps","detail":"photo B, comps A (weighted sales 20.00)"}]""";

        assertThat(ListingViews.lessSureBecause(offer(trace))).containsExactly("HIGH_VALUE");
    }

    @Test
    void photosThatLeaveTheConditionLessCertainStillSayPhotos() {
        String trace = """
                [{"subject":"confidence_base","rule":"worse of photo and comps","detail":"photo B, comps C (weighted sales 3.00)"}]""";

        assertThat(ListingViews.lessSureBecause(offer(trace))).containsExactly("PHOTOS", "FEW_SALES");
    }
}

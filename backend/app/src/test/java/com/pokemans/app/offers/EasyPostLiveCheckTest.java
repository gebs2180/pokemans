package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The one thing the mock can't show: that real EasyPost finds a bought shipment by its reference, so a retry after a
 * lost answer gets the same label (audit of 2026-11-02, finding 2). Skipped unless EASYPOST_TEST_KEY is set, and only a
 * test key is accepted, so it buys no real postage.
 */
@EnabledIfEnvironmentVariable(named = "EASYPOST_TEST_KEY", matches = "EZTK.+")
class EasyPostLiveCheckTest {

    private static final ShippingProvider.Address FROM = new ShippingProvider.Address("EasyPost", null, "417 Montgomery Street", "Floor 5",
            "San Francisco", "CA", "94104", "US", "4153334445");
    private static final ShippingProvider.Address TO = new ShippingProvider.Address("Dr. Steve Brule", null, "179 N Harbor Dr", null,
            "Redondo Beach", "CA", "90277", "US", "3103808888");
    private static final ShippingProvider.Parcel PARCEL = new ShippingProvider.Parcel(new BigDecimal("7"), new BigDecimal("5"),
            BigDecimal.ONE, new BigDecimal("3"));

    @Test
    void aRetryGetsTheLabelAlreadyBought() {
        // The constructor production uses, so its HTTP client is the one checked.
        ShippingProperties properties = new ShippingProperties(new ShippingProperties.EasyPost(true, System.getenv("EASYPOST_TEST_KEY"),
                URI.create("https://api.easypost.com/v2"), "USPS", Duration.ofSeconds(20)), new ShippingProperties.Parcel(PARCEL.length(),
                PARCEL.width(), PARCEL.height(), PARCEL.weightOz()));
        EasyPostShippingProvider easyPost = new EasyPostShippingProvider(properties);
        String reference = UUID.randomUUID().toString();

        ShippingProvider.Label first = easyPost.buy(FROM, TO, PARCEL, new BigDecimal("100.00"), reference);
        ShippingProvider.Label retry = easyPost.buy(FROM, TO, PARCEL, new BigDecimal("100.00"), reference);

        assertThat(first.shipmentId()).isNotBlank();
        assertThat(first.carrier()).isEqualTo("USPS");
        assertThat(first.trackingCode()).isNotBlank();
        assertThat(first.labelUrl()).isNotBlank();
        assertThat(first.postage()).isPositive();
        assertThat(first.insuranceCost()).isPositive();
        assertThat(retry).isEqualTo(first);
    }
}

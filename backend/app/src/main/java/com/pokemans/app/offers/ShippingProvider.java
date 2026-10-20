package com.pokemans.app.offers;

import java.math.BigDecimal;

/**
 * Buys prepaid shipping labels on our account (HLD provider ports). Labels are billed to the buyer
 * each month, off-platform (Zach, 2026-10-19). With no provider configured, a taken card waits for its
 * label and the admin buys one once a provider is set up.
 */
interface ShippingProvider {

    record Address(String name, String company, String street1, String street2, String city, String state, String zip, String country,
                   String phone) {
    }

    /** Inches and ounces. */
    record Parcel(BigDecimal length, BigDecimal width, BigDecimal height, BigDecimal weightOz) {
    }

    /**
     * @param postage       what the label cost
     * @param insuranceCost what insuring it for the declared value cost
     */
    record Label(String provider, String shipmentId, String carrier, String service, String trackingCode, String labelUrl,
                 BigDecimal postage, BigDecimal insuranceCost) {
    }

    String name();

    /**
     * Buys the cheapest label on the preferred carrier, insured for {@code insuredValue}.
     *
     * @param reference our listing id, printed on the label and kept by the provider
     */
    Label buy(Address from, Address to, Parcel parcel, BigDecimal insuredValue, String reference);
}

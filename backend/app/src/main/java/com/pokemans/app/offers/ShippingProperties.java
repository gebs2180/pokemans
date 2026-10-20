package com.pokemans.app.offers;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Shipping labels for taken cards.
 *
 * @param parcel the package a single raw card ships in: a bubble mailer with the card in a toploader
 */
@ConfigurationProperties("pokemans.shipping")
record ShippingProperties(@DefaultValue EasyPost easypost, @DefaultValue Parcel parcel) {

    /**
     * <a href="https://docs.easypost.com">EasyPost</a>. Off until a key is set in the environment; a test
     * key buys test labels.
     *
     * @param carrier the carrier whose cheapest rate is bought; the cheapest of any carrier if it has none
     */
    record EasyPost(@DefaultValue("false") boolean enabled, String apiKey,
                    @DefaultValue("https://api.easypost.com/v2") URI baseUrl,
                    @DefaultValue("USPS") String carrier,
                    @DefaultValue("20s") Duration timeout) {

        /** Never prints the key. */
        @Override
        public String toString() {
            return "EasyPost[enabled=" + enabled + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "***")
                    + ", baseUrl=" + baseUrl + ", carrier=" + carrier + "]";
        }
    }

    /** Inches and ounces. */
    record Parcel(@DefaultValue("7") BigDecimal length, @DefaultValue("5") BigDecimal width, @DefaultValue("1") BigDecimal height,
                  @DefaultValue("3") BigDecimal weightOz) {
    }
}

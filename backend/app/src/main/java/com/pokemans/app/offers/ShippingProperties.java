package com.pokemans.app.offers;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Shipping labels for taken cards.
 *
 * @param parcel   the package a single raw card ships in: a bubble mailer with the card in a toploader
 * @param estimate what the buyer is told a label will cost before accepting, where no live quote can be had
 */
@ConfigurationProperties("pokemans.shipping")
record ShippingProperties(@DefaultValue EasyPost easypost, @DefaultValue Parcel parcel, @DefaultValue Estimate estimate) {

    /**
     * <a href="https://docs.easypost.com">EasyPost</a>. Off until a key is set in the environment; a test
     * key buys test labels.
     *
     * @param carrier        the carrier whose cheapest rate is bought; the cheapest of any carrier if it has none
     * @param connectTimeout how long to wait for a connection: short, since nothing is sent before it
     * @param timeout        how long to wait for each answer. A label is bought with the card's listing lock and a database
     *                       connection held (deep review 2026-11-09, A4), so this bounds that hold. A purchase whose answer
     *                       times out is found by its reference on the retry, never bought twice.
     */
    record EasyPost(@DefaultValue("false") boolean enabled, String apiKey,
                    @DefaultValue("https://api.easypost.com/v2") URI baseUrl,
                    @DefaultValue("USPS") String carrier,
                    @DefaultValue("5s") Duration connectTimeout,
                    @DefaultValue("20s") Duration timeout) {

        /** Never prints the key. */
        @Override
        public String toString() {
            return "EasyPost[enabled=" + enabled + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "***")
                    + ", baseUrl=" + baseUrl + ", carrier=" + carrier + ", connectTimeout=" + connectTimeout + ", timeout=" + timeout + "]";
        }
    }

    /** Inches and ounces. */
    record Parcel(@DefaultValue("7") BigDecimal length, @DefaultValue("5") BigDecimal width, @DefaultValue("1") BigDecimal height,
                  @DefaultValue("3") BigDecimal weightOz) {
    }

    /**
     * The buyer's estimate before accepting (UX review finding 8). Postage comes from a live quote when a provider is set
     * up, else {@code postage}. Insurance is never quoted, so it is always estimated here, on the high side: EasyPost
     * lists 0.5% with a $0.50 minimum, other sources 1% with a $1 minimum.
     *
     * @param postage          the postage to show with no provider, or when its quote fails
     * @param insuranceRate    insurance as a share of the insured value (the price)
     * @param insuranceMinimum the least insurance costs on a label
     */
    record Estimate(@DefaultValue("6.00") BigDecimal postage, @DefaultValue("0.01") BigDecimal insuranceRate,
                    @DefaultValue("1.00") BigDecimal insuranceMinimum) {
    }
}

package com.pokemans.app.offers;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Instant offers (Zach, 2026-10-19).
 *
 * <p>The buyer's margin is in the parameter set, by the card's confidence (1.2.0 on).
 *
 * @param offerValidFor  how long an offer stands before it is priced again (prices move)
 * @param listingOpenFor how long a posted offer waits for a buyer before it expires
 * @param maxPrice       the highest firm price we offer: a card priced above it gets no offer. It is
 *                       EasyPost's $5,000 insurance limit, so every label can be insured for the full
 *                       price. The $500 beta maximum (2026-10-21) was lifted on 2026-10-26 (Zach): too
 *                       many cards were above it. Set it lower to cap the beta again
 */
@ConfigurationProperties("pokemans.offers")
record OffersProperties(@DefaultValue("7d") Duration offerValidFor,
                        @DefaultValue("14d") Duration listingOpenFor,
                        @DefaultValue("5000") BigDecimal maxPrice) {
}

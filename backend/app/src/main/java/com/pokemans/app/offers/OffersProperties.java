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
 * @param maxPrice       the highest firm price we offer (Zach, 2026-10-21): a card priced above it gets no
 *                       offer. Caps what one mistake can cost a buyer in the beta, and keeps every label
 *                       under EasyPost's $5,000 insurance limit
 */
@ConfigurationProperties("pokemans.offers")
record OffersProperties(@DefaultValue("7d") Duration offerValidFor,
                        @DefaultValue("14d") Duration listingOpenFor,
                        @DefaultValue("500") BigDecimal maxPrice) {
}

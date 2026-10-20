package com.pokemans.app.offers;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Instant offers (Zach, 2026-10-19).
 *
 * @param buyerMargin    share of the quick-sale value left to the buyer as margin
 * @param offerValidFor  how long an offer stands before it is priced again (prices move)
 * @param listingOpenFor how long a posted offer waits for a buyer before it expires
 */
@ConfigurationProperties("pokemans.offers")
record OffersProperties(@DefaultValue("0.10") BigDecimal buyerMargin,
                        @DefaultValue("7d") Duration offerValidFor,
                        @DefaultValue("14d") Duration listingOpenFor) {
}

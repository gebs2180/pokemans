package com.pokemans.app.catalog;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Card catalog providers. */
@ConfigurationProperties("pokemans.catalog")
record CatalogProperties(@DefaultValue Tcgdex tcgdex, @DefaultValue Reader reader) {

    /**
     * @param enabled turn off to work with manual entries only (offline, or the API is down)
     * @param baseUrl API root, without the language segment
     */
    record Tcgdex(@DefaultValue("true") boolean enabled,
                  @DefaultValue("https://api.tcgdex.net/v2") URI baseUrl,
                  @DefaultValue("10s") Duration timeout) {
    }

    /**
     * Reads name and number off the front photo to suggest an identity (Claude vision).
     *
     * @param enabled      off unless an API key is set
     * @param model        Claude model used to read the card
     * @param pollInterval how often the background job looks for cards to read
     * @param maxAttempts  give up on a card after this many failed calls
     */
    record Reader(@DefaultValue("false") boolean enabled,
                  String apiKey,
                  @DefaultValue("claude-sonnet-5-5") String model,
                  @DefaultValue("https://api.anthropic.com") URI baseUrl,
                  @DefaultValue("60s") Duration timeout,
                  @DefaultValue("true") boolean workerEnabled,
                  @DefaultValue("15s") Duration pollInterval,
                  @DefaultValue("5") int maxAttempts) {
    }
}

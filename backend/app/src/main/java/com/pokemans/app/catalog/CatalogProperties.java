package com.pokemans.app.catalog;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Card catalog providers. */
@ConfigurationProperties("pokemans.catalog")
record CatalogProperties(@DefaultValue Tcgdex tcgdex) {

    /**
     * @param enabled turn off to work with manual entries only (offline, or the API is down)
     * @param baseUrl API root, without the language segment
     */
    record Tcgdex(@DefaultValue("true") boolean enabled,
                  @DefaultValue("https://api.tcgdex.net/v2") URI baseUrl,
                  @DefaultValue("10s") Duration timeout) {
    }
}

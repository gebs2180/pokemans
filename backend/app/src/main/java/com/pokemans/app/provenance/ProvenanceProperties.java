package com.pokemans.app.provenance;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Provenance rules.
 *
 * @param custodyCheckValidFor how long a custody check counts before the card has to be checked again
 */
@ConfigurationProperties("pokemans.provenance")
record ProvenanceProperties(@DefaultValue("90d") Duration custodyCheckValidFor) {
}

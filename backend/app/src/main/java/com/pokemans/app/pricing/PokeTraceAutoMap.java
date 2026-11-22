package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maps a newly identified card to PokeTrace without the admin, then pulls its sales and reprices, as the admin's
 * "Pull sales now" does (docs/offers.md "Automatic gates"; a default to confirm in the methodology doc, 2026-11-22).
 * Only when the search found exactly one candidate that agrees with the card on everything
 * {@link PokeTraceMappings#disagreements} checks (number, set, edition, finish, language; any stamp disagrees), and
 * PokeTrace's card still agrees when fetched again. No candidate, several, or any disagreement: nothing happens, and
 * the card waits on the admin's "needs mapping" queue. Off unless {@code pokemans.pricing.poketrace.auto-map.enabled}.
 */
@Service
class PokeTraceAutoMap {

    private static final Logger log = LoggerFactory.getLogger(PokeTraceAutoMap.class);

    /** Stored on every automatic mapping. Change it whenever the rule changes. */
    static final String RULE_VERSION = "auto-map-1 (2026-11-22)";

    private final PricingProperties properties;
    private final PokeTraceMappings mappings;
    private final PricingRefresh refresh;
    private final SoldCompRepository comps;

    PokeTraceAutoMap(PricingProperties properties, PokeTraceMappings mappings, PricingRefresh refresh, SoldCompRepository comps) {
        this.properties = properties;
        this.mappings = mappings;
        this.refresh = refresh;
        this.comps = comps;
    }

    boolean enabled() {
        return properties.poketrace().autoMap().enabled();
    }

    /**
     * Maps the card to its one clean candidate and pulls its sales. Never maps a card that has ever been mapped.
     *
     * @return the pull, or empty when the card wasn't mapped
     */
    Optional<PricingRefresh.Pull> map(PokeTraceApi api, Catalog.CardSummary card, String candidateId) {
        if (mappings.current(card.catalogCardId()).isPresent()) {
            return Optional.empty();
        }
        Map<String, Object> detail = api.card(candidateId);
        if (detail.isEmpty() || !candidateId.equals(String.valueOf(detail.get("id")))) {
            log.info("PokeTrace has no card {} now; {} waits for the admin", candidateId, card.catalogCardId());
            return Optional.empty();
        }
        try {
            mappings.mapAutomatically(card, candidateId, detail, RULE_VERSION);
        } catch (IllegalArgumentException e) {
            log.info("Not mapping {} to PokeTrace {} automatically: {}", card.catalogCardId(), candidateId, e.getMessage());
            return Optional.empty();
        }
        log.info("Mapped {} to PokeTrace {} automatically ({}); pulling its sales", card.catalogCardId(), candidateId, RULE_VERSION);
        PricingRefresh.Pull pull = refresh.pullOne(new PokeTraceSource(api, mappings, comps, properties), card,
                LocalDate.now(ZoneOffset.UTC));
        return Optional.of(pull);
    }
}

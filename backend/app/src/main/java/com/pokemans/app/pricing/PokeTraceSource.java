package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * PokeTrace Pro as a live comp source: each mapped card's PSA and raw daily history, one comp per
 * sale day ({@link PokeTraceDays}). Only cards an admin mapped are asked for ({@link PokeTraceMappings}).
 *
 * <p>Licence: PokeTrace's written permission of 2026-11-07 covers pricing offers from the data,
 * showing derived prices, ranges and sale counts to logged-in users, keeping the data after the
 * subscription and training our own models on it; not reselling the data or offering a competing
 * data API (docs/price-data-sources.md).
 *
 * <p>A card's first pull asks for a year of history, later ones for the last 30 days. Today's row is
 * left out until the day is over, so a day is stored once, complete. Other graders' tiers aren't
 * pulled: a raw card is valued on PSA's table only.
 */
@Component
@ConditionalOnBooleanProperty("pokemans.pricing.poketrace.enabled")
class PokeTraceSource implements PriceSource {

    static final String NAME = "POKETRACE";
    static final String LICENCE = "PokeTrace Pro statistical data, written permission 2026-11-07 (docs/price-data-sources.md)";

    private static final Pattern PSA = Pattern.compile("PSA_\\d+(?:[._]5)?");

    private final PokeTraceApi api;
    private final PokeTraceMappings mappings;
    private final SoldCompRepository comps;
    private final PricingProperties.PokeTrace config;

    PokeTraceSource(PokeTraceApi api, PokeTraceMappings mappings, SoldCompRepository comps, PricingProperties properties) {
        this.api = api;
        this.mappings = mappings;
        this.comps = comps;
        this.config = properties.poketrace();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String licence() {
        return LICENCE;
    }

    /** PokeTrace can send a different average, low or high for a day it sent before. */
    @Override
    public boolean revisesSales() {
        return true;
    }

    @Override
    public List<Sale> soldSales(Catalog.CardSummary card, LocalDate from, LocalDate to) {
        Optional<String> mapped = mappings.poketraceId(card.catalogCardId());
        if (mapped.isEmpty()) {
            return List.of();
        }
        String id = mapped.get();
        Map<String, Object> detail = api.card(id);
        String currency = detail.get("currency") == null ? "USD" : detail.get("currency").toString();
        String period = comps.existsBySourceAndExternalSaleIdStartingWith(NAME, id + "|") ? config.recent() : config.backfill();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Sale> sales = new ArrayList<>();
        for (String tier : tiers(detail)) {
            for (Map<String, Object> row : api.history(id, tier, period)) {
                PokeTraceDays.sale(NAME, card.catalogCardId(), id, tier, row, currency)
                        .filter(s -> !s.soldDate().isBefore(from) && !s.soldDate().isAfter(to) && s.soldDate().isBefore(today))
                        .ifPresent(sales::add);
            }
        }
        return sales;
    }

    /** PSA grades and raw conditions the card has at PokeTrace. */
    static List<String> tiers(Map<String, Object> detail) {
        List<String> tiers = new ArrayList<>();
        if (detail.get("gradedOptions") instanceof List<?> graded) {
            graded.stream().map(String::valueOf).filter(t -> PSA.matcher(t).matches()).forEach(tiers::add);
        }
        if (detail.get("conditionOptions") instanceof List<?> conditions) {
            conditions.stream().map(String::valueOf).filter(t -> PokeTraceDays.tier(t).map(x -> x.condition() != null).orElse(false))
                    .forEach(tiers::add);
        }
        return tiers;
    }
}

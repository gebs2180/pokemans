package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only price source serving canned sales. It returns every sale it holds for a card, ignoring
 * the date range, so tests can check that ingestion itself rejects bad sales.
 */
class FixturePriceSource implements PriceSource {

    static final String NAME = "FIXTURE";

    private final Map<UUID, List<Sale>> sales = new ConcurrentHashMap<>();
    private int nextId;
    /** Runs at the start of every fetch, e.g. to hold a refresh open while a test looks at it. */
    private volatile Runnable onFetch = () -> { };

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String licence() {
        return "test fixture";
    }

    @Override
    public List<Sale> soldSales(Catalog.CardSummary card, LocalDate from, LocalDate to) {
        onFetch.run();
        return List.copyOf(sales.getOrDefault(card.catalogCardId(), List.of()));
    }

    void clear() {
        sales.clear();
        onFetch = () -> { };
    }

    void onFetch(Runnable hook) {
        onFetch = hook;
    }

    /** Adds a graded sale and returns it. */
    Sale graded(UUID card, GradingCompany company, String grade, String price, LocalDate soldOn) {
        return add(new Sale(NAME, "s" + nextId++, card, company, new BigDecimal(grade), null, new BigDecimal(price), "USD",
                soldOn, URI.create("https://example.test/sale"), ListingType.AUCTION, Map.of("fixture", true)));
    }

    /** Adds a raw sale and returns it. */
    Sale raw(UUID card, RawCondition condition, String price, LocalDate soldOn) {
        return add(new Sale(NAME, "s" + nextId++, card, null, null, condition, new BigDecimal(price), "USD", soldOn, null,
                ListingType.FIXED_PRICE, Map.of()));
    }

    Sale add(Sale sale) {
        sales.computeIfAbsent(sale.catalogCardId(), k -> new ArrayList<>()).add(sale);
        return sale;
    }
}

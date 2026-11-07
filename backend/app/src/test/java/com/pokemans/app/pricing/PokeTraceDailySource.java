package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only price source for the PokeTrace Pro pilot (docs/poketrace-pilot.md): reads the responses
 * {@code analysis/poketrace/poketrace.py} saved, never the API. PokeTrace Pro has no individual sales,
 * only one row per card, tier and day, so each day with a sale becomes one comp at the day's average,
 * the same rule as the live {@link PokeTraceSource} ({@link PokeTraceDays}). The day's sale count is
 * noised by PokeTrace and never used as a count.
 *
 * <p>Calibration only: it feeds the Testcontainers database of {@code OfferSanityRunner}.
 *
 * <p>Layout: {@code <root>/pulls/<date>/cards/<poketrace id>/history/<TIER>/*.json}, each file
 * {@code {"request", "fetchedAt", "response": {"data": [rows]}}}. A later pull's row for the same tier,
 * source and day replaces an earlier one (late-reported sales).
 */
class PokeTraceDailySource implements PriceSource {

    static final String NAME = "POKETRACE_DAILY";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path root;
    private final Map<UUID, String> poketraceIds;

    /** @param poketraceIds PokeTrace card id per catalog card */
    PokeTraceDailySource(Path root, Map<UUID, String> poketraceIds) {
        this.root = root;
        this.poketraceIds = Map.copyOf(poketraceIds);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String licence() {
        return "PokeTrace Pro daily aggregates, calibration only (docs/poketrace-pilot.md)";
    }

    @Override
    public List<Sale> soldSales(Catalog.CardSummary card, LocalDate from, LocalDate to) {
        String id = poketraceIds.get(card.catalogCardId());
        if (id == null) {
            return List.of();
        }
        String currency = currency(id);
        List<Sale> sales = new ArrayList<>();
        rows(id).forEach((key, row) -> {
            LocalDate day = LocalDate.parse(key.date().substring(0, 10));
            if (day.isBefore(from) || day.isAfter(to)) {
                return;
            }
            PokeTraceDays.sale(NAME, card.catalogCardId(), id, key.tier(), row, currency).ifPresent(sales::add);
        });
        return sales;
    }

    /** One tier's daily row, by tier, source and day. */
    record Key(String tier, String source, String date) implements Comparable<Key> {
        @Override
        public int compareTo(Key o) {
            int c = tier.compareTo(o.tier);
            c = c != 0 ? c : source.compareTo(o.source);
            return c != 0 ? c : date.compareTo(o.date);
        }
    }

    /** Every pull's rows for the card, a later pull winning. */
    Map<Key, Map<String, Object>> rows(String poketraceId) {
        Map<Key, Map<String, Object>> rows = new TreeMap<>();
        for (Path pull : sorted(root.resolve("pulls"))) {
            Path history = pull.resolve("cards").resolve(poketraceId).resolve("history");
            for (Path tierDir : sorted(history)) {
                for (Path page : sorted(tierDir)) {
                    if (!page.toString().endsWith(".json")) {
                        continue;
                    }
                    Object data = response(page) instanceof Map<?, ?> r ? r.get("data") : null;
                    if (!(data instanceof List<?> list)) {
                        continue;
                    }
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> row && row.get("date") != null && row.get("source") != null) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> r = (Map<String, Object>) row;
                            rows.put(new Key(tierDir.getFileName().toString(), r.get("source").toString(), r.get("date").toString()), r);
                        }
                    }
                }
            }
        }
        return rows;
    }

    /** The card's currency from its latest detail response; PokeTrace's US market is USD. */
    private String currency(String poketraceId) {
        String currency = "USD";
        for (Path pull : sorted(root.resolve("pulls"))) {
            Path detail = pull.resolve("cards").resolve(poketraceId).resolve("detail.json");
            if (Files.exists(detail) && response(detail) instanceof Map<?, ?> r && r.get("data") instanceof Map<?, ?> d
                    && d.get("currency") != null) {
                currency = d.get("currency").toString();
            }
        }
        return currency;
    }

    private static Object response(Path file) {
        try {
            Map<String, Object> saved = JSON.readValue(Files.readString(file), new TypeReference<Map<String, Object>>() { });
            return saved.get("response");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> sorted(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}

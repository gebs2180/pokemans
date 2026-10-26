package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only price source for the PokeTrace Pro pilot (docs/poketrace-pilot.md): reads the responses
 * {@code analysis/poketrace/poketrace.py} saved, never the API. PokeTrace Pro has no individual sales,
 * only one row per card, tier and day with the day's sale count, low, high and average, so each row
 * becomes that many sales: one sale at the average; two at the low and the high; more at the low, the
 * high and the rest at the average. The count is exact, the range is exact and the mean is close.
 *
 * <p>Calibration only. Pricing live offers from daily aggregates is a methodology decision (step 3:
 * "every price traces to sold comps") and needs PokeTrace's written licence, so this stays in test
 * scope and feeds only the Testcontainers database of {@code OfferSanityRunner}.
 *
 * <p>Layout: {@code <root>/pulls/<date>/cards/<poketrace id>/history/<TIER>/*.json}, each file
 * {@code {"request", "fetchedAt", "response": {"data": [rows]}}}. A later pull's row for the same tier,
 * source and day replaces an earlier one (late-reported sales).
 */
class PokeTraceDailySource implements PriceSource {

    static final String NAME = "POKETRACE_DAILY";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern GRADED = Pattern.compile("([A-Z]+)_(\\d+)(?:[._](5))?");
    /** eBay carries every tier; TCGplayer only raw. cardmarket is EUR and cardmarket_unsold is listings: never. */
    private static final Set<String> GRADED_SOURCES = Set.of("ebay");
    private static final Set<String> RAW_SOURCES = Set.of("ebay", "tcgplayer");

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
            sales.addAll(expand(card.catalogCardId(), id, key, row, day, currency));
        });
        return sales;
    }

    /** The day's row as sales, or none if the tier or source isn't one we price from. */
    static List<Sale> expand(UUID catalogCardId, String poketraceId, Key key, Map<String, Object> row, LocalDate day, String currency) {
        int n = number(row.get("saleCount")) == null ? 0 : number(row.get("saleCount")).intValue();
        BigDecimal avg = money(row.get("avg"));
        if (n < 1 || avg == null || avg.signum() <= 0) {
            return List.of();
        }
        GradingCompany company = null;
        BigDecimal grade = null;
        RawCondition condition = null;
        Matcher m = GRADED.matcher(key.tier());
        if (m.matches() && isCompany(m.group(1))) {
            if (!GRADED_SOURCES.contains(key.source())) {
                return List.of();
            }
            company = GradingCompany.valueOf(m.group(1));
            grade = new BigDecimal(m.group(2) + (m.group(3) == null ? "" : ".5"));
            if (grade.compareTo(BigDecimal.ONE) < 0 || grade.compareTo(BigDecimal.TEN) > 0) {
                return List.of();
            }
        } else if (isRawCondition(key.tier()) && RAW_SOURCES.contains(key.source())) {
            condition = RawCondition.valueOf(key.tier());
        } else {
            return List.of();
        }
        BigDecimal low = money(row.get("low"));
        BigDecimal high = money(row.get("high"));
        List<BigDecimal> prices = new ArrayList<>();
        if (n == 1 || low == null || high == null || low.signum() <= 0) {
            for (int i = 0; i < n; i++) {
                prices.add(avg);
            }
        } else {
            prices.add(low);
            prices.add(high);
            for (int i = 2; i < n; i++) {
                prices.add(avg);
            }
        }
        Map<String, Object> raw = new LinkedHashMap<>(row);
        raw.put("tier", key.tier());
        raw.put("expansion", n == 1 ? "one sale at the average" : n == 2 ? "low and high" : "low, high and " + (n - 2) + " at the average");
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < prices.size(); i++) {
            sales.add(new Sale(NAME, poketraceId + "|" + key.tier() + "|" + key.source() + "|" + day + "|" + i, catalogCardId, company,
                    grade, condition, prices.get(i), currency, day, null, ListingType.UNKNOWN, raw));
        }
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

    private static Number number(Object o) {
        return o instanceof Number n ? n : null;
    }

    private static BigDecimal money(Object o) {
        return o instanceof Number n ? new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_EVEN) : null;
    }

    private static boolean isCompany(String s) {
        return Stream.of(GradingCompany.values()).anyMatch(c -> c.name().equals(s));
    }

    /** ALTERED is ours, not a PokeTrace tier. */
    private static boolean isRawCondition(String s) {
        return Stream.of(RawCondition.values()).anyMatch(c -> c != RawCondition.ALTERED && c.name().equals(s));
    }
}

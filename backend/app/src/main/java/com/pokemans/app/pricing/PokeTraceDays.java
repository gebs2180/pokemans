package com.pokemans.app.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * How a PokeTrace daily row becomes a comp: <b>one comp per sale day</b>, at the day's average
 * (Zach, 2026-11-07). PokeTrace Pro has no individual sales, only a row per card, tier, source and
 * day, and eBay's {@code saleCount} is deliberately noisy: a positive count is moved by −2 to +4
 * (minimum 1) and zero stays zero (PokeTrace, 2026-11-07). So the count is never used as a number of
 * sales. A day with a sale is certain, so the number of sale days is a lower bound on the sales and
 * can't be inflated: comp counts, confidence, the 180-day extension, sales per month and trimming
 * all work on sale days.
 *
 * <p>Graded tiers come from eBay only; raw tiers from eBay or TCGplayer. Never Cardmarket (EUR) or
 * {@code cardmarket_unsold} (listings, not sales). A day that both eBay and TCGplayer report is two
 * comps: each source had at least one sale, so it is still a lower bound.
 */
final class PokeTraceDays {

    static final String RULE = "one comp per sale day, at the day's average";

    private static final Pattern GRADED = Pattern.compile("([A-Z]+)_(\\d+)(?:[._](5))?");
    private static final Set<String> GRADED_SOURCES = Set.of("ebay");
    private static final Set<String> RAW_SOURCES = Set.of("ebay", "tcgplayer");

    private PokeTraceDays() {
    }

    /** What a PokeTrace tier is in our terms: a grading company and grade, or a raw condition. */
    record Tier(PriceSource.GradingCompany company, BigDecimal grade, PriceSource.RawCondition condition) {
    }

    /** The tier, or empty if it isn't one we price from (sealed, an unknown grader, a grade outside 1 to 10). */
    static Optional<Tier> tier(String tier) {
        Matcher m = GRADED.matcher(tier);
        if (m.matches()) {
            if (!isCompany(m.group(1))) {
                return Optional.empty();
            }
            BigDecimal grade = new BigDecimal(m.group(2) + (m.group(3) == null ? "" : ".5"));
            if (grade.compareTo(BigDecimal.ONE) < 0 || grade.compareTo(BigDecimal.TEN) > 0) {
                return Optional.empty();
            }
            return Optional.of(new Tier(PriceSource.GradingCompany.valueOf(m.group(1)), grade, null));
        }
        if (isRawCondition(tier)) {
            return Optional.of(new Tier(null, null, PriceSource.RawCondition.valueOf(tier)));
        }
        return Optional.empty();
    }

    /**
     * The day's row as one comp, or empty if the day had no sale or the tier or source isn't one we price from.
     *
     * @param source the comp source name stored on the sale
     */
    static Optional<PriceSource.Sale> sale(String source, UUID catalogCardId, String poketraceId, String tierName,
                                           Map<String, Object> row, String currency) {
        Object rowSource = row.get("source");
        Object date = row.get("date");
        if (rowSource == null || date == null) {
            return Optional.empty();
        }
        Number count = row.get("saleCount") instanceof Number n ? n : null;
        BigDecimal avg = money(row.get("avg"));
        // Zero stays zero in PokeTrace's noise, so a positive count means the day had a sale.
        if (count == null || count.doubleValue() <= 0 || avg == null || avg.signum() <= 0) {
            return Optional.empty();
        }
        Optional<Tier> tier = tier(tierName);
        if (tier.isEmpty()) {
            return Optional.empty();
        }
        Set<String> allowed = tier.get().company() != null ? GRADED_SOURCES : RAW_SOURCES;
        if (!allowed.contains(rowSource.toString())) {
            return Optional.empty();
        }
        LocalDate day = LocalDate.parse(date.toString().substring(0, 10));
        Map<String, Object> raw = new LinkedHashMap<>(row);
        raw.values().removeIf(Objects::isNull);
        raw.put("tier", tierName);
        raw.put("rule", RULE);
        return Optional.of(new PriceSource.Sale(source, poketraceId + "|" + tierName + "|" + rowSource + "|" + day, catalogCardId,
                tier.get().company(), tier.get().grade(), tier.get().condition(), avg, currency, day, null,
                PriceSource.ListingType.UNKNOWN, raw));
    }

    private static BigDecimal money(Object o) {
        return o instanceof Number n ? new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_EVEN) : null;
    }

    private static boolean isCompany(String s) {
        return Stream.of(PriceSource.GradingCompany.values()).anyMatch(c -> c.name().equals(s));
    }

    /** ALTERED is ours, not a PokeTrace tier. */
    private static boolean isRawCondition(String s) {
        return Stream.of(PriceSource.RawCondition.values()).anyMatch(c -> c != PriceSource.RawCondition.ALTERED && c.name().equals(s));
    }
}

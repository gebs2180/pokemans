package com.pokemans.valuation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Every constant the methodology uses, loaded from a versioned config. Bundled versions live in
 * {@code parameter-sets/<version>.properties}; the app may load others (e.g. from the database) via
 * {@link #fromProperties(Properties)}.
 */
public record ParameterSet(
        String version,
        // Step 3: pricing
        int halfLifeDays,
        int windowDays,
        int extendedWindowDays,
        int minSalesInWindow,
        int outlierTrimMinSales,
        BigDecimal outlierTrimFraction,
        int cardLadderMinGrades,
        int cardLadderMinSpan,
        int staleGraceDays,
        int staleDaysPerLevel,
        // Liquidity tiers (sales per month)
        BigDecimal highLiquidityAbove,
        BigDecimal lowLiquidityBelow,
        // Step 4: values
        BigDecimal sellingFees,
        BigDecimal buyerCosts,
        BigDecimal searchPremiumHigh,
        BigDecimal searchPremiumMid,
        BigDecimal searchPremiumLow,
        BigDecimal speedDiscountHigh,
        BigDecimal speedDiscountMid,
        BigDecimal speedDiscountLow,
        BigDecimal stressK,
        BigDecimal stressMonths,
        int stressShiftGrades,
        int stressShiftGradesConfidenceC,
        BigDecimal depthDiscountFloor,
        BigDecimal depthDiscountCap,
        // Confidence
        BigDecimal compsForA,
        BigDecimal compsForB,
        BigDecimal compsForC,
        // Step 5: portfolio
        BigDecimal minFmv,
        BigDecimal singleCardCap,
        BigDecimal topFiveCap,
        BigDecimal correlationThreshold,
        BigDecimal correlationHaircut,
        BigDecimal suggestedAlphaLow,
        BigDecimal suggestedAlphaHigh,
        // Step 6: grade-first
        BigDecimal gradeFirstMinLift,
        int gradeFirstTopN,
        // Valuation runs: σ for a card with too few monthly price changes (null in sets before 1.1.0)
        BigDecimal fallbackSigma,
        // Instant offers: the buyer's margin by the card's confidence (null in sets before 1.2.0)
        BigDecimal offerMarginA,
        BigDecimal offerMarginB,
        BigDecimal offerMarginC,
        // Comps confidence (null in sets before 1.3.0, which means off; review B5): also no better than the
        // count at the modal grade, and a grade with fewer than minSalesInWindow sale days in the extended
        // window counts as derived, not observed
        Boolean modalGradeConfidence,
        Boolean thinGradesDerived,
        // The PSA 10 premium (null in sets before 1.4.0, which means off; Zach, 2026-11-09): the card's own
        // steepest step-down ratio leaves out the step up to 10, and an offer's 10 rung is at most this
        // multiple of its 9 rung
        Boolean cardLadderExcludesTen,
        BigDecimal offerMaxTenToNineMultiple,
        // PokeTrace day guards (null in sets before 1.5.0, which means off; review B4): a day whose high is more
        // than dayMaxSpread times its low, or whose average is more than dayMaxMultipleOfTier times the median of
        // at least dayGuardMinOtherDays other sale days of its tier within dayGuardNeighbourhoodDays either side,
        // is left out of pricing. Never shifted, and a low day is never left out, since that would push prices up
        BigDecimal dayMaxSpread,
        BigDecimal dayMaxMultipleOfTier,
        Integer dayGuardMinOtherDays,
        Integer dayGuardNeighbourhoodDays,
        // A measured monthly σ above this is not used: the valuation takes the fallback σ and its confidence drop
        // (null before 1.5.0; review B4)
        BigDecimal maxSigma,
        // A condition built from a single pre-grade provider keeps confidence A only while the card's FMV (central
        // distribution, net prices) is at most this; above it the condition counts as B. Null before 1.6.0, where a
        // one-provider condition is never better than B (Zach, 2026-11-10)
        BigDecimal oneProviderMaxFmvForA,
        // A PokeTrace day PokeTrace revised is priced at its newest revision, the older rows left out with the revision
        // that replaced them. Null before 1.7.0, where revisions are ignored and the day's first row stays its comp
        Boolean pokeTraceLatestRevision,
        // Raw-sale floors (null before 1.8.0, which means none; review B1 step 2, Zach 2026-11-11): the grades each raw
        // condition floors, as "7,8" (null or blank for none); the recency-weighted quantile of the condition's sale
        // days that sets the floor; the fewest sale days for a floor; and whether a grade priced at its floor counts
        // as derived for confidence
        String rawFloorNearMint,
        String rawFloorLightlyPlayed,
        String rawFloorModeratelyPlayed,
        String rawFloorHeavilyPlayed,
        BigDecimal rawFloorQuantile,
        Integer rawFloorMinSaleDays,
        Boolean rawFloorDerived) {

    public static final String DEFAULT_VERSION = "1.0.0";

    public ParameterSet {
        if (rawFloorQuantile != null) {
            if (rawFloorQuantile.signum() <= 0 || rawFloorQuantile.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("pricing.rawFloor.quantile must be above 0 and at most 1: " + rawFloorQuantile);
            }
            if (rawFloorMinSaleDays == null || rawFloorMinSaleDays < 1) {
                throw new IllegalArgumentException("pricing.rawFloor.minSaleDays must be at least 1 with raw floors");
            }
            // The parameters, not the fields: a compact constructor assigns the fields only after its body.
            java.util.Map<RawFloors.Condition, String> texts = new java.util.EnumMap<>(RawFloors.Condition.class);
            texts.put(RawFloors.Condition.NEAR_MINT, rawFloorNearMint);
            texts.put(RawFloors.Condition.LIGHTLY_PLAYED, rawFloorLightlyPlayed);
            texts.put(RawFloors.Condition.MODERATELY_PLAYED, rawFloorModeratelyPlayed);
            texts.put(RawFloors.Condition.HEAVILY_PLAYED, rawFloorHeavilyPlayed);
            java.util.Set<Grade> seen = java.util.EnumSet.noneOf(Grade.class);
            for (RawFloors.Condition condition : RawFloors.Condition.values()) {
                for (Grade grade : parseFloorGrades(condition, texts.get(condition))) {
                    if (!seen.add(grade)) {
                        throw new IllegalArgumentException("Grade " + grade + " has a raw floor from two conditions");
                    }
                }
            }
        }
    }

    /** The default bundled parameter set. */
    public static ParameterSet defaults() {
        return load(DEFAULT_VERSION);
    }

    /** Loads a bundled parameter set by version. */
    public static ParameterSet load(String version) {
        String resource = "/parameter-sets/" + version + ".properties";
        try (InputStream in = ParameterSet.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No parameter set " + version + " at " + resource);
            }
            Properties props = new Properties();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            ParameterSet set = fromProperties(props);
            if (!set.version().equals(version)) {
                throw new IllegalStateException("Parameter set file " + resource + " declares version " + set.version());
            }
            return set;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read parameter set " + version, e);
        }
    }

    public static ParameterSet fromProperties(Properties p) {
        return new ParameterSet(
                required(p, "version"),
                integer(p, "pricing.halfLifeDays"),
                integer(p, "pricing.windowDays"),
                integer(p, "pricing.extendedWindowDays"),
                integer(p, "pricing.minSalesInWindow"),
                integer(p, "pricing.outlierTrimMinSales"),
                decimal(p, "pricing.outlierTrimFraction"),
                integer(p, "pricing.cardLadder.minGrades"),
                integer(p, "pricing.cardLadder.minSpan"),
                integer(p, "pricing.stale.graceDays"),
                integer(p, "pricing.stale.daysPerLevel"),
                decimal(p, "liquidity.highAbove"),
                decimal(p, "liquidity.lowBelow"),
                decimal(p, "values.sellingFees"),
                decimal(p, "values.buyerCosts"),
                decimal(p, "values.searchPremium.high"),
                decimal(p, "values.searchPremium.mid"),
                decimal(p, "values.searchPremium.low"),
                decimal(p, "values.speedDiscount.high"),
                decimal(p, "values.speedDiscount.mid"),
                decimal(p, "values.speedDiscount.low"),
                decimal(p, "values.stressK"),
                decimal(p, "values.stressMonths"),
                integer(p, "values.stressShiftGrades"),
                integer(p, "values.stressShiftGradesConfidenceC"),
                decimal(p, "values.depthDiscountFloor"),
                decimal(p, "values.depthDiscountCap"),
                decimal(p, "confidence.compsForA"),
                decimal(p, "confidence.compsForB"),
                decimal(p, "confidence.compsForC"),
                decimal(p, "portfolio.minFmv"),
                decimal(p, "portfolio.singleCardCap"),
                decimal(p, "portfolio.topFiveCap"),
                decimal(p, "portfolio.correlationThreshold"),
                decimal(p, "portfolio.correlationHaircut"),
                decimal(p, "portfolio.suggestedAlphaLow"),
                decimal(p, "portfolio.suggestedAlphaHigh"),
                decimal(p, "gradeFirst.minLift"),
                integer(p, "gradeFirst.topN"),
                optionalDecimal(p, "values.fallbackSigma"),
                optionalDecimal(p, "offers.buyerMargin.A"),
                optionalDecimal(p, "offers.buyerMargin.B"),
                optionalDecimal(p, "offers.buyerMargin.C"),
                optionalBoolean(p, "confidence.modalGrade"),
                optionalBoolean(p, "pricing.thinGradesDerived"),
                optionalBoolean(p, "pricing.cardLadder.excludeTen"),
                optionalDecimal(p, "offers.maxTenToNineMultiple"),
                optionalDecimal(p, "pricing.pokeTraceDay.maxSpread"),
                optionalDecimal(p, "pricing.pokeTraceDay.maxMultipleOfTier"),
                optionalInteger(p, "pricing.pokeTraceDay.minOtherDays"),
                optionalInteger(p, "pricing.pokeTraceDay.neighbourhoodDays"),
                optionalDecimal(p, "values.maxSigma"),
                optionalDecimal(p, "confidence.oneProviderMaxFmvForA"),
                optionalBoolean(p, "pricing.pokeTraceDay.latestRevision"),
                optionalString(p, "pricing.rawFloor.NEAR_MINT"),
                optionalString(p, "pricing.rawFloor.LIGHTLY_PLAYED"),
                optionalString(p, "pricing.rawFloor.MODERATELY_PLAYED"),
                optionalString(p, "pricing.rawFloor.HEAVILY_PLAYED"),
                optionalDecimal(p, "pricing.rawFloor.quantile"),
                optionalInteger(p, "pricing.rawFloor.minSaleDays"),
                optionalBoolean(p, "pricing.rawFloor.derived"));
    }

    /** True when this set floors grade prices with raw sales (1.8.0 on). */
    public boolean hasRawFloors() {
        return rawFloorQuantile != null;
    }

    /** The grades a raw condition floors, lowest first; empty for none or before 1.8.0. */
    public java.util.List<Grade> rawFloorGrades(RawFloors.Condition condition) {
        return hasRawFloors() ? parseFloorGrades(condition, rawFloorGradesText(condition)) : java.util.List.of();
    }

    /** True when a grade priced at its raw floor counts as derived for confidence (1.8.0 on). */
    public boolean rawFloorIsDerived() {
        return Boolean.TRUE.equals(rawFloorDerived);
    }

    private String rawFloorGradesText(RawFloors.Condition condition) {
        return switch (condition) {
            case NEAR_MINT -> rawFloorNearMint;
            case LIGHTLY_PLAYED -> rawFloorLightlyPlayed;
            case MODERATELY_PLAYED -> rawFloorModeratelyPlayed;
            case HEAVILY_PLAYED -> rawFloorHeavilyPlayed;
        };
    }

    /** "7,8" as grades; never 1 (its own market, priced from its own sales only). 9 and 10 are allowed, pending Zach. */
    private static java.util.List<Grade> parseFloorGrades(RawFloors.Condition condition, String text) {
        if (text == null || text.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<Grade> grades = new java.util.ArrayList<>();
        for (String part : text.split(",")) {
            int g = Integer.parseInt(part.trim());
            if (g < 2 || g > 10) {
                throw new IllegalArgumentException("pricing.rawFloor." + condition + " may floor grades 2 to 10 only, never 1, not " + g);
            }
            Grade grade = Grade.of(g);
            if (!grades.contains(grade)) {
                grades.add(grade);
            }
        }
        grades.sort(null);
        return java.util.List.copyOf(grades);
    }

    private static String optionalString(Properties p, String key) {
        String value = p.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Comps confidence is also no better than the count behind the modal grade's price (1.3.0 on). */
    public boolean usesModalGradeConfidence() {
        return Boolean.TRUE.equals(modalGradeConfidence);
    }

    /**
     * True when an observed price from fewer than {@link #minSalesInWindow} sales in the extended window
     * counts as derived for confidence (1.3.0 on). The price itself is unchanged.
     */
    public boolean thinGradesAreDerived() {
        return Boolean.TRUE.equals(thinGradesDerived);
    }

    /** True for an observed price this set treats as derived because too few sales are behind it. */
    public boolean isThin(GradePrice price) {
        return thinGradesAreDerived() && price.method() == GradePrice.Method.OBSERVED && price.salesInWindow() < minSalesInWindow;
    }

    /**
     * True when the card's own steepest step-down ratio leaves out the step up to grade 10 (1.4.0 on): a PSA 10
     * is its own market, like a PSA 1, and its jump over the 9 would drag every lower grade far down.
     */
    public boolean cardLadderLeavesOutTen() {
        return Boolean.TRUE.equals(cardLadderExcludesTen);
    }

    /** The most an offer's 10 rung pays as a multiple of its 9 rung, or empty for no cap (before 1.4.0). */
    public java.util.Optional<BigDecimal> offerTenToNineCap() {
        return java.util.Optional.ofNullable(offerMaxTenToNineMultiple);
    }

    /**
     * True when a condition from a single pre-grade provider can't stay at A for a card of this FMV: above
     * {@link #oneProviderMaxFmvForA}, or always before 1.6.0, which has no threshold (Zach, 2026-11-10).
     */
    public boolean oneProviderAboveA(BigDecimal fmv) {
        return oneProviderMaxFmvForA == null || fmv.compareTo(oneProviderMaxFmvForA) > 0;
    }

    /**
     * True when a revised PokeTrace day is priced at its newest revision (1.7.0 on); before, revisions are ignored and
     * the day's first row is its comp.
     */
    public boolean pricesPokeTraceRevisions() {
        return Boolean.TRUE.equals(pokeTraceLatestRevision);
    }

    /** Liquidity tier for a card's monthly sales count. */
    public LiquidityTier liquidityTier(BigDecimal salesPerMonth) {
        if (salesPerMonth.compareTo(highLiquidityAbove) > 0) {
            return LiquidityTier.HIGH;
        }
        if (salesPerMonth.compareTo(lowLiquidityBelow) < 0) {
            return LiquidityTier.LOW;
        }
        return LiquidityTier.MID;
    }

    public BigDecimal searchPremium(LiquidityTier tier) {
        return switch (tier) {
            case HIGH -> searchPremiumHigh;
            case MID -> searchPremiumMid;
            case LOW -> searchPremiumLow;
        };
    }

    public BigDecimal speedDiscount(LiquidityTier tier) {
        return switch (tier) {
            case HIGH -> speedDiscountHigh;
            case MID -> speedDiscountMid;
            case LOW -> speedDiscountLow;
        };
    }

    /**
     * The buyer's margin on an instant offer for a card valued at this confidence: the less certain
     * its condition, the more the buyer keeps for carrying the risk (Zach, 2026-10-21). D gets no offer.
     */
    public BigDecimal offerMargin(Confidence confidence) {
        BigDecimal margin = switch (confidence) {
            case A -> offerMarginA;
            case B -> offerMarginB;
            case C -> offerMarginC;
            case D -> throw new IllegalArgumentException("Confidence D gets no offer");
        };
        if (margin == null) {
            throw new IllegalStateException("Parameter set " + version + " has no offer margins: offers need 1.2.0 or later");
        }
        return margin;
    }

    public enum LiquidityTier { HIGH, MID, LOW }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing parameter " + key);
        }
        return value.trim();
    }

    private static BigDecimal decimal(Properties p, String key) {
        return new BigDecimal(required(p, key));
    }

    private static BigDecimal optionalDecimal(Properties p, String key) {
        String value = p.getProperty(key);
        return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
    }

    private static Integer optionalInteger(Properties p, String key) {
        String value = p.getProperty(key);
        return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
    }

    /** True when a measured σ is above this set's cap and must not be used (1.5.0 on). */
    public boolean sigmaTooHigh(BigDecimal sigma) {
        return maxSigma != null && sigma != null && sigma.compareTo(maxSigma) > 0;
    }

    private static Boolean optionalBoolean(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim()) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("Parameter " + key + " must be true or false: " + value);
        };
    }

    private static int integer(Properties p, String key) {
        return Integer.parseInt(required(p, key));
    }

    /** Confidence from a count of sales, against the A, B and C thresholds. A weighted count may be fractional. */
    public Confidence fromCompCount(BigDecimal count) {
        if (count.compareTo(compsForA) >= 0) {
            return Confidence.A;
        }
        if (count.compareTo(compsForB) >= 0) {
            return Confidence.B;
        }
        if (count.compareTo(compsForC) >= 0) {
            return Confidence.C;
        }
        return Confidence.D;
    }
}

package com.pokemans.app.condition;

import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the system's condition distribution from provider grades (CLAUDE.md pipeline step 2).
 * Pure: the same inputs and properties always give the same distribution.
 *
 * <ol>
 *   <li>Each provider grade is folded down to a whole grade (half grades fold down) and spread over
 *       neighbouring grades, more below than above. When the provider's lowest of corners, edges and
 *       surface (folded) is at or above that grade, the tighter {@link ConditionProperties#tightSpread()} is
 *       used; a lower subgrade, or none given, keeps {@link ConditionProperties#spread()}. The provider's
 *       centering subgrade is never read: only our own measurement caps (Zach, 2026-11-10).</li>
 *   <li>Providers are averaged.</li>
 *   <li>P(10) is capped ({@link ConditionProperties#maxTenProbability()}): the excess moves down to 9. A
 *       provider 10 would otherwise put 0.60 on 10 (its own 0.50 plus the +1 mass the edge keeps), and the
 *       PSA 10 price dominates an offer (review B2).</li>
 *   <li>A centering-only reading (our own measurement) caps the result: whatever probability sits
 *       above the best grade the centering allows moves onto that grade. It never adds a grade of its
 *       own to the average, since a card centred for a 10 can still have the corners of a 4.</li>
 *   <li>Confidence comes from how many providers answered, how far apart they are, and whether every
 *       photo showed the whole card. Providers disagreeing or a photo not showing the whole card moves
 *       probability down. A single provider answering on whole-card photos is A; a valuation caps that A
 *       at B for a card above the parameter set's FMV threshold (Zach, 2026-11-10).</li>
 *   <li>U gets at least {@link ConditionProperties#ungradableFloor()}, taken proportionally from the other
 *       grades: a photo model can't see a trimmed or recoloured card (review B2).</li>
 * </ol>
 *
 * The 10 cap and the U floor are for system distributions only: a reviewer's hand-entered distribution
 * doesn't come through here, and a graded card is certain at its grade. Every number here (spread, cap,
 * floor, penalties) is a starting guess until returned grades exist to calibrate it.
 *
 * There is no default surface shift for holos or full arts: surface counts through the providers'
 * grades, which score it (Zach, 2026-11-04, review D5). Nothing here moves probability up. Beyond the U floor,
 * alteration and trimming are a reviewer's call, not a photo model's.
 */
final class DistributionBuilder {

    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * @param providerGrades     one grade per provider that answered (provider name to grade), 1–10 scale
     * @param wholeCardInPhotos  every photo used had the whole card detected
     * @param centeringCap       the best grade measured centering allows, or null when it wasn't measured
     * @param minSubgrades       per provider, the lowest of its corners, edges and surface grades, present only
     *                           when it gave all three; never its centering
     */
    record Input(Map<String, Double> providerGrades, boolean wholeCardInPhotos, Integer centeringCap,
                 Map<String, Double> minSubgrades) {

        Input(Map<String, Double> providerGrades, boolean wholeCardInPhotos, Integer centeringCap) {
            this(providerGrades, wholeCardInPhotos, centeringCap, Map.of());
        }

        Input {
            minSubgrades = minSubgrades == null ? Map.of() : minSubgrades;
        }
    }

    /** The lowest of corners, edges and surface, or null unless all three are given. Centering is left out. */
    static Double minSubgrade(Double corners, Double edges, Double surface) {
        if (corners == null || edges == null || surface == null) {
            return null;
        }
        return Math.min(corners, Math.min(edges, surface));
    }

    record Result(GradeDistribution distribution, Confidence confidence, Map<String, Object> basis) {
    }

    private final ConditionProperties properties;

    DistributionBuilder(ConditionProperties properties) {
        this.properties = properties;
    }

    /** Null when no provider answered: there is nothing to build from, and the reviewer enters it. */
    Result build(Input input) {
        if (input.providerGrades().isEmpty()) {
            return null;
        }
        List<GradeDistribution> perProvider = new ArrayList<>();
        Map<String, Integer> folded = new LinkedHashMap<>();
        Map<String, Integer> foldedMinSub = new LinkedHashMap<>();
        Map<String, String> spreadUsed = new LinkedHashMap<>();
        input.providerGrades().forEach((provider, grade) -> {
            int whole = fold(grade);
            folded.put(provider, whole);
            Double minSub = input.minSubgrades().get(provider);
            Integer wholeMinSub = minSub == null ? null : fold(minSub);
            foldedMinSub.put(provider, wholeMinSub);
            boolean tight = wholeMinSub != null && wholeMinSub >= whole;
            spreadUsed.put(provider, tight ? "tight" : "default");
            perProvider.add(spread(whole, tight ? properties.tightSpread() : properties.spread()));
        });
        GradeDistribution distribution = capTen(average(perProvider), properties.maxTenProbability());
        // Before the confidence shift, so it still moves mass below the cap.
        if (input.centeringCap() != null) {
            distribution = distribution.capAt(Grade.of(Math.max(1, Math.min(10, input.centeringCap()))));
        }

        DoubleSummaryStatistics stats = input.providerGrades().values().stream().mapToDouble(Double::doubleValue).summaryStatistics();
        double gap = stats.getMax() - stats.getMin();
        Confidence confidence;
        if (!input.wholeCardInPhotos() || gap > properties.disagreementForB()) {
            confidence = Confidence.C;
        } else if (gap > 1.0) {
            confidence = Confidence.B;
        } else {
            confidence = Confidence.A;
        }
        // B is providers more than a grade apart. One provider alone is A and moves nothing down; the valuation
        // caps that A at B for a card above the FMV threshold (Zach, 2026-11-10).
        BigDecimal confidencePenalty = switch (confidence) {
            case A -> BigDecimal.ZERO;
            case B -> properties.confidencePenaltyB();
            case C, D -> properties.confidencePenaltyC();
        };
        if (confidencePenalty.signum() > 0) {
            distribution = distribution.applyPhotoConfidencePenalty(confidencePenalty);
        }
        // Last, so nothing after it can take U below the floor. It scales the grades down, so P(10) stays capped.
        distribution = floorUngradable(distribution, properties.ungradableFloor());

        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("providerGrades", input.providerGrades());
        basis.put("foldedGrades", folded);
        basis.put("minSubgrades", foldedMinSub);
        basis.put("spreadUsed", spreadUsed);
        basis.put("providerGap", gap);
        basis.put("centeringCap", input.centeringCap());
        basis.put("wholeCardInPhotos", input.wholeCardInPhotos());
        basis.put("confidencePenalty", confidencePenalty);
        basis.put("spread", new TreeMap<>(properties.spread()));
        basis.put("tightSpread", new TreeMap<>(properties.tightSpread()));
        basis.put("maxTenProbability", properties.maxTenProbability());
        basis.put("ungradableFloor", properties.ungradableFloor());
        return new Result(distribution, confidence, basis);
    }

    /**
     * The configured spread around one whole grade; mass past 10 or below 1 stays on the edge grade.
     * {@link #build} then caps P(10), so the edge can't pile mass onto 10.
     */
    GradeDistribution spread(int grade) {
        return spread(grade, properties.spread());
    }

    private static GradeDistribution spread(int grade, Map<Integer, BigDecimal> weights) {
        EnumMap<Grade, BigDecimal> p = new EnumMap<>(Grade.class);
        weights.forEach((offset, weight) -> {
            Grade target = Grade.of(Math.max(1, Math.min(10, grade + offset)));
            p.merge(target, weight, BigDecimal::add);
        });
        return GradeDistribution.of(p);
    }

    private static int fold(double grade) {
        return Math.max(1, Math.min(10, (int) Math.floor(grade)));
    }

    /** Moves whatever probability on 10 exceeds {@code max} down to 9. Downward only. */
    static GradeDistribution capTen(GradeDistribution distribution, BigDecimal max) {
        BigDecimal excess = distribution.p(Grade.G10).subtract(max);
        if (excess.signum() <= 0) {
            return distribution;
        }
        EnumMap<Grade, BigDecimal> p = new EnumMap<>(distribution.asMap());
        p.put(Grade.G10, max);
        p.merge(Grade.G9, excess, BigDecimal::add);
        return GradeDistribution.of(p);
    }

    /**
     * Raises U to {@code floor} if it is below, scaling every grade by the same factor so the total stays 1.
     * Every grade's probability falls or stays: nothing moves up.
     */
    static GradeDistribution floorUngradable(GradeDistribution distribution, BigDecimal floor) {
        BigDecimal u = distribution.p(Grade.U);
        if (u.compareTo(floor) >= 0) {
            return distribution;
        }
        BigDecimal scale = BigDecimal.ONE.subtract(floor).divide(BigDecimal.ONE.subtract(u), MC);
        EnumMap<Grade, BigDecimal> p = new EnumMap<>(Grade.class);
        distribution.asMap().forEach((grade, q) -> p.put(grade, grade.isUngradable() ? floor : q.multiply(scale, MC)));
        return GradeDistribution.of(p);
    }

    private static GradeDistribution average(List<GradeDistribution> distributions) {
        BigDecimal n = BigDecimal.valueOf(distributions.size());
        EnumMap<Grade, BigDecimal> sum = new EnumMap<>(Grade.class);
        for (GradeDistribution d : distributions) {
            d.asMap().forEach((grade, p) -> sum.merge(grade, p, BigDecimal::add));
        }
        EnumMap<Grade, BigDecimal> mean = new EnumMap<>(Grade.class);
        sum.forEach((grade, total) -> mean.put(grade, total.divide(n, MC)));
        return GradeDistribution.of(mean);
    }
}

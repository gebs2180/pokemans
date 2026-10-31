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
 *       neighbouring grades, more below than above.</li>
 *   <li>Providers are averaged.</li>
 *   <li>A centering-only reading (our own measurement) caps the result: whatever probability sits
 *       above the best grade the centering allows moves onto that grade. It never adds a grade of its
 *       own to the average, since a card centred for a 10 can still have the corners of a 4.</li>
 *   <li>Confidence comes from how many providers answered, how far apart they are, and whether every
 *       photo showed the whole card. Lower confidence moves probability down.</li>
 *   <li>Modern holos get the default surface shift down; a reviewer can clear it after a close-up.</li>
 * </ol>
 *
 * Nothing here moves probability up. U (ungradable) stays at zero: alteration and trimming are a
 * reviewer's call, not a photo model's.
 */
final class DistributionBuilder {

    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * @param providerGrades     one grade per provider that answered (provider name to grade), 1–10 scale
     * @param wholeCardInPhotos  every photo used had the whole card detected
     * @param modernHolo         the card is identified as a holo or reverse holo from after the WotC era
     * @param centeringCap       the best grade measured centering allows, or null when it wasn't measured
     */
    record Input(Map<String, Double> providerGrades, boolean wholeCardInPhotos, boolean modernHolo, Integer centeringCap) {
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
        input.providerGrades().forEach((provider, grade) -> {
            int whole = Math.max(1, Math.min(10, (int) Math.floor(grade)));
            folded.put(provider, whole);
            perProvider.add(spread(whole));
        });
        GradeDistribution distribution = average(perProvider);
        // Before the confidence and holo shifts, so they still move mass below the cap.
        if (input.centeringCap() != null) {
            distribution = distribution.capAt(Grade.of(Math.max(1, Math.min(10, input.centeringCap()))));
        }

        DoubleSummaryStatistics stats = input.providerGrades().values().stream().mapToDouble(Double::doubleValue).summaryStatistics();
        double gap = stats.getMax() - stats.getMin();
        Confidence confidence;
        if (!input.wholeCardInPhotos() || gap > properties.disagreementForB()) {
            confidence = Confidence.C;
        } else if (input.providerGrades().size() < 2 || gap > 1.0) {
            confidence = Confidence.B;
        } else {
            confidence = Confidence.A;
        }
        BigDecimal confidencePenalty = switch (confidence) {
            case A -> BigDecimal.ZERO;
            case B -> properties.confidencePenaltyB();
            case C, D -> properties.confidencePenaltyC();
        };
        if (confidencePenalty.signum() > 0) {
            distribution = distribution.applyPhotoConfidencePenalty(confidencePenalty);
        }
        if (input.modernHolo()) {
            distribution = distribution.applySurfacePenalty(properties.holoSurfacePenalty());
        }

        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("providerGrades", input.providerGrades());
        basis.put("foldedGrades", folded);
        basis.put("providerGap", gap);
        basis.put("centeringCap", input.centeringCap());
        basis.put("wholeCardInPhotos", input.wholeCardInPhotos());
        basis.put("modernHolo", input.modernHolo());
        basis.put("confidencePenalty", confidencePenalty);
        basis.put("holoSurfacePenalty", input.modernHolo() ? properties.holoSurfacePenalty() : BigDecimal.ZERO);
        basis.put("spread", new TreeMap<>(properties.spread()));
        return new Result(distribution, confidence, basis);
    }

    /** The configured spread around one whole grade; mass past 10 or below 1 stays on the edge grade. */
    GradeDistribution spread(int grade) {
        EnumMap<Grade, BigDecimal> p = new EnumMap<>(Grade.class);
        properties.spread().forEach((offset, weight) -> {
            Grade target = Grade.of(Math.max(1, Math.min(10, grade + offset)));
            p.merge(target, weight, BigDecimal::add);
        });
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

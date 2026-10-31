package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Probabilities over the 11 condition outcomes (U, 1–10). Immutable. Every adjustment moves
 * probability down, never up.
 */
public final class GradeDistribution {

    private static final BigDecimal SUM_TOLERANCE = new BigDecimal("0.000001");

    private final EnumMap<Grade, BigDecimal> probabilities;

    private GradeDistribution(EnumMap<Grade, BigDecimal> probabilities) {
        this.probabilities = probabilities;
    }

    /** Builds a distribution; missing grades are 0. Probabilities must be in [0, 1] and sum to 1. */
    public static GradeDistribution of(Map<Grade, BigDecimal> probabilities) {
        EnumMap<Grade, BigDecimal> copy = new EnumMap<>(Grade.class);
        BigDecimal sum = BigDecimal.ZERO;
        for (Grade grade : Grade.values()) {
            BigDecimal p = probabilities.getOrDefault(grade, BigDecimal.ZERO);
            if (p.signum() < 0 || p.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Probability for grade " + grade + " out of [0, 1]: " + p);
            }
            copy.put(grade, p);
            sum = sum.add(p);
        }
        if (sum.subtract(BigDecimal.ONE).abs().compareTo(SUM_TOLERANCE) > 0) {
            throw new IllegalArgumentException("Probabilities must sum to 1, got " + sum);
        }
        return new GradeDistribution(copy);
    }

    /** A distribution with all probability on one grade (a graded card). */
    public static GradeDistribution certain(Grade grade) {
        return of(Map.of(grade, BigDecimal.ONE));
    }

    public BigDecimal p(Grade grade) {
        return probabilities.get(grade);
    }

    public Map<Grade, BigDecimal> asMap() {
        return Collections.unmodifiableMap(probabilities);
    }

    /**
     * Shifts every gradable outcome {@code steps} grades down, floored at grade 1. The ungradable
     * probability is held as is. Used for the FLV condition stress.
     */
    public GradeDistribution shiftDown(int steps) {
        if (steps < 0) {
            throw new IllegalArgumentException("Shift must be downward: " + steps);
        }
        EnumMap<Grade, BigDecimal> shifted = zeros();
        for (Grade grade : Grade.values()) {
            Grade target = grade.down(steps);
            shifted.merge(target, probabilities.get(grade), BigDecimal::add);
        }
        return new GradeDistribution(shifted);
    }

    /**
     * Moves {@code fraction} of each gradable grade's probability one grade down (grade 1 keeps its
     * mass, U is untouched). Used for low photo confidence and the modern holo / full-art surface
     * penalty. Downward only: fraction must be in [0, 1].
     */
    public GradeDistribution moveMassDown(BigDecimal fraction) {
        if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Fraction must be in [0, 1]: " + fraction);
        }
        EnumMap<Grade, BigDecimal> moved = zeros();
        for (Grade grade : Grade.values()) {
            BigDecimal p = probabilities.get(grade);
            if (grade.isUngradable() || grade == Grade.G1) {
                moved.merge(grade, p, BigDecimal::add);
                continue;
            }
            BigDecimal shifted = p.multiply(fraction, Money.MC);
            moved.merge(grade, p.subtract(shifted), BigDecimal::add);
            moved.merge(grade.down(1), shifted, BigDecimal::add);
        }
        return new GradeDistribution(moved);
    }

    /**
     * Moves the probability of every grade above {@code max} onto {@code max}, e.g. when the card's
     * centering allows no higher grade. Grades at or below it and U are unchanged: downward only.
     */
    public GradeDistribution capAt(Grade max) {
        if (max.isUngradable()) {
            throw new IllegalArgumentException("Cap must be a grade from 1 to 10");
        }
        EnumMap<Grade, BigDecimal> capped = zeros();
        for (Grade grade : Grade.values()) {
            Grade target = grade.number() > max.number() ? max : grade;
            capped.merge(target, probabilities.get(grade), BigDecimal::add);
        }
        return new GradeDistribution(capped);
    }

    /** Photo-confidence adjustment: low confidence moves probability toward lower grades. */
    public GradeDistribution applyPhotoConfidencePenalty(BigDecimal fraction) {
        return moveMassDown(fraction);
    }

    /** Surface penalty for modern holos and full arts not cleared by a close-up. */
    public GradeDistribution applySurfacePenalty(BigDecimal fraction) {
        return moveMassDown(fraction);
    }

    /** Median outcome, ordering U below grade 1. */
    public Grade median() {
        BigDecimal half = new BigDecimal("0.5");
        BigDecimal cumulative = BigDecimal.ZERO;
        for (Grade grade : Grade.values()) {
            cumulative = cumulative.add(probabilities.get(grade));
            if (cumulative.compareTo(half) >= 0) {
                return grade;
            }
        }
        return Grade.G10;
    }

    private static EnumMap<Grade, BigDecimal> zeros() {
        EnumMap<Grade, BigDecimal> map = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            map.put(grade, BigDecimal.ZERO);
        }
        return map;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GradeDistribution other)) {
            return false;
        }
        for (Grade grade : Grade.values()) {
            if (probabilities.get(grade).compareTo(other.probabilities.get(grade)) != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (Grade grade : Grade.values()) {
            h = 31 * h + probabilities.get(grade).stripTrailingZeros().hashCode();
        }
        return h;
    }

    @Override
    public String toString() {
        return probabilities.toString();
    }
}

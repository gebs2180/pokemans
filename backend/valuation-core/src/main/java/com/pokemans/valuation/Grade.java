package com.pokemans.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The 11 condition outcomes: ungradable (U), then grades 1 through 10. */
public enum Grade {
    U(0), G1(1), G2(2), G3(3), G4(4), G5(5), G6(6), G7(7), G8(8), G9(9), G10(10);

    private final int number;

    Grade(int number) {
        this.number = number;
    }

    /** 0 for U, otherwise the numeric grade. */
    public int number() {
        return number;
    }

    public boolean isUngradable() {
        return this == U;
    }

    public static Grade of(int number) {
        if (number < 0 || number > 10) {
            throw new IllegalArgumentException("Grade must be 0 (U) to 10: " + number);
        }
        return values()[number];
    }

    /**
     * Folds a half grade (8.5, 9.5) into the grade below. The exception for half grades with their
     * own sales history is not modelled yet; callers keep the raw grade on the comp.
     */
    public static Grade foldHalf(BigDecimal gradeValue) {
        return of(gradeValue.setScale(0, RoundingMode.FLOOR).intValueExact());
    }

    /** Grade {@code steps} lower, floored at grade 1. U stays U. */
    public Grade down(int steps) {
        if (this == U) {
            return U;
        }
        return of(Math.max(1, number - steps));
    }

    @Override
    public String toString() {
        return this == U ? "U" : Integer.toString(number);
    }
}

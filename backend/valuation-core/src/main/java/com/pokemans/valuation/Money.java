package com.pokemans.valuation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Arithmetic conventions shared by the valuation code. */
public final class Money {

    /** Working precision for intermediate results. */
    public static final MathContext MC = MathContext.DECIMAL128;

    private Money() {
    }

    /** Rounds a money amount to cents for reporting. */
    public static BigDecimal cents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static BigDecimal clamp(BigDecimal value, BigDecimal floor, BigDecimal cap) {
        return min(max(value, floor), cap);
    }

    public static boolean isPositive(BigDecimal value) {
        return value.signum() > 0;
    }
}

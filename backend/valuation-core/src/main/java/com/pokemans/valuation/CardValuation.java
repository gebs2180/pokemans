package com.pokemans.valuation;

import java.math.BigDecimal;

/**
 * Step 4 output for one card. Money values are rounded to cents.
 *
 * @param stressedFmv    FMV under the condition stress (FMV↓)
 * @param stressShift    grades the distribution was shifted down for FMV↓
 * @param volatilityShock 1 − k·σ·√t, floored at 0
 * @param depthDiscount  ℓ, the market-depth discount
 */
public record CardValuation(
        String cardId,
        BigDecimal fmv,
        BigDecimal rv,
        BigDecimal qsv,
        BigDecimal stressedFmv,
        BigDecimal flv,
        Confidence confidence,
        int stressShift,
        ParameterSet.LiquidityTier liquidityTier,
        BigDecimal searchPremium,
        BigDecimal speedDiscount,
        BigDecimal volatilityShock,
        BigDecimal depthDiscount,
        Trace trace) {
}

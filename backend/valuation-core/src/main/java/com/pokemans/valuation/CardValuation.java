package com.pokemans.valuation;

import java.math.BigDecimal;

/**
 * Step 4 output for one card. Money values are rounded to cents.
 *
 * @param stressedFmv    FMV under the condition stress (FMV↓)
 * @param stressShift    grades the distribution was shifted down for FMV↓
 * @param volatilityShock 1 − k·σ·√t, floored at 0
 * @param depthDiscount  ℓ, the market-depth discount
 * @param offerConfidence the confidence an instant offer is priced at: like {@code confidence}, without
 *                        the penalties that only bear on the FLV (Zach, 2026-10-21)
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
        Trace trace,
        Confidence offerConfidence) {

    /** A valuation whose offer confidence is its confidence. */
    public CardValuation(String cardId, BigDecimal fmv, BigDecimal rv, BigDecimal qsv, BigDecimal stressedFmv, BigDecimal flv,
                         Confidence confidence, int stressShift, ParameterSet.LiquidityTier liquidityTier, BigDecimal searchPremium,
                         BigDecimal speedDiscount, BigDecimal volatilityShock, BigDecimal depthDiscount, Trace trace) {
        this(cardId, fmv, rv, qsv, stressedFmv, flv, confidence, stressShift, liquidityTier, searchPremium, speedDiscount,
                volatilityShock, depthDiscount, trace, confidence);
    }
}

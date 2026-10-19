package com.pokemans.app.valuation;

import com.pokemans.valuation.GradingCost;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param parameterSet   the parameter set runs are valued with (1.1.0 adds the fallback σ)
 * @param gradingCompany whose price table values a raw card's distribution: PSA only, no fallback (Zach, 2026-10-18)
 * @param gradingCost    the grading cost a run starts from; the admin can override it per run
 */
@ConfigurationProperties("pokemans.valuation")
record ValuationProperties(
        @DefaultValue("1.1.0") String parameterSet,
        @DefaultValue("PSA") String gradingCompany,
        @DefaultValue GradingCostDefaults gradingCost) {

    /** Starting values (Zach, 2026-10-18): PSA value tier, shipping and insurance per card, 2% for a 60-90 day wait. */
    record GradingCostDefaults(@DefaultValue("25") BigDecimal fee, @DefaultValue("5") BigDecimal shipping,
                               @DefaultValue("2") BigDecimal insurance, @DefaultValue("0.02") BigDecimal timeToCashRate) {

        GradingCost toCost() {
            return new GradingCost(fee, shipping, insurance, timeToCashRate);
        }
    }
}

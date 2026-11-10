package com.pokemans.app.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.valuation.ParameterSet;
import org.junit.jupiter.api.Test;

/**
 * Replay hashes a run's stored parameter set as {@link ValuationService#JSON} writes it. The 1.3.0 flags
 * are left out while null, so a 1.2.0 set serializes exactly as it did before they existed and old runs
 * still reproduce.
 */
class ParameterSetJsonTest {

    @Test
    void aSetWithoutTheNewFlagsSerializesAsBefore() throws Exception {
        ParameterSet v120 = ParameterSet.load("1.2.0");

        String json = ValuationService.JSON.writeValueAsString(v120);

        assertThat(json).doesNotContain("modalGradeConfidence").doesNotContain("thinGradesDerived")
                .endsWith("\"offerMarginC\":0.17}");
        assertThat(ValuationService.JSON.readValue(json, ParameterSet.class)).isEqualTo(v120);
    }

    @Test
    void version130KeepsItsFlags() throws Exception {
        ParameterSet v130 = ParameterSet.load("1.3.0");

        String json = ValuationService.JSON.writeValueAsString(v130);

        assertThat(json).contains("\"modalGradeConfidence\":true").contains("\"thinGradesDerived\":true")
                .doesNotContain("cardLadderExcludesTen").doesNotContain("offerMaxTenToNineMultiple");
        assertThat(ValuationService.JSON.readValue(json, ParameterSet.class)).isEqualTo(v130);
    }

    @Test
    void version140KeepsItsTenRules() throws Exception {
        ParameterSet v140 = ParameterSet.load("1.4.0");

        String json = ValuationService.JSON.writeValueAsString(v140);

        assertThat(json).contains("\"cardLadderExcludesTen\":true").contains("\"offerMaxTenToNineMultiple\":2.5")
                .doesNotContain("dayMaxSpread").doesNotContain("maxSigma");
        assertThat(ValuationService.JSON.readValue(json, ParameterSet.class)).isEqualTo(v140);
    }

    @Test
    void version150KeepsItsDayGuardsAndSigmaCap() throws Exception {
        ParameterSet v150 = ParameterSet.load("1.5.0");

        String json = ValuationService.JSON.writeValueAsString(v150);

        assertThat(json).contains("\"dayMaxSpread\":3").contains("\"dayMaxMultipleOfTier\":3")
                .contains("\"dayGuardMinOtherDays\":2").contains("\"dayGuardNeighbourhoodDays\":90").contains("\"maxSigma\":1.0");
        assertThat(ValuationService.JSON.readValue(json, ParameterSet.class)).isEqualTo(v150);
    }
}

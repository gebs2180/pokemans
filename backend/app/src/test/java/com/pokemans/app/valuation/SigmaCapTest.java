package com.pokemans.app.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.valuation.ParameterSet;
import com.pokemans.valuation.Trace;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Review B4: from parameter set 1.5.0 a sigma above 1 isn't used; the 20% fallback is, at one level less confidence. */
class SigmaCapTest {

    private static ValuationEngine.CardResult value(String version, String sigma) {
        return new ValuationEngine(ParameterSet.load(version), ValuationFixtures.COST)
                .value(List.of(ValuationFixtures.cardWithSigma(sigma))).cards().get(0);
    }

    private static List<String> sigmaLines(ValuationEngine.CardResult card) {
        return card.trace().stream().filter(s -> s.subject().equals("sigma")).map(Trace.Step::rule).toList();
    }

    @Test
    void aSigmaAboveTheCapFallsBackAndCostsConfidence() {
        ValuationEngine.CardResult wild = value("1.5.0", "20.48");
        ValuationEngine.CardResult fallback = value("1.5.0", null);

        assertThat(sigmaLines(wild)).anySatisfy(r -> assertThat(r).contains("measured sigma 20.48 is above the cap of 1.0"));
        // The same FLV and confidence as a card with too few changes to measure.
        assertThat(wild.valuation().flv()).isEqualByComparingTo(fallback.valuation().flv());
        assertThat(wild.valuation().confidence()).isEqualTo(fallback.valuation().confidence());
    }

    @Test
    void aSigmaAtTheCapIsUsedAndOlderSetsUseAnySigma() {
        assertThat(sigmaLines(value("1.5.0", "1.0"))).noneSatisfy(r -> assertThat(r).startsWith("fallback"));
        assertThat(sigmaLines(value("1.4.0", "20.48"))).noneSatisfy(r -> assertThat(r).startsWith("fallback"));
    }
}

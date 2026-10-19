package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StalenessTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private final Staleness staleness = new Staleness(ParameterSet.defaults());

    @ParameterizedTest(name = "{0} days old: {1} levels, A becomes {2}")
    @CsvSource({
            "0, 0, A", "7, 0, A",          // within the grace period: fresh
            "8, 1, B", "37, 1, B",         // first month past grace
            "38, 2, C", "67, 2, C",
            "68, 3, D", "400, 3, D"})      // D: out of the collateral base, never below
    void dropsOneLevelPerMonthPastTheGracePeriod(int daysOld, int levels, Confidence fromA) {
        int n = staleness.levels(TODAY.minusDays(daysOld), TODAY);

        assertThat(n).isEqualTo(levels);
        assertThat(Staleness.drop(Confidence.A, n)).isEqualTo(fromA);
    }
}

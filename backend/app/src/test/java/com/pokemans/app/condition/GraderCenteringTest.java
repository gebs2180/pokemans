package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GraderCenteringTest {

    @Test
    void psaIsTheCapsOwnTableSoTheyAgreeEverywhere() {
        IntStream.rangeClosed(500, 1000).mapToDouble(i -> i / 10.0).forEach(front -> {
            for (Double back : new Double[] {null, 60.0, 75.0, 75.1, 90.0, 95.0}) {
                assertThat(GraderCentering.best(GraderCentering.PSA, front, back))
                        .as("front %s back %s", front, back)
                        .isEqualTo(String.valueOf(PsaCentering.cap(front, back, 0)));
            }
        });
    }

    @Test
    void nothingMeasuredIsNoAnswerAndOneSideAloneLimits() {
        GraderCentering.ALL.forEach(s -> assertThat(GraderCentering.best(s, null, null)).isNull());
        assertThat(GraderCentering.best(GraderCentering.TAG, null, 52.0)).isEqualTo("Pristine 10");
        assertThat(GraderCentering.best(GraderCentering.TAG, 51.0, 53.0)).isEqualTo("Gem Mint 10");
    }

    @Test
    void eachGraderAtItsBoundaries() {
        assertThat(GraderCentering.best(GraderCentering.CGC, 50.0, 60.0)).isEqualTo("Pristine 10");
        assertThat(GraderCentering.best(GraderCentering.CGC, 55.0, 75.0)).isEqualTo("Gem Mint 10");
        assertThat(GraderCentering.best(GraderCentering.CGC, 55.0, 75.1)).isEqualTo("Mint 9");
        assertThat(GraderCentering.best(GraderCentering.CGC, 91.0, null)).isEqualTo("1");
        assertThat(GraderCentering.best(GraderCentering.TAG, 62.5, 80.0)).isEqualTo("NM-MT+ 8.5");
        assertThat(GraderCentering.best(GraderCentering.TAG, 99.0, null)).isEqualTo("1");
        assertThat(GraderCentering.best(GraderCentering.BGS, 50.0, 60.0)).isEqualTo("Pristine 10");
        assertThat(GraderCentering.best(GraderCentering.BGS, 54.0, 61.0)).isEqualTo("Mint 9");
    }

    @Test
    void everyTableIsBestFirst() {
        // Going down a table, a limit may stay or loosen, never tighten: a worse grade can't need better centering.
        GraderCentering.ALL.forEach(s -> {
            for (int i = 1; i < s.allowances().size(); i++) {
                GraderCentering.Allowance better = s.allowances().get(i - 1);
                GraderCentering.Allowance worse = s.allowances().get(i);
                assertThat(worse.maxFrontShare()).as(s.grader() + " " + worse.grade()).isGreaterThanOrEqualTo(better.maxFrontShare());
                assertThat(worse.maxBackShare()).as(s.grader() + " " + worse.grade()).isGreaterThanOrEqualTo(better.maxBackShare());
            }
        });
    }
}

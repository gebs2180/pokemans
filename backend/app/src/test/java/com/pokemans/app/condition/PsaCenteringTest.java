package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PsaCenteringTest {

    @Test
    void frontLimitsAtEachBoundaryWithoutMargin() {
        assertThat(PsaCentering.cap(50.0, null, 0)).isEqualTo(10);
        assertThat(PsaCentering.cap(55.0, null, 0)).isEqualTo(10);
        assertThat(PsaCentering.cap(55.1, null, 0)).isEqualTo(9);
        assertThat(PsaCentering.cap(60.0, null, 0)).isEqualTo(9);
        assertThat(PsaCentering.cap(60.1, null, 0)).isEqualTo(8);
        assertThat(PsaCentering.cap(65.0, null, 0)).isEqualTo(8);
        assertThat(PsaCentering.cap(70.0, null, 0)).isEqualTo(7);
        assertThat(PsaCentering.cap(70.1, null, 0)).isEqualTo(6);
        assertThat(PsaCentering.cap(80.0, null, 0)).isEqualTo(6);
        // 85/15 is both EX 5 and VG-EX 4: the better grade.
        assertThat(PsaCentering.cap(85.0, null, 0)).isEqualTo(5);
        assertThat(PsaCentering.cap(90.0, null, 0)).isEqualTo(3);
        assertThat(PsaCentering.cap(90.1, null, 0)).isEqualTo(1);
    }

    @Test
    void backIsLooserAndTheWorseSideWins() {
        assertThat(PsaCentering.cap(null, 75.0, 0)).isEqualTo(10);
        assertThat(PsaCentering.cap(null, 75.1, 0)).isEqualTo(9);
        assertThat(PsaCentering.cap(null, 90.0, 0)).isEqualTo(9);
        assertThat(PsaCentering.cap(null, 90.1, 0)).isEqualTo(1);
        assertThat(PsaCentering.cap(52.0, 80.0, 0)).isEqualTo(9);
        assertThat(PsaCentering.cap(68.0, 60.0, 0)).isEqualTo(7);
    }

    @Test
    void theMarginCountsAgainstTheCard() {
        // 54.5/45.5 is a 10 as measured, but within a point of the limit.
        assertThat(PsaCentering.cap(54.5, null, 0)).isEqualTo(10);
        assertThat(PsaCentering.cap(54.5, null, 1.0)).isEqualTo(9);
        assertThat(PsaCentering.cap(54.0, null, 1.0)).isEqualTo(10);
    }

    @Test
    void nothingMeasuredIsNoCap() {
        assertThat(PsaCentering.cap(null, null, 1.0)).isNull();
    }
}

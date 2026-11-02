package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pokemans.app.condition.CenteringScoreboard.Reading;
import com.pokemans.app.condition.CenteringScoreboard.Row;
import com.pokemans.app.condition.CenteringScoreboard.Truth;
import java.util.List;
import org.junit.jupiter.api.Test;

class CenteringScoreboardTest {

    /** 50/50 both ways. */
    private static final Truth EVEN = new Truth(2.5, 2.5, 2.5, 2.5);

    private static Reading front(String face, Truth truth, Double left, Double top) {
        return new Reading(face, false, false, face + ".jpg", truth, left, top, left == null ? "refused" : null);
    }

    private static Row row(Reading... readings) {
        return CenteringScoreboard.row("test", List.of(readings), 1);
    }

    @Test
    void errorIsTheWorsePairInPoints() {
        assertThat(front("a", EVEN, 51.0, 47.0).error()).isCloseTo(3, within(1e-9));
    }

    @Test
    void aSideNobodyCouldSeeLeavesOnlyTheOtherPairToCompare() {
        Truth noBottom = new Truth(2.5, 2.5, 2.5, null);
        assertThat(front("a", noBottom, 51.0, 80.0).error()).isCloseTo(1, within(1e-9));
        assertThat(front("a", new Truth(null, null, null, null), 51.0, 80.0).error()).isNull();

        Row row = row(front("a", noBottom, 51.0, 80.0));
        assertThat(row.unseen()).isEqualTo(1);
        assertThat(row.capCompared()).isZero();
    }

    @Test
    void aNumberMoreThanTwoPointsOffIsFalse() {
        Row row = row(front("a", EVEN, 52.0, 50.0), front("b", EVEN, 52.5, 50.0), front("c", EVEN, 50.0, 50.0));
        assertThat(row.scored()).isEqualTo(3);
        assertThat(row.falseMeasurements()).isEqualTo(1);
        assertThat(row.medianError()).isCloseTo(2, within(1e-9));
    }

    @Test
    void refusalsCountAgainstTheShareMeasuredAndNotTheError() {
        Row row = row(front("a", EVEN, 50.5, 50.0), front("b", EVEN, null, null), front("b", EVEN, null, null));
        assertThat(row.photos()).isEqualTo(3);
        assertThat(row.measured()).isEqualTo(1);
        assertThat(row.faces()).isEqualTo(2);
        assertThat(row.facesMeasured()).isEqualTo(1);
        assertThat(row.scored()).isEqualTo(1);
    }

    @Test
    void aFaceWithAnyPhotoMeasuredCountsAsMeasured() {
        Row row = row(front("a", EVEN, 50.5, 50.0), front("a", EVEN, null, null));
        assertThat(row.facesMeasured()).isEqualTo(1);
        assertThat(row.measured()).isEqualTo(1);
    }

    @Test
    void capsAreComparedAtTheSameMarginAndTooHighIsAgainstTheTruthItself() {
        // Truly 54.5/45.5. Measured 53.5: at a margin of 1 that's a 10, and so it would be at the truth
        // with no margin, so it isn't too high. The truth with the margin is 55.5, a 9: not the same cap.
        Truth nearLimit = new Truth(5.45, 4.55, 5.0, 5.0);
        Row near = row(front("a", nearLimit, 53.5, 50.0));
        assertThat(near.capCompared()).isEqualTo(1);
        assertThat(near.capSame()).isZero();
        assertThat(near.capTooHigh()).isZero();

        // Truly 56/44, a 9. Measured 53.5 gives a 10: the failure that would raise a card.
        Row over = row(front("b", new Truth(5.6, 4.4, 5.0, 5.0), 53.5, 50.0));
        assertThat(over.capTooHigh()).isEqualTo(1);
    }

    @Test
    void backsAreCappedOnThePsaBackTable() {
        // 70/30 is a 10 on a back and a 7 at best on a front.
        Truth uneven = new Truth(7.0, 3.0, 5.0, 5.0);
        Reading back = new Reading("a", true, false, "a.jpg", uneven, 70.0, 50.0, null);
        assertThat(back.cap(back.worstShare(), 1)).isEqualTo(10);
        assertThat(front("a", uneven, 70.0, 50.0).cap(70.0, 1)).isEqualTo(6);
    }

    @Test
    void agreementNeedsNoLabelAndTakesTheWorsePair() {
        Row row = row(front("a", null, 50.0, 50.0), front("a", null, 50.5, 51.5), front("a", null, 49.8, 50.2),
                front("b", null, 50.0, 50.0), front("b", null, 50.5, 50.5), front("c", null, 50.0, 50.0));
        assertThat(row.agreementFaces()).isEqualTo(2);
        assertThat(row.agreementWithin()).isEqualTo(1);
        assertThat(row.agreementMedian()).isCloseTo(1.0, within(1e-9));
        assertThat(row.scored()).isZero();
        assertThat(row.medianError()).isNaN();
    }

    @Test
    void groupsSplitHardFrontsFromOtherFrontsAndBacks() {
        List<Reading> readings = List.of(
                new Reading("a", false, true, "a", EVEN, 50.0, 50.0, null),
                new Reading("b", false, false, "b", EVEN, null, null, "refused"),
                new Reading("c", true, false, "c", EVEN, 50.0, 50.0, null));
        List<Row> rows = CenteringScoreboard.score(readings, 1);
        assertThat(rows).extracting(Row::group).containsExactly("all", "hard fronts", "other fronts", "backs");
        assertThat(rows).extracting(Row::photos).containsExactly(3, 1, 1, 1);
        assertThat(rows).extracting(Row::measured).containsExactly(2, 1, 0, 1);
        assertThat(CenteringScoreboard.table(rows)).contains("| hard fronts | 1/1 (100%) |").contains("| other fronts | 0/1 (0%) |");
    }
}

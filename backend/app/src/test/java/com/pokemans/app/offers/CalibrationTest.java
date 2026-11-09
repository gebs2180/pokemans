package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.valuation.Grade;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CalibrationTest {

    private static final Map<Grade, BigDecimal> NEAR_MINT = Map.of(Grade.G9, new BigDecimal("0.5"), Grade.G10, new BigDecimal("0.5"));

    @Test
    void theStressShiftComesFromTheTraceElseTheConfidence() {
        assertThat(Calibration.stressShift("[{\"rule\":\"distribution shifted 3 grades down, floor 1, U held\"}]", "A")).isEqualTo(3);
        assertThat(Calibration.stressShift(null, "B")).isEqualTo(2);
        assertThat(Calibration.stressShift("[]", "C")).isEqualTo(3);
    }

    @Test
    void medianAndStressedMedian() {
        Calibration.Prediction p = Calibration.predict(NEAR_MINT, "B", null);
        assertThat(p.median()).isEqualTo(Grade.G9);
        assertThat(p.stressedMedian()).isEqualTo(Grade.G7);
        assertThat(p.distribution().keySet()).containsExactly("U", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }

    @Test
    void halfGradesFoldDownAndAResultWithoutANumberIsU() {
        Calibration.Prediction p = Calibration.predict(NEAR_MINT, "B", null);
        assertThat(Calibration.compare(p, new BigDecimal("10"))).isEqualTo(new Calibration.Comparison(Grade.G10, true, false));
        assertThat(Calibration.compare(p, new BigDecimal("7.5"))).isEqualTo(new Calibration.Comparison(Grade.G7, false, false));
        assertThat(Calibration.compare(p, new BigDecimal("6.5"))).isEqualTo(new Calibration.Comparison(Grade.G6, false, true));
        assertThat(Calibration.compare(p, null)).isEqualTo(new Calibration.Comparison(Grade.U, false, true));
    }
}

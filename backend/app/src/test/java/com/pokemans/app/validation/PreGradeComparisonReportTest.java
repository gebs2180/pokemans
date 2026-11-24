package com.pokemans.app.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The summary over CardGrade and claude-vision side by side: how far each is from PSA's returned grade. */
class PreGradeComparisonReportTest {

    private static PreGradeComparisonReport.Answer answer(Double psa) {
        return new PreGradeComparisonReport.Answer(UUID.randomUUID(), Instant.now(), psa, psa, null, null, null, null, null, null, null, null);
    }

    private static PreGradeComparisonReport.Row row(Double cardGrade, Double claude, Integer psa) {
        return new PreGradeComparisonReport.Row(UUID.randomUUID(), UUID.randomUUID(), "A card",
                cardGrade == null ? null : answer(cardGrade), answer(claude),
                psa == null ? null : new PreGradeComparisonReport.Returned(BigDecimal.valueOf(psa), "PSA " + psa, null, "SELLER"));
    }

    @Test
    void comparesEachProviderWithPsaOnlyWhereAGradeCameBack() {
        PreGradeComparisonReport.Report report = PreGradeComparisonReport.report(List.of(
                row(9.0, 8.0, 8),     // CardGrade 1 above, Claude exact
                row(10.0, 7.0, 9),    // CardGrade 1 above, Claude 2 below
                row(null, 9.0, 6),    // no CardGrade answer on these photos; Claude 3 above
                row(8.0, 8.0, null))); // no PSA grade yet: listed, not scored

        assertThat(report.cards()).isEqualTo(4);
        assertThat(report.withPsa()).isEqualTo(3);
        assertThat(report.cardGradeMae()).isEqualTo(1.0);
        assertThat(report.claudeMae()).isEqualTo(1.667);
        assertThat(report.cardGradeWithinOne()).isEqualTo(1.0);
        assertThat(report.claudeWithinOne()).isEqualTo(0.333);
        assertThat(report.cardGradeAbove()).isEqualTo(1.0);
        assertThat(report.claudeAbove()).isEqualTo(0.333);
        assertThat(report.rows()).hasSize(4);
    }

    @Test
    void nothingReturnedIsNoScore() {
        PreGradeComparisonReport.Report report = PreGradeComparisonReport.report(List.of(row(9.0, 8.0, null)));

        assertThat(report.withPsa()).isZero();
        assertThat(report.claudeMae()).isNull();
        assertThat(report.cardGradeAbove()).isNull();
    }
}

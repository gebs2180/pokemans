package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reading provider responses (shapes from their published docs and OpenAPI spec, 2026-10-16). */
class ProviderReadingTest {

    @Test
    void ximilarFinishedJobUsesTheCombinedGradeWhenGiven() {
        Map<String, Object> job = Map.of("id", "job-1", "status", "DONE", "response", Map.of(
                "grades", Map.of("final", 7.8),
                "records", List.of(
                        Map.of("_status", Map.of("code", 200, "text", "OK"),
                                "_base64", "AAAA",
                                "grades", Map.of("corners", 8.0, "edges", 8.0, "surface", 6.0, "centering", 9.0, "final", 7.5,
                                        "condition", "Near Mint"),
                                "card", List.of(Map.of(
                                        "centering", Map.of("left/right", "46/54", "top/bottom", "54/46", "grade", 9.0),
                                        "_tags", Map.of("Damaged", List.of(Map.of("name", "OK", "prob", 0.95))))),
                                "versions", Map.of("final", "m-123")),
                        Map.of("grades", Map.of("final", 8.5)))));

        PreGradeProvider.Done done = (PreGradeProvider.Done) XimilarPreGradeProvider.read(job);

        assertThat(done.reading().overallGrade()).isEqualTo(7.8);
        assertThat(done.reading().surface()).isEqualTo(6.0);
        assertThat(done.reading().modelVersion()).isEqualTo("m-123");
        assertThat(done.reading().details()).containsKey("backGrades")
                .containsEntry("frontCentering", Map.of("leftRight", "46/54", "topBottom", "54/46"));
        assertThat(PredictionWorker.withoutImages(done.raw()).toString()).doesNotContain("_base64");
    }

    @Test
    void ximilarWithoutACombinedGradeWeightsFront70Back30() {
        Map<String, Object> job = Map.of("status", "DONE", "response", Map.of("records", List.of(
                Map.of("grades", Map.of("final", 8.0)), Map.of("grades", Map.of("final", 9.0)))));

        assertThat(((PreGradeProvider.Done) XimilarPreGradeProvider.read(job)).reading().overallGrade()).isEqualTo(8.3, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void ximilarRecordWithoutGradesIsARefusal() {
        Map<String, Object> job = Map.of("status", "DONE", "response", Map.of("records", List.of(
                Map.of("_status", Map.of("code", 400, "text", "No card found")))));

        assertThat(XimilarPreGradeProvider.read(job)).isInstanceOf(PreGradeProvider.Failed.class);
    }

    @Test
    void cardGradeCompletedUsesPsaPrediction() {
        Map<String, Object> grading = Map.of("id", "g-1", "status", "completed", "cardName", "Charizard", "results", Map.of(
                "overallGrade", 8.4, "psaPrediction", 8.0, "centering", 9.1, "corners", 8.2, "edges", 8.6, "surface", 7.9,
                "gradeLabel", "NM-MT", "limitingFactor", "surface",
                "cvMetrics", Map.of("centering", Map.of("left_pct", 47, "right_pct", 53))));

        PreGradeProvider.Done done = (PreGradeProvider.Done) CardGradePreGradeProvider.read(grading);

        assertThat(done.reading().overallGrade()).isEqualTo(8.4);
        assertThat(done.reading().psaGrade()).isEqualTo(8.0);
        assertThat(done.reading().details()).containsEntry("limitingFactor", "surface");
    }

    @Test
    void cardGradePendingAndRejected() {
        assertThat(CardGradePreGradeProvider.read(Map.of("status", "pending"))).isInstanceOf(PreGradeProvider.Pending.class);
        PreGradeProvider.Failed failed = (PreGradeProvider.Failed) CardGradePreGradeProvider.read(Map.of("status", "failed",
                "rejection", Map.of("code", "card_cropped", "message", "Take it out of the toploader")));
        assertThat(failed.reason()).contains("toploader");
    }
}

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
    void ximilarWithOnlyABackFinalIsStoredWithNoOverallAndCapsNothing() {
        Map<String, Object> job = Map.of("status", "DONE", "response", Map.of("records", List.of(
                Map.of("grades", Map.of("centering", 6.0)), Map.of("grades", Map.of("final", 9.0)))));

        PreGradeProvider.Reading reading = ((PreGradeProvider.Done) XimilarPreGradeProvider.read(job)).reading();
        assertThat(reading.overallGrade()).isNull();

        PredictionRequest request = new PredictionRequest(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), XimilarPreGradeProvider.NAME,
                List.of(java.util.UUID.randomUUID()), null);
        assertThat(ProviderPrediction.succeeded(request, reading, Map.of(), 1).centeringCap()).isNull();
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
    void cardGradeCenteringIsStoredButCapsNothing() {
        Map<String, Object> grading = Map.of("status", "completed", "results", Map.of(
                "overallGrade", 9.0, "psaPrediction", 9.0, "centering", 6.0,
                "cvMetrics", Map.of("centering", Map.of("score", 600, "left_pct", 35, "right_pct", 65))));
        PreGradeProvider.Reading reading = ((PreGradeProvider.Done) CardGradePreGradeProvider.read(grading)).reading();

        assertThat(reading.centering()).isEqualTo(6.0);
        assertThat(reading.details()).containsKey("providerFrontCentering").doesNotContainKeys("frontCentering", "backCentering");
        PredictionRequest request = new PredictionRequest(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                CardGradePreGradeProvider.NAME, List.of(java.util.UUID.randomUUID()), null);
        ProviderPrediction prediction = ProviderPrediction.succeeded(request, reading, grading, 1);
        assertThat(prediction.centeringCap()).isNull();
        assertThat(ProviderPrediction.centeringCap(List.of(prediction))).isNull();
        assertThat(prediction.gradeForDistribution()).isEqualTo(9.0);
    }

    @Test
    void cardGradeCompletedWithoutAnyGradeIsAFailure() {
        assertThat(CardGradePreGradeProvider.read(Map.of("status", "completed", "results", Map.of())))
                .isInstanceOf(PreGradeProvider.Failed.class);
    }

    /** A real response, saved by CardGradeLiveCheck. */
    private static Map<String, Object> fixture(String name) throws java.io.IOException {
        try (java.io.InputStream in = ProviderReadingTest.class.getResourceAsStream("/cardgrade/" + name + ".json")) {
            assertThat(in).as(name).isNotNull();
            return tools.jackson.databind.json.JsonMapper.builder().build()
                    .readValue(in, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void cardGradeRealCompletedGrading() throws Exception {
        Map<String, Object> grading = fixture("metagross");
        PreGradeProvider.Done done = (PreGradeProvider.Done) CardGradePreGradeProvider.read(grading);
        PreGradeProvider.Reading reading = done.reading();

        assertThat(reading.overallGrade()).isEqualTo(9.0);
        assertThat(reading.psaGrade()).isEqualTo(9.0);
        assertThat(reading.centering()).isEqualTo(10.0);
        assertThat(reading.corners()).isEqualTo(9.0);
        assertThat(reading.edges()).isEqualTo(10.0);
        assertThat(reading.surface()).isEqualTo(10.0);
        assertThat(reading.modelVersion()).isEqualTo("v2@c2666f1b2e696cbcc7d6008179a44c68c44f4c0c");
        assertThat(reading.details())
                .containsEntry("gradeLabel", "MINT")
                .containsEntry("limitingFactor", "corners")
                .containsEntry("bgsPrediction", 9.5)
                .containsEntry("imageQualityWarnings", List.of())
                .doesNotContainKeys("frontCentering", "backCentering");
        assertThat((Map<Object, Object>) reading.details().get("providerFrontCentering")).containsEntry("left_pct", 49).containsEntry("top_pct", 46);
        assertThat((Map<Object, Object>) reading.details().get("providerBackCentering")).containsEntry("left_pct", 52);
        Map<?, ?> identified = (Map<?, ?>) reading.details().get("identifiedAs");
        assertThat(identified.get("name")).isEqualTo("Metagross δ");
        assertThat(identified.get("set")).isNull(); // not the string "null"
        assertThat(identified.get("confidence")).isEqualTo(0.3);
        // The whole response is what gets stored.
        assertThat(done.raw()).isSameAs(grading);

        // Its centering of 10 caps nothing and the PSA prediction is what the distribution uses.
        PredictionRequest request = new PredictionRequest(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                CardGradePreGradeProvider.NAME, List.of(java.util.UUID.randomUUID()), null);
        ProviderPrediction prediction = ProviderPrediction.succeeded(request, reading, done.raw(), 1);
        assertThat(prediction.centeringCap()).isNull();
        assertThat(prediction.gradeForDistribution()).isEqualTo(9.0);
    }

    @Test
    void cardGradeRealFailedGradingIsAFailureWithItsMessage() throws Exception {
        Map<String, Object> grading = fixture("failed/metagross-analyzing");
        PreGradeProvider.Failed failed = (PreGradeProvider.Failed) CardGradePreGradeProvider.read(grading);

        assertThat(failed.reason()).startsWith("CardGrade couldn't grade the card: ").contains("credit has been refunded");
        assertThat(failed.raw()).isSameAs(grading);
    }

    @Test
    void cardGradePendingAndRejected() {
        assertThat(CardGradePreGradeProvider.read(Map.of("status", "pending"))).isInstanceOf(PreGradeProvider.Pending.class);
        assertThat(CardGradePreGradeProvider.read(Map.of("status", "processing"))).isInstanceOf(PreGradeProvider.Pending.class);
        assertThat(CardGradePreGradeProvider.read(Map.of("status", "refunded"))).isInstanceOf(PreGradeProvider.Failed.class);
        PreGradeProvider.Failed failed = (PreGradeProvider.Failed) CardGradePreGradeProvider.read(Map.of("status", "failed",
                "rejection", Map.of("code", "card_cropped", "message", "Take it out of the toploader")));
        assertThat(failed.reason()).contains("toploader");
    }
}

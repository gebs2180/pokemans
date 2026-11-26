package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The claude-vision rubric: what one request holds, how an answer is read, and how samples combine. No network. */
class ClaudeVisionRubricTest {

    private static final List<ClaudeVisionViews.View> VIEWS = List.of(
            new ClaudeVisionViews.View("Front — whole card", new byte[] {1, 2, 3}),
            new ClaudeVisionViews.View("Back — top edge", new byte[] {4, 5, 6}));

    @Test
    @SuppressWarnings("unchecked")
    void eachPictureFollowsItsLabelAndTheRubricIsTheSystemPrompt() {
        Map<String, Object> params = ClaudeVisionRubric.params("claude-opus-5-5", "high", 16000, VIEWS);

        assertThat(params).containsEntry("model", "claude-opus-5-5").containsEntry("max_tokens", 16000)
                .containsEntry("system", ClaudeVisionRubric.SYSTEM);
        assertThat(ClaudeVisionRubric.SYSTEM).contains("weakest sub-grade, not the average", "Back edges", "Holo surface",
                "Do not grade centering");
        Map<String, Object> output = (Map<String, Object>) params.get("output_config");
        assertThat(output).containsEntry("effort", "high");
        assertThat((Map<String, Object>) output.get("format")).containsEntry("type", "json_schema")
                .containsEntry("schema", ClaudeVisionRubric.SCHEMA);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) params.get("messages");
        assertThat(messages).hasSize(1);
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.getFirst().get("content");
        assertThat(content).hasSize(5);
        assertThat(content.get(0)).containsEntry("type", "text").containsEntry("text", "Front — whole card:");
        assertThat(content.get(1)).containsEntry("type", "image");
        assertThat((Map<String, Object>) content.get(1).get("source")).containsEntry("type", "base64")
                .containsEntry("media_type", "image/jpeg").containsEntry("data", "AQID");
        assertThat(content.get(2)).containsEntry("text", "Back — top edge:");
        assertThat((Map<String, Object>) content.get(3).get("source")).containsEntry("data", "BAUG");
        // The cache breakpoint is on the last block, so every sample after the first reads the pictures from cache.
        assertThat(content.get(4)).containsEntry("cache_control", Map.of("type", "ephemeral"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaAsksForTheSubGradesWithNotesAndNoCentering() {
        Map<String, Object> properties = (Map<String, Object>) ClaudeVisionRubric.SCHEMA.get("properties");
        assertThat(properties).containsKeys("corners", "edges", "surface", "overall", "limiting_factor", "hidden_areas")
                .doesNotContainKey("centering");
        assertThat(ClaudeVisionRubric.SCHEMA).containsEntry("additionalProperties", false);
        assertThat((List<String>) ClaudeVisionRubric.SCHEMA.get("required")).contains("corners", "edges", "surface", "overall");
    }

    @Test
    void noEffortLeavesItOut() {
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) ClaudeVisionRubric.params("m", null, 100, VIEWS).get("output_config");
        assertThat(output).doesNotContainKey("effort");
    }

    static Map<String, Object> message(String json) {
        return Map.of("stop_reason", "end_turn", "content", List.of(
                Map.of("type", "thinking", "thinking", "looking", "signature", "sig"),
                Map.of("type", "text", "text", json)));
    }

    static String answer(double corners, double edges, double surface, double overall, String limiting) {
        return """
                {"corners":{"grade":%s,"notes":"back top-left corner: slight fraying"},
                 "edges":{"grade":%s,"notes":"back top edge: 3 white specks"},
                 "surface":{"grade":%s,"notes":"holo: one faint scratch"},
                 "overall":%s,"limiting_factor":"%s","hidden_areas":""}""".formatted(corners, edges, surface, overall, limiting);
    }

    @Test
    void readsAnAnswerAndSnapsGradesDownToHalfPoints() {
        ClaudeVisionRubric.Sample sample = ClaudeVisionRubric.parse(message(answer(8.7, 7.5, 9, 7.4, "edges")));

        assertThat(sample.ok()).isTrue();
        assertThat(sample.grades()).containsEntry("corners", 8.5).containsEntry("edges", 7.5).containsEntry("surface", 9.0);
        assertThat(sample.overall()).isEqualTo(7.0);
        assertThat(sample.limitingFactor()).isEqualTo("edges");
        assertThat(sample.notes()).containsEntry("edges", "back top edge: 3 white specks");
    }

    @Test
    void aRefusalACutOffOrABadAnswerIsAFailedSampleNotAGuess() {
        assertThat(ClaudeVisionRubric.parse(Map.of("stop_reason", "refusal", "content", List.of())).error()).contains("refusal");
        assertThat(ClaudeVisionRubric.parse(Map.of("stop_reason", "max_tokens", "content", List.of())).error()).contains("max_tokens");
        assertThat(ClaudeVisionRubric.parse(message("not json")).error()).contains("isn't JSON");
        assertThat(ClaudeVisionRubric.parse(message("{\"corners\":{\"grade\":11,\"notes\":\"\"}}")).error()).contains("corners");
        assertThat(ClaudeVisionRubric.parse(Map.of("stop_reason", "end_turn", "content", List.of())).error()).contains("no text");
        assertThat(ClaudeVisionRubric.parse(null).ok()).isFalse();
    }

    @Test
    void snapNeverRoundsUpAndRejectsOffScaleValues() {
        assertThat(ClaudeVisionRubric.snap(9.99)).isEqualTo(9.5);
        assertThat(ClaudeVisionRubric.snap(1)).isEqualTo(1.0);
        assertThat(ClaudeVisionRubric.snap(0.5)).isNull();
        assertThat(ClaudeVisionRubric.snap(10.5)).isNull();
        assertThat(ClaudeVisionRubric.snap("9")).isNull();
    }

    @Test
    void anEvenCountTakesTheLowerMiddleAndSpreadIsHighestMinusLowest() {
        assertThat(ClaudeVisionRubric.median(List.of(9.0, 7.0, 8.0))).isEqualTo(8.0);
        assertThat(ClaudeVisionRubric.median(List.of(9.0, 8.0))).isEqualTo(8.0);
        assertThat(ClaudeVisionRubric.median(Arrays.asList(9.0, null, 7.0, 8.5, 8.0))).isEqualTo(8.0);
        assertThat(ClaudeVisionRubric.median(List.of())).isNull();
        assertThat(ClaudeVisionRubric.spread(List.of(9.0, 7.0, 8.0))).isEqualTo(2.0);
    }

    private static ClaudeVisionRubric.Sample sample(double corners, double edges, double surface, double overall, String limiting) {
        return ClaudeVisionRubric.parse(message(answer(corners, edges, surface, overall, limiting)));
    }

    @Test
    void theOverallFollowsTheWeakestMedianSubGradeEvenWhenTheModelSaysHigher() {
        ClaudeVisionRubric.Aggregate aggregate = ClaudeVisionRubric.aggregate(List.of(
                sample(9, 7, 9, 9, "corners"),
                sample(9, 7.5, 9, 9, "edges"),
                sample(8.5, 6.5, 9, 8.5, "edges"),
                ClaudeVisionRubric.Sample.failed("refusal")), null);

        assertThat(aggregate.samplesUsed()).isEqualTo(3);
        assertThat(aggregate.medians()).containsEntry("corners", 9.0).containsEntry("edges", 7.0).containsEntry("surface", 9.0);
        assertThat(aggregate.modelOverall()).isEqualTo(9.0);
        assertThat(aggregate.overall()).isEqualTo(7.0);
        assertThat(aggregate.limitingFactor()).isEqualTo("edges");
        assertThat(aggregate.spreads()).containsEntry("edges", 1.0).containsEntry("overall", 0.5).containsEntry("surface", 0.0);
        assertThat(aggregate.psaGrade()).isEqualTo(7.0);
    }

    @Test
    void ourCenteringCapsTheOverallAndBecomesTheLimitingFactor() {
        ClaudeVisionRubric.Aggregate aggregate = ClaudeVisionRubric.aggregate(List.of(sample(9.5, 9.5, 10, 9.5, "corners")), 8);

        assertThat(aggregate.overall()).isEqualTo(8.0);
        assertThat(aggregate.limitingFactor()).isEqualTo("centering");
    }

    @Test
    void aCapAboveTheModelsGradeChangesNothing() {
        ClaudeVisionRubric.Aggregate aggregate = ClaudeVisionRubric.aggregate(List.of(sample(9.5, 9.5, 10, 9.5, "corners")), 10);

        assertThat(aggregate.overall()).isEqualTo(9.5);
        assertThat(aggregate.limitingFactor()).isEqualTo("corners");
        assertThat(aggregate.psaGrade()).isEqualTo(9.0);
    }

    @Test
    void tiedWeakestSubGradesGoToTheFactorMostSamplesNamed() {
        ClaudeVisionRubric.Aggregate aggregate = ClaudeVisionRubric.aggregate(List.of(
                sample(8, 8, 9, 8, "edges"),
                sample(8, 8, 9, 8, "edges"),
                sample(8, 8, 9, 8, "corners")), null);

        assertThat(aggregate.limitingFactor()).isEqualTo("edges");
    }

    @Test
    void notesComeFromASampleThatGaveTheMedianOverall() {
        ClaudeVisionRubric.Sample low = ClaudeVisionRubric.parse(message(answer(6, 6, 6, 6, "corners").replace("slight fraying", "rounded")));
        ClaudeVisionRubric.Aggregate aggregate = ClaudeVisionRubric.aggregate(List.of(low, sample(8, 8, 8, 8, "corners"),
                sample(9, 9, 9, 9, "corners")), null);

        assertThat(aggregate.modelOverall()).isEqualTo(8.0);
        assertThat(aggregate.notes().get("corners")).isEqualTo("back top-left corner: slight fraying");
    }

    @Test
    void noUsableSampleIsNoReading() {
        assertThat(ClaudeVisionRubric.aggregate(List.of(ClaudeVisionRubric.Sample.failed("x")), 9)).isNull();
    }
}

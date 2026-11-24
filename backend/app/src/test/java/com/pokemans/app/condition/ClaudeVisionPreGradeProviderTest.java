package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pokemans.app.intake.CardOutline;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * claude-vision against a stub of the Batches API on localhost: no live calls. The photos are drawn
 * ({@link CenteringMeasurerTest#photo}), never real ones.
 */
class ClaudeVisionPreGradeProviderTest {

    private static final CardOutline OUTLINE = new CardOutline(CenteringMeasurerTest.QUALITY);
    private static final ClaudeVisionViews VIEWS = new ClaudeVisionViews(OUTLINE);
    private static final CenteringPreGradeProvider CENTERING = new CenteringPreGradeProvider(new CenteringMeasurer(OUTLINE), 1.0);

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static ConditionProperties.ClaudeVision config(URI base, int samples) {
        return new ConditionProperties.ClaudeVision(true, "sk-test", base, "claude-opus-5-5", "high", samples, 16000,
                Duration.ofSeconds(5), 2.00, 10.00);
    }

    private static PreGradeProvider.Photos photos() {
        byte[] front = CenteringMeasurerTest.png(CenteringMeasurerTest.photo(new CenteringMeasurerTest.Borders(60, 40, 60, 60), 2, true));
        byte[] back = CenteringMeasurerTest.png(CenteringMeasurerTest.photo(new CenteringMeasurerTest.Borders(50, 50, 50, 50), -1, true));
        return new PreGradeProvider.Photos(new PreGradeProvider.Image(front, "image/png"), new PreGradeProvider.Image(back, "image/png"));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String result(String id, String answer) {
        String text = answer.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"custom_id":"%s","result":{"type":"succeeded","message":{"model":"claude-opus-5-5","stop_reason":"end_turn",\
                "content":[{"type":"text","text":"%s"}],\
                "usage":{"input_tokens":20,"cache_creation_input_tokens":30000,"cache_read_input_tokens":0,"output_tokens":2000}}}}"""
                .formatted(id, text);
    }

    @Test
    void sendsBothSidesAsNineViewsEachWithOurCentering() {
        ClaudeVisionPreGradeProvider provider = new ClaudeVisionPreGradeProvider(config(URI.create("http://127.0.0.1:1"), 3),
                RestClient.builder(), VIEWS, CENTERING, null);

        ClaudeVisionPreGradeProvider.Prepared prepared = provider.prepare(photos());

        assertThat(prepared.views()).hasSize(18);
        assertThat(prepared.views().getFirst().label()).isEqualTo("Front — whole card");
        assertThat(prepared.views()).extracting(ClaudeVisionViews.View::label)
                .contains("Back — bottom-left corner", "Back — left edge", "Front — top-right corner");
        assertThat(prepared.views()).allSatisfy(v -> assertThat(v.jpeg()).startsWith((byte) 0xFF, (byte) 0xD8));
        assertThat(prepared.centering()).containsEntry("frontViews", "cropped").containsEntry("backViews", "cropped")
                .containsKey("measured");
        assertThat(prepared.centering().get("psaCenteringCap")).isInstanceOf(Integer.class);
    }

    @Test
    void aPhotoWithoutAnOutlineGoesWhole() {
        ClaudeVisionPreGradeProvider provider = new ClaudeVisionPreGradeProvider(config(URI.create("http://127.0.0.1:1"), 3),
                RestClient.builder(), VIEWS, CENTERING, null);
        byte[] blank = CenteringMeasurerTest.png(CenteringMeasurerTest.background(1200, 1600));

        ClaudeVisionPreGradeProvider.Prepared prepared = provider.prepare(
                new PreGradeProvider.Photos(new PreGradeProvider.Image(blank, "image/png"), null));

        assertThat(prepared.views()).extracting(ClaudeVisionViews.View::label).containsExactly("Front — whole photo (card outline not found)");
        assertThat(prepared.centering()).containsEntry("frontViews", "whole photo: card outline not found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBatchHoldsOneIdenticalRequestPerSample() {
        Map<String, Object> body = ClaudeVisionPreGradeProvider.batchBody(config(URI.create("http://x"), 3),
                List.of(new ClaudeVisionViews.View("Front — whole card", new byte[] {1})));

        List<Map<String, Object>> requests = (List<Map<String, Object>>) body.get("requests");
        assertThat(requests).extracting(r -> r.get("custom_id")).containsExactly("sample-0", "sample-1", "sample-2");
        assertThat(requests.get(0).get("params")).isEqualTo(requests.get(2).get("params"));
    }

    @Test
    void submitsABatchThenReadsTheMedianOnceItEnds() throws Exception {
        AtomicReference<String> posted = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicInteger checks = new AtomicInteger();
        List<String> paths = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(exchange.getRequestMethod() + " " + path);
            key.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            if (path.equals("/v1/messages/batches")) {
                posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                reply(exchange, 200, "{\"id\":\"msgbatch_1\",\"processing_status\":\"in_progress\"}");
            } else if (path.equals("/v1/messages/batches/msgbatch_1")) {
                boolean ended = checks.incrementAndGet() > 1;
                reply(exchange, 200, ended
                        ? "{\"id\":\"msgbatch_1\",\"processing_status\":\"ended\",\"results_url\":\"" + base + "/results/msgbatch_1\"}"
                        : "{\"id\":\"msgbatch_1\",\"processing_status\":\"in_progress\"}");
            } else if (path.equals("/results/msgbatch_1")) {
                reply(exchange, 200, String.join("\n",
                        result("sample-2", ClaudeVisionRubricTest.answer(9, 8, 9, 8, "edges")),
                        result("sample-0", ClaudeVisionRubricTest.answer(9, 7.5, 9, 8, "edges")),
                        "{\"custom_id\":\"sample-1\",\"result\":{\"type\":\"errored\",\"error\":{\"type\":\"overloaded_error\"}}}"));
            } else {
                reply(exchange, 404, "{}");
            }
        });
        server.start();
        ClaudeVisionPreGradeProvider provider = new ClaudeVisionPreGradeProvider(config(URI.create(base), 3), RestClient.builder(),
                VIEWS, CENTERING, null) {
            @Override
            Prepared prepare(PreGradeProvider.Photos photos) {
                return new Prepared(List.of(new ClaudeVisionViews.View("Front — whole card", new byte[] {1, 2, 3})),
                        Map.of("psaCenteringCap", 10, "frontViews", "cropped"));
            }
        };

        String job = provider.submit(photos(), "key-1");

        assertThat(job).startsWith("msgbatch_1|");
        assertThat(key.get()).isEqualTo("sk-test");
        assertThat(posted.get()).contains("\"sample-0\"", "\"sample-2\"", "\"claude-opus-5-5\"", "\"data\":\"AQID\"", "json_schema");
        assertThat(provider.poll(job)).isInstanceOf(PreGradeProvider.Pending.class);

        PreGradeProvider.Done done = (PreGradeProvider.Done) provider.poll(job);

        PreGradeProvider.Reading reading = done.reading();
        assertThat(reading.modelVersion()).isEqualTo("claude-opus-5-5/rubric-1");
        // Two usable samples: edges 7.5 and 8, so the lower middle 7.5; overall follows edges.
        assertThat(reading.edges()).isEqualTo(7.5);
        assertThat(reading.corners()).isEqualTo(9.0);
        assertThat(reading.overallGrade()).isEqualTo(7.5);
        assertThat(reading.psaGrade()).isEqualTo(7.5);
        assertThat(reading.centering()).isEqualTo(10.0);
        assertThat(reading.details()).containsEntry("limitingFactor", "edges").containsEntry("samplesRequested", 3)
                .containsEntry("samplesUsed", 2).containsEntry("rubricVersion", "rubric-1").containsEntry("frontViews", "cropped");
        assertThat((Map<String, Object>) (Map<?, ?>) reading.details().get("spread")).containsEntry("edges", 0.5);
        // Two samples' usage: 40 input, 60,000 cache writes at 1.25x, 4,000 output, at $2 / $10 per million.
        assertThat(reading.details().get("costUsd")).isEqualTo(0.1901);
        assertThat((List<?>) done.raw().get("samples")).hasSize(3);
        assertThat(done.raw()).containsEntry("batchId", "msgbatch_1");
        assertThat(paths).contains("POST /v1/messages/batches", "GET /results/msgbatch_1");
    }

    @Test
    void noUsableSampleFailsAndKeepsWhatCameBack() {
        String jsonl = "{\"custom_id\":\"sample-0\",\"result\":{\"type\":\"succeeded\",\"message\":{\"stop_reason\":\"refusal\",\"content\":[]}}}\n"
                + "{\"custom_id\":\"sample-1\",\"result\":{\"type\":\"expired\"}}";

        PreGradeProvider.Poll poll = ClaudeVisionPreGradeProvider.read("b", jsonl, Map.of(), config(URI.create("http://x"), 2));

        assertThat(poll).isInstanceOf(PreGradeProvider.Failed.class);
        PreGradeProvider.Failed failed = (PreGradeProvider.Failed) poll;
        assertThat(failed.reason()).contains("No usable answer", "refusal", "expired");
        assertThat((List<?>) failed.raw().get("samples")).hasSize(2);
    }

    @Test
    void aBadKeyIsRefusedAndOverloadWaits() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (calls.incrementAndGet() == 1) {
                reply(exchange, 401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\"}}");
            } else {
                exchange.getResponseHeaders().add("Retry-After", "17");
                reply(exchange, 529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");
            }
        });
        server.start();
        ClaudeVisionPreGradeProvider provider = new ClaudeVisionPreGradeProvider(
                config(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), 1), RestClient.builder(), VIEWS, CENTERING, null);

        assertThatThrownBy(() -> provider.poll("msgbatch_1|{}")).isInstanceOf(PreGradeProvider.Refused.class);
        assertThatThrownBy(() -> provider.poll("msgbatch_1|{}")).isInstanceOfSatisfying(PreGradeProvider.Busy.class,
                busy -> assertThat(busy.retryAfter()).isEqualTo(Duration.ofSeconds(17)));
    }

    @Test
    void noKeyIsAStartupError() {
        ConditionProperties.ClaudeVision noKey = new ConditionProperties.ClaudeVision(true, " ", URI.create("http://x"), "m", "high", 1,
                100, Duration.ofSeconds(1), 2, 10);
        assertThatThrownBy(() -> new ClaudeVisionPreGradeProvider(noKey, RestClient.builder(), VIEWS, CENTERING, null))
                .isInstanceOf(IllegalStateException.class);
    }
}

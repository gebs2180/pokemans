package com.pokemans.app.condition;

import com.pokemans.app.intake.CardOutline;
import com.pokemans.app.intake.QualityProperties;
import com.pokemans.app.storage.PhotoDecodes;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude grading a card from its photos, in <b>shadow</b>: queued only after CardGrade answers
 * ({@link ConditionService#queueShadow}), stored in {@code provider_prediction} as {@value #NAME}, and
 * never read by the distribution, prices or offers ({@link ProviderPrediction#SHADOW}). It is here to be
 * compared with CardGrade and with PSA's returned grades (docs/claude-vision-shadow.md); any switch of
 * providers is Zach's call once those are in.
 *
 * <p>Each side goes as the card straightened, four corner close-ups and four edge strips
 * ({@link ClaudeVisionViews}), with the fixed rubric ({@link ClaudeVisionRubric}). The same request is
 * sent {@code samples} times through the Message Batches API (half price; answers within the hour,
 * usually minutes), and the median of the answers is stored with their spread. Centering is never the
 * model's: it is our own measurement ({@link CenteringPreGradeProvider}), taken at submit and carried in
 * the job id until the answers are in.
 *
 * <p>A retried submit after a timeout can create a second batch (the Batches API has no idempotency
 * key); in shadow that only costs one more card's worth.
 */
@Component
@ConditionalOnBooleanProperty("pokemans.condition.claude-vision.enabled")
class ClaudeVisionPreGradeProvider implements PreGradeProvider {

    static final String NAME = "claude-vision";

    /** Between the batch id and our centering in the job id. */
    private static final String SEPARATOR = "|";
    private static final Duration DECODE_WAIT = Duration.ofMinutes(2);
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);
    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient http;
    private final ConditionProperties.ClaudeVision config;
    private final ClaudeVisionViews views;
    private final CenteringPreGradeProvider centering;
    private final PhotoDecodes decodes;

    @Autowired
    ClaudeVisionPreGradeProvider(ConditionProperties properties, QualityProperties quality, PhotoDecodes decodes) {
        this(properties.claudeVision(), RestClient.builder().requestFactory(requestFactory(properties.claudeVision().timeout())),
                new ClaudeVisionViews(new CardOutline(quality)),
                new CenteringPreGradeProvider(new CenteringMeasurer(new CardOutline(quality)), properties.centering().measurementMargin()),
                decodes);
    }

    /** Package-private for tests: a builder bound to a stub server; {@code decodes} may be null. */
    ClaudeVisionPreGradeProvider(ConditionProperties.ClaudeVision config, RestClient.Builder builder, ClaudeVisionViews views,
                                 CenteringPreGradeProvider centering, PhotoDecodes decodes) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("pokemans.condition.claude-vision.api-key (or ANTHROPIC_API_KEY) must be set when it is enabled");
        }
        this.http = builder
                .baseUrl(config.baseUrl().toString())
                .defaultHeader("x-api-key", config.apiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        this.config = config;
        this.views = views;
        this.centering = centering;
        this.decodes = decodes;
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration timeout) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        requests.setReadTimeout(timeout);
        return requests;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** What submit prepared from the photos: the pictures to send and our own centering. */
    record Prepared(List<ClaudeVisionViews.View> views, Map<String, Object> centering) {
    }

    @Override
    public String submit(Photos photos, String idempotencyKey) {
        Prepared prepared;
        try {
            prepared = decodes == null ? prepare(photos) : decodes.withTurn(DECODE_WAIT, () -> prepare(photos));
        } catch (PhotoDecodes.Busy e) {
            throw new Busy(e.getMessage(), DEFAULT_RETRY_AFTER);
        }
        Map<String, Object> batch;
        try {
            batch = http.post()
                    .uri("/v1/messages/batches")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(batchBody(config, prepared.views()))
                    .retrieve()
                    .body(OBJECT);
        } catch (RestClientResponseException e) {
            throw translate(e);
        }
        Object id = batch == null ? null : batch.get("id");
        if (id == null) {
            throw new IllegalStateException("The Batches API accepted the request but returned no id");
        }
        return id + SEPARATOR + JSON.writeValueAsString(prepared.centering());
    }

    /** Package-private for tests: the views of both sides and our centering, from the photos. */
    Prepared prepare(Photos photos) {
        List<ClaudeVisionViews.View> all = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        all.addAll(side("Front", photos.front(), summary));
        if (photos.back() != null) {
            all.addAll(side("Back", photos.back(), summary));
        }
        if (all.isEmpty()) {
            throw new Refused("Neither photo decodes");
        }
        Poll measured = centering.grade(photos);
        switch (measured) {
            case Done done -> {
                summary.put("psaCenteringCap", done.reading().centering() == null ? null : done.reading().centering().intValue());
                summary.put("measured", done.raw());
            }
            case Failed failed -> summary.put("unmeasured", failed.reason());
            case Pending pending -> {
            }
        }
        return new Prepared(all, summary);
    }

    private List<ClaudeVisionViews.View> side(String side, Image photo, Map<String, Object> summary) {
        Optional<List<ClaudeVisionViews.View>> cropped = views.of(side, photo.bytes());
        if (cropped.isPresent()) {
            summary.put(side.toLowerCase() + "Views", "cropped");
            return cropped.get();
        }
        Optional<ClaudeVisionViews.View> whole = ClaudeVisionViews.whole(side, photo.bytes());
        summary.put(side.toLowerCase() + "Views", whole.isPresent() ? "whole photo: card outline not found" : "photo doesn't decode");
        return whole.map(List::of).orElse(List.of());
    }

    /** Package-private for tests: {@code samples} identical requests, {@code sample-0} onwards. */
    static Map<String, Object> batchBody(ConditionProperties.ClaudeVision config, List<ClaudeVisionViews.View> views) {
        Map<String, Object> params = ClaudeVisionRubric.params(config.model(), config.effort(), config.maxTokens(), views);
        List<Map<String, Object>> requests = new ArrayList<>();
        for (int i = 0; i < config.samples(); i++) {
            requests.add(Map.of("custom_id", "sample-" + i, "params", params));
        }
        return Map.of("requests", requests);
    }

    @Override
    public Poll poll(String externalRequestId) {
        int split = externalRequestId.indexOf(SEPARATOR);
        String batchId = split < 0 ? externalRequestId : externalRequestId.substring(0, split);
        Map<String, Object> ours = split < 0 ? Map.of() : JSON.readValue(externalRequestId.substring(split + 1),
                JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        try {
            Map<String, Object> batch = http.get().uri("/v1/messages/batches/{id}", batchId).retrieve().body(OBJECT);
            if (batch == null || !"ended".equals(batch.get("processing_status"))) {
                return Poll.pending();
            }
            Object url = batch.get("results_url");
            if (url == null) {
                return Poll.failed("The batch ended without results", Map.of("batch", batch));
            }
            String jsonl = http.get().uri(URI.create(url.toString())).retrieve().body(String.class);
            return read(batchId, jsonl, ours, config);
        } catch (RestClientResponseException e) {
            throw translate(e);
        }
    }

    /**
     * Package-private for tests: the reading from a batch's results file. Every sample is kept in raw,
     * usable or not, with its usage; the cost is estimated from the token counts at batch prices.
     */
    static Poll read(String batchId, String jsonl, Map<String, Object> ours, ConditionProperties.ClaudeVision config) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String line : jsonl == null ? new String[0] : jsonl.split("\\R")) {
            if (!line.isBlank()) {
                results.add(JSON.readValue(line, JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
            }
        }
        results.sort(java.util.Comparator.comparing(r -> String.valueOf(r.get("custom_id"))));
        List<ClaudeVisionRubric.Sample> samples = new ArrayList<>();
        List<Map<String, Object>> stored = new ArrayList<>();
        long input = 0;
        long output = 0;
        long cacheWrite = 0;
        long cacheRead = 0;
        for (Map<String, Object> result : results) {
            Map<String, Object> outcome = map(result.get("result"));
            Map<String, Object> message = map(outcome.get("message"));
            ClaudeVisionRubric.Sample sample = "succeeded".equals(outcome.get("type"))
                    ? ClaudeVisionRubric.parse(message)
                    : ClaudeVisionRubric.Sample.failed("batch result " + outcome.get("type") + ": " + outcome.get("error"));
            samples.add(sample);
            Map<String, Object> usage = map(message.get("usage"));
            input += count(usage.get("input_tokens"));
            output += count(usage.get("output_tokens"));
            cacheWrite += count(usage.get("cache_creation_input_tokens"));
            cacheRead += count(usage.get("cache_read_input_tokens"));
            Map<String, Object> kept = new LinkedHashMap<>();
            kept.put("customId", result.get("custom_id"));
            kept.put("type", outcome.get("type"));
            kept.put("error", sample.error());
            kept.put("model", message.get("model"));
            kept.put("stopReason", message.get("stop_reason"));
            kept.put("content", withoutSignatures(message.get("content")));
            kept.put("usage", usage);
            stored.add(kept);
        }
        double cost = (input + cacheWrite * 1.25 + cacheRead * 0.1) * config.inputUsdPerMtok() / 1_000_000
                + output * config.outputUsdPerMtok() / 1_000_000;
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", input);
        usage.put("outputTokens", output);
        usage.put("cacheWriteTokens", cacheWrite);
        usage.put("cacheReadTokens", cacheRead);
        usage.put("costUsd", Math.round(cost * 10_000) / 10_000.0);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("batchId", batchId);
        raw.put("samples", stored);
        raw.put("centering", ours);
        raw.put("usage", usage);

        Integer cap = ours.get("psaCenteringCap") instanceof Number n ? n.intValue() : null;
        ClaudeVisionRubric.Aggregate aggregate = ClaudeVisionRubric.aggregate(samples, cap);
        if (aggregate == null) {
            return Poll.failed("No usable answer from " + samples.size() + " samples: "
                    + samples.stream().map(ClaudeVisionRubric.Sample::error).distinct().toList(), raw);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rubricVersion", ClaudeVisionRubric.VERSION);
        details.put("limitingFactor", aggregate.limitingFactor());
        details.put("modelOverallMedian", aggregate.modelOverall());
        details.put("spread", aggregate.spreads());
        details.put("samplesRequested", config.samples());
        details.put("samplesUsed", aggregate.samplesUsed());
        details.put("samples", samples.stream().filter(ClaudeVisionRubric.Sample::ok).map(s -> {
            Map<String, Object> one = new LinkedHashMap<>(s.grades());
            one.put("overall", s.overall());
            one.put("limitingFactor", s.limitingFactor());
            return one;
        }).toList());
        details.put("notes", aggregate.notes());
        details.put("hiddenAreas", aggregate.hiddenAreas());
        details.put("psaCenteringCap", cap);
        details.put("frontViews", ours.get("frontViews"));
        details.put("backViews", ours.get("backViews"));
        details.put("costUsd", usage.get("costUsd"));
        String model = stored.stream().map(s -> s.get("model")).filter(java.util.Objects::nonNull).map(Object::toString)
                .findFirst().orElse(config.model());
        Map<String, Double> m = aggregate.medians();
        return Poll.done(new Reading(model + "/" + ClaudeVisionRubric.VERSION, aggregate.overall(), aggregate.psaGrade(),
                cap == null ? null : cap.doubleValue(), m.get("corners"), m.get("edges"), m.get("surface"), details), raw);
    }

    /**
     * 429 and 529 (overloaded) wait; a bad request, key or batch id fails without retrying; anything
     * else (5xx) is retried by the worker.
     */
    private static RuntimeException translate(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String said = "The Anthropic API said " + status + ": " + e.getResponseBodyAsString();
        if (status == 429 || status == 529) {
            return new Busy(said, retryAfter(e.getResponseHeaders()));
        }
        if (status == 400 || status == 401 || status == 403 || status == 404 || status == 413) {
            return new Refused(said);
        }
        return e;
    }

    private static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return value == null ? DEFAULT_RETRY_AFTER : Duration.ofSeconds(Math.max(1, Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY_AFTER;
        }
    }

    /** Thinking signatures are opaque and large: everything else in the content is kept. */
    private static Object withoutSignatures(Object content) {
        if (!(content instanceof List<?> blocks)) {
            return content;
        }
        return blocks.stream().map(b -> {
            if (!(b instanceof Map<?, ?> block)) {
                return b;
            }
            Map<Object, Object> copy = new LinkedHashMap<>(block);
            copy.remove("signature");
            copy.remove("data");
            return copy;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static long count(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }
}

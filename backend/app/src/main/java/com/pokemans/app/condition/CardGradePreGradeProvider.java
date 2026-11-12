package com.pokemans.app.condition;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * <a href="https://cardgrade.io/api-docs">CardGrade.io</a> v1 API (checked against its OpenAPI spec on
 * 2026-11-10): multipart upload of front and back to {@code POST /api/v1/grade}, then poll
 * {@code GET /api/v1/grade/{id}}. One credit per card, front and back together; a failed grade is
 * refunded. The Idempotency-Key header means a retried submit returns the first grading and never
 * pays twice.
 *
 * <p>Limits are kept on our side ({@link RateWindow}): the plan's grades and reads per minute. A 429
 * waits for its Retry-After. Out of credits (402), a bad key or a plan without API access fails the
 * request without retrying, so the card waits for Ask again or a reviewer; after a 402, submits stop for
 * {@code outOfCreditsPause} so the rest of the queue doesn't each call CardGrade to hear the same.
 *
 * <p>Its terms let it train on uploads unless the account opts out in Settings: opt out before
 * enabling (docs/pregrade-providers.md).
 */
@Component
@ConditionalOnBooleanProperty("pokemans.condition.cardgrade.enabled")
class CardGradePreGradeProvider implements PreGradeProvider {

    private static final Logger log = LoggerFactory.getLogger(CardGradePreGradeProvider.class);

    static final String NAME = "CARDGRADE";

    /** CardGrade's per-image limit. Our uploads can be larger; those are re-encoded smaller first. */
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** Still being graded. The docs list {@code processing} and {@code identified} beside {@code pending}. */
    private static final Set<String> IN_PROGRESS = Set.of("pending", "processing", "identified");

    /** How long to wait after a 429 that gives no Retry-After: the limits are per minute. */
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ERROR = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final String gradingCompany;
    private final Clock clock;
    private final RateWindow grades;
    private final RateWindow reads;
    private final Duration outOfCreditsPause;
    private volatile Instant outOfCreditsUntil = Instant.MIN;

    @Autowired
    CardGradePreGradeProvider(ConditionProperties properties) {
        this(properties, RestClient.builder().requestFactory(requestFactory(properties.cardgrade().timeout())), Clock.systemUTC());
    }

    /** Package-private for tests: a builder bound to a mock server, and a clock. */
    CardGradePreGradeProvider(ConditionProperties properties, RestClient.Builder builder, Clock clock) {
        ConditionProperties.CardGrade config = properties.cardgrade();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("pokemans.condition.cardgrade.api-key must be set when CardGrade is enabled");
        }
        this.http = builder
                .baseUrl(config.baseUrl().toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .build();
        this.gradingCompany = config.gradingCompany();
        this.clock = clock;
        this.grades = new RateWindow(config.gradesPerMinute(), clock);
        this.reads = new RateWindow(config.readsPerMinute(), clock);
        this.outOfCreditsPause = config.outOfCreditsPause();
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

    @Override
    public String submit(Photos photos, String idempotencyKey) {
        if (clock.instant().isBefore(outOfCreditsUntil)) {
            throw new Refused("CardGrade is out of credits (until at least " + outOfCreditsUntil + "); not sent");
        }
        waitForSlot(grades, "grades");
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("frontImage", part("front", photos.front()));
        if (photos.back() != null) {
            form.add("backImage", part("back", photos.back()));
        }
        form.add("gradingCompany", gradingCompany);
        form.add("cardType", "Pokemon Card");
        Map<String, Object> accepted;
        try {
            accepted = http.post()
                    .uri("/api/v1/grade")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(OBJECT);
        } catch (RestClientResponseException e) {
            throw translate(e, true);
        }
        Object id = accepted == null ? null : accepted.get("id");
        if (id == null) {
            throw new IllegalStateException("CardGrade accepted the request but returned no id");
        }
        if (accepted.get("creditsRemaining") instanceof Number left) {
            log.info("CardGrade accepted grading {}; {} credits left", id, left);
            if (left.intValue() <= 0) {
                // This one was paid for; the next would be refused.
                outOfCreditsUntil = clock.instant().plus(outOfCreditsPause);
            }
        }
        return id.toString();
    }

    @Override
    public java.util.Optional<Instant> submitsPausedUntil(Instant now) {
        return now.isBefore(outOfCreditsUntil) ? java.util.Optional.of(outOfCreditsUntil) : java.util.Optional.empty();
    }

    @Override
    public Poll poll(String externalRequestId) {
        waitForSlot(reads, "reads");
        try {
            return read(http.get().uri("/api/v1/grade/{id}", externalRequestId).retrieve().body(OBJECT));
        } catch (RestClientResponseException e) {
            throw translate(e, false);
        }
    }

    private static void waitForSlot(RateWindow window, String what) {
        Duration wait = window.tryAcquire();
        if (!wait.isZero()) {
            throw new Busy("Our CardGrade " + what + "-per-minute limit is used up", wait.plusSeconds(1));
        }
    }

    /**
     * What a CardGrade error means for the request. 429 waits; 402 (out of credits), 400, 401, 403 and a
     * 404 on a poll fail it, since asking again can't help; anything else (5xx) is retried by the
     * worker, and the Idempotency-Key keeps a retried submit from paying twice.
     */
    private RuntimeException translate(RestClientResponseException e, boolean submitting) {
        String body = e.getResponseBodyAsString();
        String code = first(CODE, body);
        String message = first(ERROR, body);
        String said = "CardGrade said " + e.getStatusCode().value() + (code == null ? "" : " " + code)
                + (message == null ? "" : ": " + message);
        int status = e.getStatusCode().value();
        if (status == 429) {
            return new Busy(said, retryAfter(e.getResponseHeaders()));
        }
        if (status == 402) {
            outOfCreditsUntil = clock.instant().plus(outOfCreditsPause);
            return new Refused(said + " (out of credits; the monthly grades are used up)");
        }
        if (status == 400 || status == 401 || status == 403 || (status == 404 && !submitting)) {
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

    private static String first(Pattern pattern, String body) {
        Matcher m = body == null ? null : pattern.matcher(body);
        return m != null && m.find() ? m.group(1) : null;
    }

    /**
     * Package-private for tests: reads a grading in any state.
     *
     * <p>The distribution uses {@code psaPrediction} (ProviderPrediction#gradeForDistribution), falling
     * back on {@code overallGrade}. Its centering, both the sub-grade and the percentages, is stored but
     * never caps or moves the distribution: only our own measurement does (CLAUDE.md step 2).
     */
    static Poll read(Map<String, Object> grading) {
        String status = grading == null ? null : String.valueOf(grading.get("status"));
        if (IN_PROGRESS.contains(status)) {
            return Poll.pending();
        }
        if (!"completed".equals(status)) {
            Map<String, Object> rejection = map(grading == null ? null : grading.get("rejection"));
            Object message = rejection.get("message") != null ? rejection.get("message") : "status " + status;
            return Poll.failed("CardGrade couldn't grade the card: " + message, grading);
        }
        Map<String, Object> results = map(grading.get("results"));
        Double overall = number(results.get("overallGrade"));
        if (overall == null) {
            overall = number(grading.get("overallGrade"));
        }
        Double psa = number(results.get("psaPrediction"));
        if (overall == null && psa == null) {
            return Poll.failed("CardGrade completed the grading without a grade", grading);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("gradeLabel", results.get("gradeLabel"));
        details.put("limitingFactor", results.get("limitingFactor"));
        details.put("bgsPrediction", results.get("bgsPrediction"));
        details.put("cgcPrediction", results.get("cgcPrediction"));
        // Not "frontCentering"/"backCentering": those keys hold our own measurement's per-side caps.
        details.put("providerFrontCentering", map(map(results.get("cvMetrics")).get("centering")));
        details.put("providerBackCentering", map(map(results.get("backCvMetrics")).get("centering")));
        details.put("marketValueLow", results.get("marketValueLow"));
        details.put("marketValueHigh", results.get("marketValueHigh"));
        // What CardGrade thought the card was, kept for comparison; our catalog ID is never taken from it.
        Map<String, Object> pipeline = map(results.get("details"));
        Map<String, Object> identified = new LinkedHashMap<>();
        identified.put("name", grading.get("cardName"));
        identified.put("set", grading.get("cardSet"));
        identified.put("number", grading.get("cardNumber"));
        identified.put("confidence", pipeline.get("identificationConfidence"));
        identified.put("reviewReason", pipeline.get("reviewReason"));
        details.put("identifiedAs", identified);
        details.put("imageQualityWarnings", pipeline.get("imageQualityWarnings"));
        Object version = pipeline.get("pipelineVersion");
        Object commit = pipeline.get("pipelineCommit");
        String modelVersion = version == null && commit == null ? null
                : (version == null ? "" : version.toString()) + (commit == null ? "" : "@" + commit);
        return Poll.done(new Reading(modelVersion, overall, psa, number(results.get("centering")),
                number(results.get("corners")), number(results.get("edges")), number(results.get("surface")), details), grading);
    }

    private static HttpEntity<ByteArrayResource> part(String name, Image image) {
        Image sized = image.bytes().length <= MAX_IMAGE_BYTES ? image : ImageResizer.jpegUnder(image, MAX_IMAGE_BYTES);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(sized.contentType()));
        String extension = sized.contentType().endsWith("png") ? ".png" : ".jpg";
        ByteArrayResource resource = new ByteArrayResource(sized.bytes()) {
            @Override
            public String getFilename() {
                return name + extension;
            }
        };
        return new HttpEntity<>(resource, headers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}

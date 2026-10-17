package com.pokemans.app.condition;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
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

/**
 * <a href="https://cardgrade.io/api-docs">CardGrade.io</a> v1 API: multipart upload of front and back,
 * then poll. One credit per card; a failed grade is refunded. Uses the Idempotency-Key header, so a
 * retried submit never pays twice. Its terms let it train on uploads unless the account opts out in
 * Settings: opt out before enabling (docs/pregrade-providers.md).
 */
@Component
@ConditionalOnBooleanProperty("pokemans.condition.cardgrade.enabled")
class CardGradePreGradeProvider implements PreGradeProvider {

    static final String NAME = "CARDGRADE";

    /** CardGrade's per-image limit. Our uploads can be larger; those are re-encoded smaller first. */
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final String gradingCompany;

    CardGradePreGradeProvider(ConditionProperties properties) {
        ConditionProperties.CardGrade config = properties.cardgrade();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("pokemans.condition.cardgrade.api-key must be set when CardGrade is enabled");
        }
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        requests.setReadTimeout(config.timeout());
        this.http = RestClient.builder()
                .baseUrl(config.baseUrl().toString())
                .defaultHeader("Authorization", "Bearer " + config.apiKey())
                .requestFactory(requests)
                .build();
        this.gradingCompany = config.gradingCompany();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String submit(Photos photos, String idempotencyKey) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("frontImage", part("front", photos.front()));
        if (photos.back() != null) {
            form.add("backImage", part("back", photos.back()));
        }
        form.add("gradingCompany", gradingCompany);
        form.add("cardType", "Pokemon Card");
        Map<String, Object> accepted = http.post()
                .uri("/api/v1/grade")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(OBJECT);
        Object id = accepted == null ? null : accepted.get("id");
        if (id == null) {
            throw new IllegalStateException("CardGrade accepted the request but returned no id");
        }
        return id.toString();
    }

    @Override
    public Poll poll(String externalRequestId) {
        Map<String, Object> grading = http.get().uri("/api/v1/grade/{id}", externalRequestId).retrieve().body(OBJECT);
        return read(grading);
    }

    /** Package-private for tests: reads a grading in any state. */
    static Poll read(Map<String, Object> grading) {
        String status = grading == null ? null : String.valueOf(grading.get("status"));
        if ("pending".equals(status)) {
            return Poll.pending();
        }
        if (!"completed".equals(status)) {
            Map<String, Object> rejection = map(grading == null ? null : grading.get("rejection"));
            Object message = rejection.getOrDefault("message", "status " + status);
            return Poll.failed("CardGrade couldn't grade the card: " + message, grading);
        }
        Map<String, Object> results = map(grading.get("results"));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("gradeLabel", results.get("gradeLabel"));
        details.put("limitingFactor", results.get("limitingFactor"));
        details.put("bgsPrediction", results.get("bgsPrediction"));
        details.put("cgcPrediction", results.get("cgcPrediction"));
        details.put("frontCentering", map(map(results.get("cvMetrics")).get("centering")));
        details.put("backCentering", map(map(results.get("backCvMetrics")).get("centering")));
        details.put("identifiedAs", Map.of(
                "name", String.valueOf(grading.get("cardName")),
                "set", String.valueOf(grading.get("cardSet")),
                "number", String.valueOf(grading.get("cardNumber"))));
        Double overall = number(results.get("overallGrade"));
        if (overall == null) {
            overall = number(grading.get("overallGrade"));
        }
        return Poll.done(new Reading(null, overall, number(results.get("psaPrediction")), number(results.get("centering")),
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

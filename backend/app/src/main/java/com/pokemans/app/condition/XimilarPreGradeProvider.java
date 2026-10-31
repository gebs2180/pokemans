package com.pokemans.app.condition;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * <a href="https://docs.ximilar.com/collectibles/card-grading">Ximilar card grading</a> through its
 * asynchronous request API: front and back in one job (100 credits), which Ximilar weights 70/30.
 * Ximilar keeps the images for a week so we can see its visualization, then removes them
 * (docs/pregrade-providers.md).
 */
@Component
@ConditionalOnBooleanProperty("pokemans.condition.ximilar.enabled")
class XimilarPreGradeProvider implements PreGradeProvider {

    static final String NAME = "XIMILAR";

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;

    XimilarPreGradeProvider(ConditionProperties properties) {
        ConditionProperties.Ximilar config = properties.ximilar();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("pokemans.condition.ximilar.api-key must be set when Ximilar is enabled");
        }
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        requests.setReadTimeout(config.timeout());
        this.http = RestClient.builder()
                .baseUrl(config.baseUrl().toString())
                .defaultHeader("Authorization", "Token " + config.apiKey())
                .requestFactory(requests)
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String submit(Photos photos, String idempotencyKey) {
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(Map.of("_base64", Base64.getEncoder().encodeToString(photos.front().bytes())));
        if (photos.back() != null) {
            records.add(Map.of("_base64", Base64.getEncoder().encodeToString(photos.back().bytes())));
        }
        Map<String, Object> response = http.post()
                .uri("/account/v2/request/")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "card-grader", "endpoint", "grade", "records", records))
                .retrieve()
                .body(OBJECT);
        Object id = response == null ? null : response.get("id");
        if (id == null) {
            throw new IllegalStateException("Ximilar accepted the request but returned no id");
        }
        return id.toString();
    }

    @Override
    public Poll poll(String externalRequestId) {
        Map<String, Object> job = http.get().uri("/account/v2/request/{id}", externalRequestId).retrieve().body(OBJECT);
        String status = job == null ? null : String.valueOf(job.get("status"));
        if ("CREATED".equals(status) || "PROCESSING".equals(status)) {
            return Poll.pending();
        }
        if (!"DONE".equals(status)) {
            return Poll.failed("Ximilar job ended with status " + status, job);
        }
        return read(job);
    }

    /** Package-private for tests: reads a finished job. */
    static Poll read(Map<String, Object> job) {
        Map<String, Object> response = map(job.get("response"));
        List<Map<String, Object>> records = list(response.get("records"));
        if (records.isEmpty()) {
            return Poll.failed("Ximilar returned no records", job);
        }
        Map<String, Object> front = records.get(0);
        Map<String, Object> back = records.size() > 1 ? records.get(1) : Map.of();
        Map<String, Object> status = map(front.get("_status"));
        Map<String, Object> frontGrades = map(front.get("grades"));
        if (frontGrades.isEmpty()) {
            Object text = status.getOrDefault("text", "no grades returned");
            return Poll.failed("Ximilar couldn't grade the card: " + text, job);
        }
        Map<String, Object> combined = map(response.get("grades"));
        Map<String, Object> backGrades = map(back.get("grades"));
        Double overall = number(combined.get("final"));
        if (overall == null) {
            Double frontFinal = number(frontGrades.get("final"));
            Double backFinal = number(backGrades.get("final"));
            overall = frontFinal;
            if (frontFinal != null && backFinal != null) {
                overall = 0.7 * frontFinal + 0.3 * backFinal;
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("condition", frontGrades.get("condition"));
        details.put("frontGrades", frontGrades);
        if (!backGrades.isEmpty()) {
            details.put("backGrades", backGrades);
        }
        Map<String, Object> card = list(front.get("card")).stream().findFirst().orElse(Map.of());
        Map<String, Object> centering = map(card.get("centering"));
        details.put("frontCentering", Map.of("leftRight", String.valueOf(centering.get("left/right")),
                "topBottom", String.valueOf(centering.get("top/bottom"))));
        Map<String, Object> tags = map(card.get("_tags"));
        list(tags.get("Damaged")).stream().findFirst().ifPresent(d -> details.put("damaged", d));
        String version = String.valueOf(map(front.get("versions")).getOrDefault("final", ""));
        return Poll.done(new Reading(version.isBlank() ? null : version, overall, null,
                number(frontGrades.get("centering")), number(frontGrades.get("corners")),
                number(frontGrades.get("edges")), number(frontGrades.get("surface")), details), job);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        if (!(value instanceof List<?> l)) {
            return List.of();
        }
        return l.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList();
    }

    private static Double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}

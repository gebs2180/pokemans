package com.pokemans.app.pricing;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * PokeTrace over HTTP with the {@code X-API-Key} header. A 429 or 5xx is retried a few times,
 * waiting as {@code Retry-After} says (at most 30 seconds); anything else fails the card's fetch,
 * which the ingestion run records and moves past.
 */
@Component
@ConditionalOnBooleanProperty("pokemans.pricing.poketrace.enabled")
class HttpPokeTraceApi implements PokeTraceApi {

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };
    private static final int PAGE = 50;
    private static final int ATTEMPTS = 4;
    /** A year of daily rows is about 8 pages; this stops a runaway cursor. */
    private static final int MAX_PAGES = 60;

    private final RestClient http;

    HttpPokeTraceApi(PricingProperties properties) {
        PricingProperties.PokeTrace config = properties.poketrace();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("pokemans.pricing.poketrace.api-key must be set when PokeTrace is enabled");
        }
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        requests.setReadTimeout(config.timeout());
        this.http = RestClient.builder()
                .baseUrl(config.baseUrl().toString())
                .defaultHeader("X-API-Key", config.apiKey())
                .defaultHeader("Accept", "application/json")
                .requestFactory(requests)
                .build();
    }

    @Override
    public List<Map<String, Object>> search(String text) {
        Map<String, Object> body = get(u -> u.path("/cards").queryParam("search", text).queryParam("game", "pokemon")
                .queryParam("market", "US").queryParam("limit", PAGE).build());
        return rows(body);
    }

    @Override
    public Map<String, Object> card(String poketraceId) {
        Map<String, Object> body = get(u -> u.path("/cards/{id}").build(poketraceId));
        return body != null && body.get("data") instanceof Map<?, ?> d ? cast(d) : Map.of();
    }

    @Override
    public List<Map<String, Object>> history(String poketraceId, String tier, String period) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String at = cursor;
            Map<String, Object> body = get(u -> {
                UriBuilder b = u.path("/cards/{id}/prices/{tier}/history").queryParam("period", period).queryParam("limit", PAGE);
                if (at != null) {
                    b.queryParam("cursor", at);
                }
                return b.build(poketraceId, tier);
            });
            rows.addAll(rows(body));
            Map<?, ?> pagination = body != null && body.get("pagination") instanceof Map<?, ?> p ? p : Map.of();
            if (!Boolean.TRUE.equals(pagination.get("hasMore")) || pagination.get("nextCursor") == null) {
                return rows;
            }
            cursor = pagination.get("nextCursor").toString();
        }
        throw new IllegalStateException("PokeTrace history for " + poketraceId + " " + tier + " ran past " + MAX_PAGES + " pages");
    }

    private Map<String, Object> get(java.util.function.Function<UriBuilder, java.net.URI> uri) {
        for (int attempt = 1; ; attempt++) {
            try {
                return http.get().uri(uri).retrieve().body(OBJECT);
            } catch (HttpStatusCodeException e) {
                HttpStatusCode status = e.getStatusCode();
                if (attempt >= ATTEMPTS || !(status.value() == 429 || status.is5xxServerError())) {
                    throw e;
                }
                sleep(retryAfter(e, attempt));
            }
        }
    }

    private static Duration retryAfter(HttpStatusCodeException e, int attempt) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        long seconds = (long) Math.pow(2, attempt);
        if (header != null) {
            try {
                seconds = Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) {
                // An HTTP date: fall back to backing off.
            }
        }
        return Duration.ofSeconds(Math.clamp(seconds, 1, 30));
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting to retry PokeTrace", e);
        }
    }

    private static List<Map<String, Object>> rows(Map<String, Object> body) {
        if (body == null || !(body.get("data") instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                rows.add(cast(m));
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}

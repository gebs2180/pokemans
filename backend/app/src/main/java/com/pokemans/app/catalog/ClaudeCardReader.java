package com.pokemans.app.catalog;

import java.net.http.HttpClient;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads a card with Claude's vision through the Messages API. A forced tool call makes the answer
 * structured. Under Anthropic's commercial API terms, inputs and outputs aren't used for training
 * by default.
 */
@Component
@ConditionalOnBooleanProperty("pokemans.catalog.reader.enabled")
class ClaudeCardReader implements CardReader {

    static final String NAME = "CLAUDE";

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };

    static final String PROMPT = """
            This is a photo of the front of a Pokémon trading card, cropped to the card and straightened.
            Report what is printed on it using the report_card tool. Read, don't guess: leave a field out
            when you can't read it clearly. The collector number is usually at the bottom, like "11/113"
            (number 11, set total 113) or a promo code like "SWSH050" with no total. Give the name exactly
            as printed, including suffixes such as ex, EX, GX, V, VMAX, VSTAR, Star or the δ (delta) symbol.""";

    /** JSON schema of the tool input: the fields of {@link CardReader.Reading}. */
    static final Map<String, Object> TOOL = Map.of(
            "name", "report_card",
            "description", "Report the text and marks printed on the card.",
            "input_schema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string", "description", "Card name as printed"),
                            "number", Map.of("type", "string", "description", "Collector number before the slash, or the promo code"),
                            "set_total", Map.of("type", "integer", "description", "Number after the slash"),
                            "language", Map.of("type", "string", "description", "ISO 639-1 code of the card text, e.g. en, ja"),
                            "first_edition_stamp", Map.of("type", "boolean", "description", "A '1st Edition' stamp is visible"),
                            "finish", Map.of("type", "string", "enum", List.of("NORMAL", "HOLO", "REVERSE_HOLO", "UNSURE"),
                                    "description", "HOLO: foil artwork. REVERSE_HOLO: foil everywhere except the artwork."),
                            "copyright_year", Map.of("type", "integer", "description", "Latest year in the copyright line"),
                            "set_name", Map.of("type", "string", "description", "Set name only if printed on the card"),
                            "notes", Map.of("type", "string", "description", "Anything else that identifies the print, e.g. stamps")),
                    "required", List.of()));

    private final RestClient http;
    private final String model;

    ClaudeCardReader(CatalogProperties properties) {
        CatalogProperties.Reader config = properties.reader();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("pokemans.catalog.reader.api-key must be set when the card reader is enabled");
        }
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        requests.setReadTimeout(config.timeout());
        this.http = RestClient.builder()
                .baseUrl(config.baseUrl().toString())
                .defaultHeader("x-api-key", config.apiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .requestFactory(requests)
                .build();
        this.model = config.model();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Reading read(byte[] image, String contentType) {
        Map<String, Object> request = Map.of(
                "model", model,
                "max_tokens", 1024,
                "tools", List.of(TOOL),
                "tool_choice", Map.of("type", "tool", "name", "report_card"),
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "image", "source", Map.of("type", "base64", "media_type", contentType,
                                "data", Base64.getEncoder().encodeToString(image))),
                        Map.of("type", "text", "text", PROMPT)))));
        Map<String, Object> response = http.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(OBJECT);
        return parse(response);
    }

    /** Package-private for tests: the reading in a Messages API response. */
    static Reading parse(Map<String, Object> response) {
        Map<String, Object> input = Map.of();
        if (response != null && response.get("content") instanceof List<?> content) {
            for (Object block : content) {
                if (block instanceof Map<?, ?> m && "tool_use".equals(m.get("type")) && m.get("input") instanceof Map<?, ?> i) {
                    input = cast(i);
                }
            }
        }
        if (input.isEmpty()) {
            throw new IllegalStateException("The reader returned no card report");
        }
        String finish = text(input.get("finish"));
        Variant variant = finish == null || finish.equals("UNSURE") ? null : Variant.valueOf(finish);
        String language = text(input.get("language"));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", response.get("id"));
        raw.put("model", response.get("model"));
        raw.put("usage", response.get("usage"));
        raw.put("input", input);
        return new Reading(text(input.get("name")), text(input.get("number")), integer(input.get("set_total")),
                language == null ? null : language.toLowerCase(Locale.ROOT), bool(input.get("first_edition_stamp")),
                variant, integer(input.get("copyright_year")), text(input.get("set_name")), text(input.get("notes")),
                text(response.get("model")), raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : null;
    }
}

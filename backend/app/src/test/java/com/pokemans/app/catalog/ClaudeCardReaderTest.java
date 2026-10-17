package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ClaudeCardReaderTest {

    @Test
    void sendsTheImageWithTheReportToolAndReadsTheReport() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> version = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] response = """
                    {"id":"msg_1","model":"claude-sonnet-5-5","content":[{"type":"tool_use","name":"report_card","input":
                     {"name":"Metagross δ","number":"11","set_total":113,"language":"en","finish":"HOLO","copyright_year":2005,
                      "notes":"25th anniversary stamp"}}],"usage":{"input_tokens":1900,"output_tokens":80}}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            ClaudeCardReader reader = new ClaudeCardReader(new CatalogProperties(null, new CatalogProperties.Reader(true, "sk-test",
                    "claude-sonnet-5-5", base, Duration.ofSeconds(5), false, Duration.ofSeconds(15), 5)));

            CardReader.Reading reading = reader.read(new byte[] {1, 2, 3}, "image/jpeg");

            assertThat(key.get()).isEqualTo("sk-test");
            assertThat(version.get()).isEqualTo("2023-06-01");
            assertThat(body.get()).contains("\"report_card\"", "\"media_type\":\"image/jpeg\"", "\"data\":\"AQID\"")
                    .doesNotContain("tool_choice");
            assertThat(reading.name()).isEqualTo("Metagross δ");
            assertThat(reading.number()).isEqualTo("11");
            assertThat(reading.setTotal()).isEqualTo(113);
            assertThat(reading.finish()).isEqualTo(Variant.HOLO);
            assertThat(reading.copyrightYear()).isEqualTo(2005);
            assertThat(reading.model()).isEqualTo("claude-sonnet-5-5");
            assertThat(reading.raw()).containsKeys("usage", "input");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unsureFinishIsNullAndNoReportIsAnError() {
        CardReader.Reading reading = ClaudeCardReader.parse(Map.of("content", List.of(Map.of("type", "tool_use",
                "input", Map.of("name", "Pikachu", "finish", "UNSURE", "language", "JA")))));
        assertThat(reading.finish()).isNull();
        assertThat(reading.language()).isEqualTo("ja");
        assertThat(reading.number()).isNull();

        assertThatThrownBy(() -> ClaudeCardReader.parse(Map.of("content", List.of(Map.of("type", "text", "text", "hi")))))
                .isInstanceOf(IllegalStateException.class);
    }
}

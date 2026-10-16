package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a local stand-in serving trimmed copies of real TCGdex responses. */
class TcgdexCatalogProviderTest {

    private static final Map<String, String> RESPONSES = Map.of(
            "/v2/en/cards", """
                    [{"id":"ex5-11","localId":"11","name":"Metagross","image":"https://assets.tcgdex.net/en/ex/ex5/11"},
                     {"id":"swsh6-112","localId":"112","name":"Metagross V","image":"https://assets.tcgdex.net/en/swsh/swsh6/112"},
                     {"id":"30th-c-003","localId":"003","name":"Metagross"}]""",
            "/v2/en/cards/base1-4", """
                    {"id":"base1-4","localId":"4","name":"Charizard","rarity":"Rare","image":"https://assets.tcgdex.net/en/base/base1/4",
                     "set":{"id":"base1","name":"Base Set"},
                     "variants":{"firstEdition":true,"holo":true,"normal":false,"reverse":false,"wPromo":false},
                     "variants_detailed":[{"type":"holo","size":"standard"},{"type":"holo","size":"standard","stamp":["1st-edition"]}]}""",
            "/v2/en/sets/base1", """
                    {"id":"base1","name":"Base Set","releaseDate":"1999-01-09","serie":{"id":"base","name":"Base"}}""");

    private HttpServer server;
    private TcgdexCatalogProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = RESPONSES.get(exchange.getRequestURI().getPath());
            byte[] bytes = (body == null ? "{\"error\":\"not found\"}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2");
        provider = new TcgdexCatalogProvider(
                new CatalogProperties(new CatalogProperties.Tcgdex(true, base, Duration.ofSeconds(5))));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void searchFiltersByExactNumberIgnoringLeadingZeros() {
        assertThat(provider.search("metagross", null, "en")).hasSize(3);
        assertThat(provider.search("metagross", "11", "en")).singleElement()
                .satisfies(hit -> {
                    assertThat(hit.externalId()).isEqualTo("ex5-11");
                    assertThat(hit.setId()).isEqualTo("ex5");
                });
        assertThat(provider.search("metagross", "3", "en")).singleElement()
                .satisfies(hit -> assertThat(hit.setId()).isEqualTo("30th-c"));
    }

    @Test
    void cardDetailIncludesSetEraEditionsAndVariants() {
        CatalogProvider.ProviderCard card = provider.card("base1-4", "en").orElseThrow();

        assertThat(card.name()).isEqualTo("Charizard");
        assertThat(card.setName()).isEqualTo("Base Set");
        assertThat(card.seriesName()).isEqualTo("Base");
        assertThat(card.setReleaseDate()).isEqualTo(LocalDate.of(1999, 1, 9));
        assertThat(card.editions()).containsExactly(Edition.UNLIMITED, Edition.FIRST_EDITION, Edition.SHADOWLESS);
        assertThat(card.variants()).containsExactly(Variant.HOLO);
        assertThat(card.stamps()).containsExactly("1st-edition");
        assertThat(card.promo()).isFalse();
        assertThat(card.payload()).containsKey("variants");
    }

    @Test
    void unknownCardIsEmpty() {
        assertThat(provider.card("nope-1", "en")).isEmpty();
    }
}

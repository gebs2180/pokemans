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
                     {"id":"ex11-11","localId":"11","name":"Metagross δ","image":"https://assets.tcgdex.net/en/ex/ex11/11"},
                     {"id":"swsh6-112","localId":"112","name":"Metagross V","image":"https://assets.tcgdex.net/en/swsh/swsh6/112"},
                     {"id":"30th-c-003","localId":"003","name":"Metagross"}]""",
            "/v2/en/cards/base1-4", """
                    {"id":"base1-4","localId":"4","name":"Charizard","rarity":"Rare","image":"https://assets.tcgdex.net/en/base/base1/4",
                     "set":{"id":"base1","name":"Base Set"},
                     "variants":{"firstEdition":true,"holo":true,"normal":false,"reverse":false,"wPromo":false},
                     "variants_detailed":[{"type":"holo","size":"standard"},{"type":"holo","size":"standard","stamp":["1st-edition"]}]}""",
            "/v2/en/sets/base1", """
                    {"id":"base1","name":"Base Set","releaseDate":"1999-01-09","serie":{"id":"base","name":"Base"}}""",
            "/v2/en/sets", """
                    [{"id":"ex5","name":"Hidden Legends","cardCount":{"total":102,"official":101}},
                     {"id":"ex11","name":"Delta Species","cardCount":{"total":114,"official":113}}]""");

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
                new CatalogProperties(new CatalogProperties.Tcgdex(true, base, Duration.ofSeconds(5)), null));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void searchFiltersByExactNumberIgnoringLeadingZeros() {
        assertThat(provider.search("metagross", null, "en")).hasSize(4);
        assertThat(provider.search("metagross", "11", "en")).extracting(CatalogProvider.Hit::externalId)
                .containsExactly("ex5-11", "ex11-11");
        assertThat(provider.search("metagross", "11", "en").getFirst().setId()).isEqualTo("ex5");
        assertThat(provider.search("metagross", "3", "en")).singleElement()
                .satisfies(hit -> assertThat(hit.setId()).isEqualTo("30th-c"));
    }

    @Test
    void searchShowsSetNamesAndPutsTheSetOfThePrintedSizeFirst() {
        // "11/113" is Delta Species (113 cards), not Hidden Legends (101), though TCGdex lists Hidden Legends first.
        assertThat(provider.search("metagross", "11/113", "en")).extracting(CatalogProvider.Hit::externalId)
                .containsExactly("ex11-11", "ex5-11");
        assertThat(provider.search("metagross", "11/113", "en").getFirst())
                .satisfies(hit -> assertThat(hit.setName()).isEqualTo("Delta Species"))
                .satisfies(hit -> assertThat(hit.setTotal()).isEqualTo(113));
        assertThat(provider.search("metagross", "11", "en")).extracting(CatalogProvider.Hit::externalId)
                .containsExactly("ex5-11", "ex11-11");
        assertThat(provider.search("metagross", null, "en")).filteredOn(h -> h.externalId().equals("swsh6-112"))
                .singleElement().satisfies(hit -> assertThat(hit.setName()).isNull());
        assertThat(TcgdexCatalogProvider.setTotal("4/102")).isEqualTo(102);
        assertThat(TcgdexCatalogProvider.setTotal("SWSH050")).isNull();
        assertThat(TcgdexCatalogProvider.setTotal("TG01/TG30")).isNull();
    }

    @Test
    void searchAcceptsTheNumberAsPrintedAndExtraWords() {
        // TCGdex matches names as one phrase; we query by the first word and filter on the rest.
        assertThat(provider.search("Metagross delta", "11/113", "en")).singleElement()
                .satisfies(hit -> assertThat(hit.externalId()).isEqualTo("ex11-11"));
        assertThat(provider.search("metagross V", null, "en")).singleElement()
                .satisfies(hit -> assertThat(hit.externalId()).isEqualTo("swsh6-112"));
        assertThat(provider.search("metagross holo", null, "en")).isEmpty();
        assertThat(TcgdexCatalogProvider.normalizeNumber(" 011/113 ")).isEqualTo("11");
        assertThat(TcgdexCatalogProvider.normalizeNumber("SWSH050")).isEqualTo("swsh050");
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

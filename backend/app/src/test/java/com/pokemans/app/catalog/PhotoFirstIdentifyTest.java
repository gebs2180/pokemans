package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Photo-first identification, with the reader on: the card is added with no name, photographed, read,
 * and the collector confirms a suggestion, says "Not my card" and searches, or searches because the
 * reading failed. Every answer is a proposal; nothing is identified until the admin confirms.
 */
@SpringBootTest(properties = {"pokemans.condition.worker.enabled=false", "pokemans.catalog.reader.max-attempts=2"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PhotoFirstIdentifyTest.FakeReaderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class PhotoFirstIdentifyTest {

    /** Reads every card as Metagross δ 11/113, or fails every call while {@link #down}. */
    static class FakeReader implements CardReader {

        final AtomicBoolean down = new AtomicBoolean();

        @Override
        public String name() {
            return "FAKE_READER";
        }

        @Override
        public Reading read(byte[] image, String contentType) {
            if (down.get()) {
                throw new IllegalStateException("overloaded");
            }
            return new Reading("Metagross δ", "11", 113, "en", false, Variant.HOLO, 2005, null, null, "fake-1", Map.of("ok", true));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeReaderConfig {
        @Bean
        FakeReader fakeReader() {
            return new FakeReader();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private RecognitionService recognition;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FakeReader reader;

    @MockitoBean
    private CatalogProvider provider;

    private TestApi api;

    @BeforeEach
    void catalog() {
        reader.down.set(false);
        api = new TestApi(mvc, security);
        when(provider.id()).thenReturn(CatalogCard.Provider.TCGDEX);
        when(provider.search(eq("Metagross δ"), eq("11"), any())).thenReturn(List.of(
                new CatalogProvider.Hit("ex11-11", "11", "Metagross δ", "ex11", null),
                new CatalogProvider.Hit("ex5-11", "11", "Metagross", "ex5", null)));
        when(provider.card("ex11-11", "en")).thenReturn(Optional.of(new CatalogProvider.ProviderCard("ex11-11", "en", "ex11",
                "Delta Species", "EX", LocalDate.of(2005, 10, 31), "11", "Metagross δ", "Rare Holo", List.of(Edition.UNLIMITED),
                List.of(Variant.HOLO, Variant.REVERSE_HOLO), List.of(), false, "https://img/ex11/11", Map.of(), 113)));
        when(provider.card("ex5-11", "en")).thenReturn(Optional.of(new CatalogProvider.ProviderCard("ex5-11", "en", "ex5",
                "Hidden Legends", "EX", LocalDate.of(2004, 6, 1), "11", "Metagross", "Rare Holo", List.of(Edition.UNLIMITED),
                List.of(Variant.HOLO), List.of(), false, null, Map.of(), 101)));
    }

    private void runReader() {
        jdbc.update("update card_reading set next_attempt_at = now() - interval '1 day' where status = 'PENDING'");
        recognition.runOnce();
    }

    /** A collector's card added with no name, front and back taken. Returns {orgId, cardId}. */
    private String[] photographedCard(String subject, JwtRequestPostProcessor collector) throws Exception {
        String orgId = api.collectorOrg("Photo First " + subject, subject);
        String collectionId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"Binder\"}", collector), "$.id");
        String cardId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections/" + collectionId + "/cards",
                        "{\"lot\":false}", collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("New card"))
                .andExpect(jsonPath("$.named").value(false)), "$.id");
        String identify = "/api/orgs/" + orgId + "/cards/" + cardId + "/identify";
        api.get(identify, collector).andExpect(jsonPath("$.step").value("PHOTOS"));
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.get(identify, collector).andExpect(jsonPath("$.step").value("PHOTOS"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        return new String[] {orgId, cardId};
    }

    @Test
    void confirmsTheReadersSuggestion() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|photo-first-yes", List.of());
        String[] card = photographedCard("auth0|photo-first-yes", collector);
        String orgId = card[0];
        String cardId = card[1];
        String identify = "/api/orgs/" + orgId + "/cards/" + cardId + "/identify";

        api.get(identify, collector).andExpect(jsonPath("$.step").value("READING"));
        runReader();
        String suggestionId = TestApi.read(api.get(identify, collector)
                .andExpect(jsonPath("$.step").value("SUGGEST"))
                .andExpect(jsonPath("$.readQuery").value("Metagross δ 11/113"))
                .andExpect(jsonPath("$.suggestions.length()").value(2))
                .andExpect(jsonPath("$.suggestions[0].card.externalId").value("ex11-11"))
                .andExpect(jsonPath("$.suggestions[0].card.number").value("11/113"))
                .andExpect(jsonPath("$.suggestions[0].card.variants.length()").value(2))
                .andExpect(jsonPath("$.suggestions[0].variant").value("HOLO"))
                .andExpect(jsonPath("$.suggestions[1].card.externalId").value("ex5-11")), "$.suggestions[0].suggestionId");
        String otherSuggestion = TestApi.read(api.get(identify, collector), "$.suggestions[1].suggestionId");

        // Someone else's suggestion, or a different card under this one's id, is refused.
        String proposed = "/api/orgs/" + orgId + "/cards/" + cardId + "/proposed-identity";
        api.postJson(proposed, "{\"source\":\"SUGGESTION\"}", collector).andExpect(status().isBadRequest());
        api.postJson(proposed, "{\"source\":\"SUGGESTION\",\"suggestionId\":\"" + suggestionId + "\",\"externalId\":\"ex5-11\",\"language\":\"en\"}",
                collector).andExpect(status().isBadRequest());

        // One tap, with the finish asked because the catalog lists two.
        api.postJson(proposed, "{\"source\":\"SUGGESTION\",\"suggestionId\":\"" + suggestionId + "\",\"variant\":\"HOLO\"}", collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("SUGGESTION"))
                .andExpect(jsonPath("$.externalId").value("ex11-11"))
                .andExpect(jsonPath("$.edition").value("UNLIMITED"))
                .andExpect(jsonPath("$.variant").value("HOLO"))
                .andExpect(jsonPath("$.suggestionId").value(suggestionId))
                .andExpect(jsonPath("$.readingId").isNotEmpty());

        api.get(identify, collector)
                .andExpect(jsonPath("$.step").value("PROPOSED"))
                .andExpect(jsonPath("$.proposal.name").value("Metagross δ"));
        api.get("/api/orgs/" + orgId + "/cards/" + cardId, collector)
                .andExpect(jsonPath("$.label").value("Metagross δ · Delta Species 11/113 · Holo"))
                .andExpect(jsonPath("$.named").value(true));
        // Changing the answer shows the suggestions again.
        api.get(identify + "?again=true", collector).andExpect(jsonPath("$.step").value("SUGGEST"));

        // The admin still sees the reader's suggestions and the collector's answer, and nothing is identified yet.
        api.get("/api/orgs/" + orgId + "/cards/" + cardId + "/identification", api.admin())
                .andExpect(jsonPath("$.current").isEmpty())
                .andExpect(jsonPath("$.suggestions.length()").value(2))
                .andExpect(jsonPath("$.proposal.source").value("SUGGESTION"));
        assertThat(jdbc.queryForObject("select count(*) from card_identification where card_item_id = ?::uuid and status = 'CONFIRMED'",
                Integer.class, cardId)).isZero();

        // The collector can change their answer to the alternative; each answer is a new row.
        api.postJson(proposed, "{\"source\":\"SUGGESTION\",\"suggestionId\":\"" + otherSuggestion + "\"}", collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalId").value("ex5-11"));
        assertThat(jdbc.queryForObject("select count(*) from card_identity_proposal where card_item_id = ?::uuid",
                Integer.class, cardId)).isEqualTo(2);
    }

    @Test
    void notMyCardSearchesInstead() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|photo-first-no", List.of());
        String[] card = photographedCard("auth0|photo-first-no", collector);
        String orgId = card[0];
        String cardId = card[1];
        String identify = "/api/orgs/" + orgId + "/cards/" + cardId + "/identify";
        runReader();
        String readingId = TestApi.read(api.get(identify, collector).andExpect(jsonPath("$.step").value("SUGGEST")), "$.readingId");

        // "Not my card": the collector searches (prefilled with what was read) and picks one.
        api.postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/proposed-identity",
                        "{\"externalId\":\"ex5-11\",\"language\":\"en\"}", collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("SEARCH"))
                .andExpect(jsonPath("$.suggestionId").isEmpty())
                .andExpect(jsonPath("$.readingId").value(readingId));

        // The wrong suggestions stay on record next to the answer.
        assertThat(jdbc.queryForObject("select count(*) from card_identification where card_item_id = ?::uuid and status = 'SUGGESTED'",
                Integer.class, cardId)).isEqualTo(2);
        api.get("/api/orgs/" + orgId + "/cards/" + cardId, collector)
                .andExpect(jsonPath("$.label").value("Metagross · Hidden Legends 11/101 · Holo"));

        // Once the admin confirms an identity, the collector's later answers no longer rename the card.
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                        "{\"provider\":\"TCGDEX\",\"externalId\":\"ex5-11\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\"}",
                        api.admin())
                .andExpect(status().isCreated());
        api.get(identify, collector).andExpect(jsonPath("$.step").value("IDENTIFIED"));
        api.postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/proposed-identity",
                "{\"source\":\"DESCRIBED\",\"description\":\"Actually a Metagross from a tin\"}", collector).andExpect(status().isCreated());
        api.get("/api/orgs/" + orgId + "/cards/" + cardId, collector)
                .andExpect(jsonPath("$.label").value("Metagross · Hidden Legends 11/101 · Holo"));
    }

    @Test
    void searchesWhenTheReadingFails() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|photo-first-fail", List.of());
        String[] card = photographedCard("auth0|photo-first-fail", collector);
        String orgId = card[0];
        String cardId = card[1];
        String identify = "/api/orgs/" + orgId + "/cards/" + cardId + "/identify";
        reader.down.set(true);
        runReader();
        api.get(identify, collector).andExpect(jsonPath("$.step").value("READING")); // retried later
        runReader();
        api.get(identify, collector)
                .andExpect(jsonPath("$.step").value("PICK"))
                .andExpect(jsonPath("$.why").value("READING_FAILED"))
                .andExpect(jsonPath("$.readQuery").isEmpty())
                .andExpect(jsonPath("$.suggestions.length()").value(0));

        // Neither the reader nor the search found it: the collector describes it, which names the card.
        api.postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/proposed-identity", "{\"source\":\"DESCRIBED\"}", collector)
                .andExpect(status().isBadRequest());
        api.postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/proposed-identity",
                        "{\"source\":\"DESCRIBED\",\"description\":\"Metagross, gold border, no set symbol\"}", collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("DESCRIBED"))
                .andExpect(jsonPath("$.externalId").isEmpty())
                .andExpect(jsonPath("$.readingId").isNotEmpty());
        api.get("/api/orgs/" + orgId + "/cards/" + cardId, collector)
                .andExpect(jsonPath("$.label").value("Metagross, gold border, no set symbol"));
    }

    @Test
    void aLotIsStillDescribedUpFront() throws Exception {
        JwtRequestPostProcessor partner = api.user("auth0|photo-first-lot", List.of());
        String orgId = api.collectorOrg("Photo First lot", "auth0|photo-first-lot");
        String collectionId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"Bulk\"}", partner), "$.id");
        String cards = "/api/orgs/" + orgId + "/collections/" + collectionId + "/cards";
        api.postJson(cards, "{\"lot\":true,\"quantity\":50}", partner).andExpect(status().isBadRequest());
        String lotId = TestApi.read(api.postJson(cards, "{\"label\":\"Fifty commons\",\"lot\":true,\"quantity\":50}", partner)
                .andExpect(status().isCreated()), "$.id");
        api.get("/api/orgs/" + orgId + "/cards/" + lotId + "/identify", partner).andExpect(status().isBadRequest());
    }
}

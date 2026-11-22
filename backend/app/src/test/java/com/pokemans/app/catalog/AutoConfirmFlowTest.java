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
import java.util.UUID;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Automatic identity confirmation, switched on: the collector confirms the reader's top suggestion and the card is
 * identified by the system; any other answer still waits for the admin, who can override the system.
 */
@SpringBootTest(properties = {"pokemans.condition.worker.enabled=false", "pokemans.catalog.auto-confirm.enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AutoConfirmFlowTest.FakeReaderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
@RecordApplicationEvents
class AutoConfirmFlowTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeReaderConfig {
        @Bean
        PhotoFirstIdentifyTest.FakeReader fakeReader() {
            return new PhotoFirstIdentifyTest.FakeReader();
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
    private ApplicationEvents events;

    @MockitoBean
    private CatalogProvider provider;

    private TestApi api;

    @BeforeEach
    void catalog() {
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

    /** A photographed, read card. Returns {orgId, cardId, topSuggestionId, secondSuggestionId}. */
    private String[] readCard(String subject, JwtRequestPostProcessor collector) throws Exception {
        String orgId = api.collectorOrg("Auto confirm " + subject, subject);
        String collectionId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"Binder\"}", collector), "$.id");
        String cardId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections/" + collectionId + "/cards",
                "{\"lot\":false}", collector).andExpect(status().isCreated()), "$.id");
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        jdbc.update("update card_reading set next_attempt_at = now() - interval '1 day' where status = 'PENDING'");
        recognition.runOnce();
        String identify = "/api/orgs/" + orgId + "/cards/" + cardId + "/identify";
        String top = TestApi.read(api.get(identify, collector).andExpect(jsonPath("$.step").value("SUGGEST"))
                .andExpect(jsonPath("$.suggestions[0].card.externalId").value("ex11-11")), "$.suggestions[0].suggestionId");
        String second = TestApi.read(api.get(identify, collector), "$.suggestions[1].suggestionId");
        return new String[] {orgId, cardId, top, second};
    }

    private void propose(String[] card, String json, JwtRequestPostProcessor collector) throws Exception {
        api.postJson("/api/orgs/" + card[0] + "/cards/" + card[1] + "/proposed-identity", json, collector)
                .andExpect(status().isCreated());
    }

    private int confirmed(String cardId) {
        return jdbc.queryForObject("select count(*) from card_identification where card_item_id = ?::uuid and status = 'CONFIRMED'",
                Integer.class, cardId);
    }

    private long identifiedEvents(String cardId) {
        return events.stream(Catalog.CardIdentified.class).filter(e -> e.cardItemId().equals(UUID.fromString(cardId))).count();
    }

    @Test
    void confirmsTheReadersTopSuggestion() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|auto-confirm-yes", List.of());
        String[] card = readCard("auth0|auto-confirm-yes", collector);
        propose(card, "{\"source\":\"SUGGESTION\",\"suggestionId\":\"" + card[2] + "\",\"variant\":\"HOLO\"}", collector);

        Map<String, Object> row = jdbc.queryForMap("select source, status, created_by, rule_version, proposal_id::text p,"
                + " suggestion_id::text s, reading_id, confidence, note from card_identification where card_item_id = ?::uuid"
                + " and status = 'CONFIRMED'", card[1]);
        assertThat(row.get("source")).isEqualTo("SYSTEM");
        assertThat(row.get("created_by")).isNull();
        assertThat(row.get("rule_version")).isEqualTo(AutoConfirm.RULE_VERSION);
        assertThat(row.get("s")).isEqualTo(card[2]);
        assertThat(row.get("p")).isEqualTo(jdbc.queryForObject(
                "select id::text from card_identity_proposal where card_item_id = ?::uuid", String.class, card[1]));
        assertThat(row.get("reading_id")).isNotNull();
        assertThat(row.get("note").toString()).contains("confidence 0.95").contains("threshold 0.90");
        assertThat(identifiedEvents(card[1])).isEqualTo(1);
        assertThat(events.stream(Catalog.CardIdentified.class).filter(e -> e.cardItemId().equals(UUID.fromString(card[1])))
                .findFirst().orElseThrow().automatic()).isTrue();

        String identify = "/api/orgs/" + card[0] + "/cards/" + card[1] + "/identify";
        api.get(identify, collector).andExpect(jsonPath("$.step").value("IDENTIFIED"));
        api.get("/api/orgs/" + card[0] + "/cards/" + card[1] + "/identification", api.admin())
                .andExpect(jsonPath("$.current.source").value("SYSTEM"))
                .andExpect(jsonPath("$.current.card.externalId").value("ex11-11"))
                .andExpect(jsonPath("$.current.card.variant").value("HOLO"));

        // The admin overrides it like any identification: a newer row, the system's stays on record.
        api.postJson("/api/admin/orgs/" + card[0] + "/cards/" + card[1] + "/identifications",
                        "{\"provider\":\"TCGDEX\",\"externalId\":\"ex11-11\",\"language\":\"en\",\"edition\":\"UNLIMITED\","
                                + "\"variant\":\"REVERSE_HOLO\"}", api.admin())
                .andExpect(status().isCreated());
        api.get("/api/orgs/" + card[0] + "/cards/" + card[1] + "/identification", api.admin())
                .andExpect(jsonPath("$.current.source").value("REVIEWER"))
                .andExpect(jsonPath("$.current.card.variant").value("REVERSE_HOLO"));
        assertThat(confirmed(card[1])).isEqualTo(2);
        assertThat(identifiedEvents(card[1])).isEqualTo(2);

        // A later answer from the collector doesn't confirm again.
        propose(card, "{\"source\":\"SUGGESTION\",\"suggestionId\":\"" + card[2] + "\",\"variant\":\"HOLO\"}", collector);
        assertThat(confirmed(card[1])).isEqualTo(2);
    }

    @Test
    void leavesAnUnsettledFinishForTheAdmin() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|auto-confirm-unsure", List.of());
        String[] card = readCard("auth0|auto-confirm-unsure", collector);
        propose(card, "{\"source\":\"SUGGESTION\",\"suggestionId\":\"" + card[2] + "\"}", collector); // finish: not sure
        assertThat(confirmed(card[1])).isZero();
        assertThat(identifiedEvents(card[1])).isZero();
        api.get("/api/orgs/" + card[0] + "/cards/" + card[1] + "/identify", collector)
                .andExpect(jsonPath("$.step").value("PROPOSED"));
    }

    @Test
    void leavesAFinishTheReaderDidntSeeForTheAdmin() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|auto-confirm-reverse", List.of());
        String[] card = readCard("auth0|auto-confirm-reverse", collector);
        propose(card, "{\"source\":\"SUGGESTION\",\"suggestionId\":\"" + card[2] + "\",\"variant\":\"REVERSE_HOLO\"}", collector);
        assertThat(confirmed(card[1])).isZero();
    }

    @Test
    void leavesTheSecondSuggestionAndASearchedPickForTheAdmin() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|auto-confirm-second", List.of());
        String[] card = readCard("auth0|auto-confirm-second", collector);
        propose(card, "{\"source\":\"SUGGESTION\",\"suggestionId\":\"" + card[3] + "\"}", collector);
        assertThat(confirmed(card[1])).isZero();
        propose(card, "{\"externalId\":\"ex11-11\",\"language\":\"en\",\"variant\":\"HOLO\"}", collector);
        assertThat(confirmed(card[1])).isZero();
        assertThat(identifiedEvents(card[1])).isZero();
    }
}

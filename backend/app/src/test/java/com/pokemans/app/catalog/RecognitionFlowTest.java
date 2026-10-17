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
import java.util.concurrent.atomic.AtomicInteger;
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

/** Capture completes, the front crop is read, catalog matches are suggested, the reviewer confirms one. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecognitionFlowTest.FakeReaderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class RecognitionFlowTest {

    /** Reads every card as Metagross δ 11/113; the first call fails, as an outage would. */
    static class FakeReader implements CardReader {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "FAKE_READER";
        }

        @Override
        public Reading read(byte[] image, String contentType) {
            assertThat(contentType).isEqualTo("image/jpeg");
            assertThat(image).isNotEmpty();
            if (calls.incrementAndGet() == 1) {
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

    @MockitoBean
    private CatalogProvider provider;

    private void runReader() {
        jdbc.update("update card_reading set next_attempt_at = now() - interval '1 day' where status = 'PENDING'");
        recognition.runOnce();
    }

    @Test
    void readSuggestConfirm() throws Exception {
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

        TestApi api = new TestApi(mvc, security);
        JwtRequestPostProcessor collector = api.user("auth0|reader-collector", List.of());
        String orgId = api.collectorOrg("Reader Collector", "auth0|reader-collector");
        String cardId = api.card(orgId, "Metagross from the binder", collector);
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));

        String identification = "/api/orgs/" + orgId + "/cards/" + cardId + "/identification";
        api.get(identification, api.admin())
                .andExpect(jsonPath("$.recognition").value(true))
                .andExpect(jsonPath("$.reading.status").value("PENDING"));

        runReader(); // the outage: retried later
        api.get(identification, api.admin()).andExpect(jsonPath("$.reading.status").value("PENDING"));
        runReader();

        api.get(identification, api.admin())
                .andExpect(jsonPath("$.reading.status").value("DONE"))
                .andExpect(jsonPath("$.reading.setTotal").value(113))
                .andExpect(jsonPath("$.suggestions.length()").value(2))
                .andExpect(jsonPath("$.suggestions[0].card.externalId").value("ex11-11"))
                .andExpect(jsonPath("$.suggestions[0].card.variant").value("HOLO"))
                .andExpect(jsonPath("$.suggestions[0].confidence").value(0.95))
                .andExpect(jsonPath("$.current").isEmpty())
                .andExpect(jsonPath("$.history.length()").value(0));

        // The reviewer confirms the top suggestion; it becomes current, and the suggestions stay on record.
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                        "{\"provider\":\"TCGDEX\",\"externalId\":\"ex11-11\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\"}",
                        api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current.card.name").value("Metagross δ"));
        api.get(identification, collector)
                .andExpect(jsonPath("$.current.card.id").value(jdbc.queryForObject(
                        "select catalog_card_id::text from card_identification where card_item_id = ?::uuid and status = 'SUGGESTED' order by confidence desc limit 1",
                        String.class, cardId)));

        // Reading again is admin-only.
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/readings", "", collector).andExpect(status().isForbidden());
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/readings", "", api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reading.status").value("PENDING"));
    }
}

package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * With the reader on, a completed capture is read straight away in the background, not at the next
 * poll: the poll is set to an hour here, so only the immediate start can read the card in time.
 */
@SpringBootTest(properties = {
        "pokemans.condition.worker.enabled=false",
        "pokemans.catalog.reader.enabled=true",
        "pokemans.catalog.reader.api-key=test-key",
        "pokemans.catalog.reader.poll-interval=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ReadStraightAwayTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    /** Replaces the Claude reader: no network in tests. */
    @MockitoBean
    private CardReader reader;

    @MockitoBean
    private CatalogProvider provider;

    @Test
    void aCompletedCaptureIsReadWithinSeconds() throws Exception {
        when(reader.name()).thenReturn("MOCK_READER");
        when(reader.read(any(), anyString())).thenReturn(new CardReader.Reading(
                "Lugia V", "186", 195, "en", false, Variant.HOLO, 2022, null, null, "mock-1", Map.of()));
        when(provider.id()).thenReturn(CatalogCard.Provider.TCGDEX);

        TestApi api = new TestApi(mvc, security);
        JwtRequestPostProcessor collector = api.user("auth0|straight-away", List.of());
        String orgId = api.collectorOrg("Straight Away", "auth0|straight-away");
        String cardId = api.card(orgId, "Lugia", collector);
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select status from card_reading where card_item_id = ?::uuid", String.class, cardId)).isEqualTo("DONE"));
        assertThat(jdbc.queryForObject("select name from card_reading where card_item_id = ?::uuid", String.class, cardId))
                .isEqualTo("Lugia V");
    }
}

package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A completed capture's prediction request is sent to the provider straight away in the background,
 * not at the next poll: the poll is set to an hour here, so only the immediate start can send it.
 */
@SpringBootTest(properties = {
        "pokemans.condition.worker.enabled=true",
        "pokemans.condition.worker.poll-interval=1h",
        "pokemans.condition.worker.sweep-interval=1h"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PredictStraightAwayTest.QuickProviderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class PredictStraightAwayTest {

    /** Takes requests at once; the answer comes on a later poll, as with the real providers. */
    static class QuickProvider implements PreGradeProvider {

        final AtomicInteger submits = new AtomicInteger();

        @Override
        public String name() {
            return "QUICK";
        }

        @Override
        public String submit(Photos photos, String idempotencyKey) {
            submits.incrementAndGet();
            return "quick-" + idempotencyKey;
        }

        @Override
        public Poll poll(String externalRequestId) {
            return Poll.done(new Reading("quick-1", 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, Map.of()), Map.of("id", externalRequestId));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QuickProviderConfig {
        @Bean
        QuickProvider quickProvider() {
            return new QuickProvider();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private QuickProvider provider;

    @Test
    void aCompletedCaptureIsSentWithinSeconds() throws Exception {
        TestApi api = new TestApi(mvc, security);
        JwtRequestPostProcessor collector = api.user("auth0|predict-straight-away", List.of());
        String orgId = api.collectorOrg("Predict Straight Away", "auth0|predict-straight-away");
        String cardId = api.card(orgId, "Card", collector);
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select status from prediction_request where card_item_id = ?::uuid", String.class, cardId)).isNotEqualTo("PENDING"));
        assertThat(provider.submits).hasValue(1);
    }
}

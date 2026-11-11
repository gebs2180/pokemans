package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
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
 * A provider that is out of credits fails its request at once, with no retries and no stored
 * prediction, and the card is built from the provider that answered. A busy one waits without using
 * up its attempts.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ProviderRefusalFlowTest.Providers.class})
@Testcontainers(disabledWithoutDocker = true)
class ProviderRefusalFlowTest {

    /** Answers at the first poll with a PSA 8. */
    static class Answers implements PreGradeProvider {
        @Override
        public String name() {
            return "ANSWERS";
        }

        @Override
        public String submit(Photos photos, String idempotencyKey) {
            return "a-" + idempotencyKey;
        }

        @Override
        public Poll poll(String externalRequestId) {
            return Poll.done(new Reading(null, 8.0, 8.0, null, null, null, null, Map.of()), Map.of("id", externalRequestId));
        }
    }

    /** Out of credits: every submit is refused. */
    static class OutOfCredits implements PreGradeProvider {
        final AtomicInteger submits = new AtomicInteger();

        @Override
        public String name() {
            return "BROKE";
        }

        @Override
        public String submit(Photos photos, String idempotencyKey) {
            submits.incrementAndGet();
            throw new Refused("CardGrade said 402 INSUFFICIENT_CREDITS");
        }

        @Override
        public Poll poll(String externalRequestId) {
            throw new AssertionError("never submitted");
        }
    }

    /** Rate limited on every submit. */
    static class Busy implements PreGradeProvider {
        @Override
        public String name() {
            return "BUSY";
        }

        @Override
        public String submit(Photos photos, String idempotencyKey) {
            throw new PreGradeProvider.Busy("429 RATE_LIMITED", Duration.ofSeconds(17));
        }

        @Override
        public Poll poll(String externalRequestId) {
            throw new AssertionError("never submitted");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Providers {
        @Bean
        Answers answers() {
            return new Answers();
        }

        @Bean
        OutOfCredits outOfCredits() {
            return new OutOfCredits();
        }

        @Bean
        Busy busy() {
            return new Busy();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private PredictionWorker worker;

    @Autowired
    private OutOfCredits broke;

    @Autowired
    private JdbcTemplate jdbc;

    private void runWorker() {
        jdbc.update("update prediction_request set next_attempt_at = now() - interval '1 day' where status in ('PENDING', 'SUBMITTED')");
        worker.runOnce();
    }

    @Test
    void refusedFailsAtOnceBusyWaitsAndTheCardIsBuiltFromTheOthers() throws Exception {
        TestApi api = new TestApi(mvc, security);
        JwtRequestPostProcessor collector = api.user("auth0|refusal-collector", List.of());
        String orgId = api.collectorOrg("Refusal Collector", "auth0|refusal-collector");
        String cardId = api.card(orgId, "Card for the refusal test", collector);
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector);
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector);

        runWorker(); // submits: ANSWERS accepted, BROKE refused, BUSY told to wait
        runWorker(); // ANSWERS polled: done; BROKE is finished and not called again

        assertThat(broke.submits.get()).isEqualTo(1);
        Map<String, Object> refused = jdbc.queryForMap(
                "select status, attempts, last_error from prediction_request where card_item_id = ?::uuid and provider = 'BROKE'", cardId);
        assertThat(refused.get("status")).isEqualTo("FAILED");
        assertThat(refused.get("last_error").toString()).contains("INSUFFICIENT_CREDITS");
        assertThat(jdbc.queryForObject("select count(*) from provider_prediction where card_item_id = ?::uuid and provider = 'BROKE'",
                Integer.class, cardId)).isZero();

        Map<String, Object> waiting = jdbc.queryForMap(
                "select status, attempts from prediction_request where card_item_id = ?::uuid and provider = 'BUSY'", cardId);
        assertThat(waiting.get("status")).isEqualTo("PENDING");
        assertThat(((Number) waiting.get("attempts")).intValue()).isZero();

        // Once the busy one gives up (here: closed by hand), the card is built from ANSWERS alone.
        jdbc.update("update prediction_request set status = 'FAILED', finished_at = now() where card_item_id = ?::uuid and provider = 'BUSY'", cardId);
        String base = "/api/admin/orgs/" + orgId + "/cards/" + cardId;
        api.postJson(base + "/assessments/rebuild", "", api.admin())
                .andExpect(jsonPath("$.authorType").value("SYSTEM"))
                .andExpect(jsonPath("$.confidence").value("A")); // one provider answered: A (Zach, 2026-11-10)
        api.get(base + "/condition", api.admin())
                .andExpect(jsonPath("$.assessments[0].basis.providerGrades.ANSWERS").value(8.0))
                .andExpect(jsonPath("$.assessments[0].basis.providerGrades.BROKE").doesNotExist());
    }
}

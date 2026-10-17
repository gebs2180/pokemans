package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M2 "done when", for one card: capture completes, the provider is asked automatically, its answer is
 * stored, a system distribution is built, and the reviewer's adjustment becomes a new version.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ConditionFlowTest.FakeProviderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class ConditionFlowTest {

    /** Answers "still working" on the first poll, then a 8.5 overall with a PSA 8. */
    static class FakeProvider implements PreGradeProvider {

        final AtomicInteger submits = new AtomicInteger();
        final AtomicInteger polls = new AtomicInteger();

        @Override
        public String name() {
            return "FAKE";
        }

        @Override
        public String submit(Photos photos, String idempotencyKey) {
            assertThat(photos.front().bytes()).isNotEmpty();
            assertThat(photos.back().bytes()).isNotEmpty();
            submits.incrementAndGet();
            return "fake-" + idempotencyKey;
        }

        @Override
        public Poll poll(String externalRequestId) {
            if (polls.incrementAndGet() % 2 == 1) {
                return Poll.pending();
            }
            return Poll.done(new Reading("fake-1", 8.5, 8.0, 9.0, 8.5, 8.5, 8.0, Map.of("note", "fake")),
                    Map.of("id", externalRequestId, "_base64", "should not be stored"));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeProviderConfig {
        @Bean
        FakeProvider fakeProvider() {
            return new FakeProvider();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private PredictionWorker worker;

    @Autowired
    private FakeProvider provider;

    @Autowired
    private JdbcTemplate jdbc;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
    }

    /** Makes every open request due (well in the past: the database clock can run ahead of ours), then runs the worker once. */
    private void runWorker() {
        jdbc.update("update prediction_request set next_attempt_at = now() - interval '1 day' where status in ('PENDING', 'SUBMITTED')");
        worker.runOnce();
    }

    @Test
    void captureToProviderToSystemDistributionToReviewer() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|condition-collector", List.of());
        String orgId = api.collectorOrg("Condition Collector", "auth0|condition-collector");
        String cardId = api.card(orgId, "Holo from the binder", collector);

        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));

        // Capture completing queued a request for the enabled provider.
        String base = "/api/admin/orgs/" + orgId + "/cards/" + cardId;
        api.get(base + "/condition", api.admin())
                .andExpect(jsonPath("$.enabledProviders[0]").value("FAKE"))
                .andExpect(jsonPath("$.requests[0].status").value("PENDING"))
                .andExpect(jsonPath("$.photos.length()").value(2))
                .andExpect(jsonPath("$.photos[0].cropUrl").isNotEmpty());

        runWorker(); // submit
        runWorker(); // poll: still working
        runWorker(); // poll: done
        assertThat(provider.submits.get()).isEqualTo(1);

        api.get(base + "/condition", api.admin())
                .andExpect(jsonPath("$.requests[0].status").value("DONE"))
                .andExpect(jsonPath("$.predictions[0].provider").value("FAKE"))
                .andExpect(jsonPath("$.predictions[0].psaGrade").value(8.0))
                .andExpect(jsonPath("$.assessments[0].version").value(1))
                .andExpect(jsonPath("$.assessments[0].authorType").value("SYSTEM"))
                // One provider: confidence B. Unidentified, so the holo surface shift applies too; the
                // two downward moves take the median from the provider's PSA 8 to 7.
                .andExpect(jsonPath("$.assessments[0].confidence").value("B"))
                .andExpect(jsonPath("$.assessments[0].median").value("7"))
                .andExpect(jsonPath("$.assessments[0].basis.modernHolo").value(true));
        assertThat(jdbc.queryForObject("select raw::text from provider_prediction where card_item_id = ?::uuid", String.class, cardId))
                .doesNotContain("_base64");

        // In the review queue, unidentified, with a system distribution.
        api.get("/api/admin/review-queue", collector).andExpect(status().isForbidden());
        api.get("/api/admin/review-queue", api.admin())
                .andExpect(jsonPath("$[?(@.cardId == '" + cardId + "')].assessment").value("SYSTEM"))
                .andExpect(jsonPath("$[?(@.cardId == '" + cardId + "')].identified").value(false));

        // The reviewer's distribution: version 2, with the change recorded.
        String reviewed = "{\"distribution\":{\"U\":0,\"6\":0.1,\"7\":0.3,\"8\":0.5,\"9\":0.1},\"confidence\":\"B\","
                + "\"reason\":\"Whitening on the back corners in the close-up\"}";
        api.postJson(base + "/assessments", reviewed, api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.authorType").value("REVIEWER"))
                .andExpect(jsonPath("$.adjustments").isNotEmpty());
        api.postJson(base + "/assessments", "{\"distribution\":{\"8\":0.7},\"confidence\":\"B\",\"reason\":\"x\"}", api.admin())
                .andExpect(status().isBadRequest());

        // Asking the provider again doesn't override the reviewer...
        api.postJson(base + "/prediction-requests", "", api.admin()).andExpect(status().isCreated());
        runWorker();
        runWorker();
        runWorker();
        api.get(base + "/condition", api.admin())
                .andExpect(jsonPath("$.predictions.length()").value(2))
                .andExpect(jsonPath("$.assessments[0].version").value(2))
                .andExpect(jsonPath("$.assessments[0].current").value(true));

        // ...until the reviewer rebuilds explicitly.
        api.postJson(base + "/assessments/rebuild", "", api.admin())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.authorType").value("SYSTEM"));
        assertThat(jdbc.queryForObject("select count(*) from condition_assessment where card_item_id = ?::uuid and is_current",
                Integer.class, cardId)).isEqualTo(1);
    }
}

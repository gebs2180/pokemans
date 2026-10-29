package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Our centering measurement as a provider beside a full one: it answers in the worker's first pass
 * with no job to poll, is stored like any answer, and caps the system distribution.
 */
@SpringBootTest(properties = {"pokemans.condition.worker.enabled=false", "pokemans.condition.centering.enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ConditionFlowTest.FakeProviderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class CenteringFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private PredictionWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
    }

    private void runWorker() {
        jdbc.update("update prediction_request set next_attempt_at = now() - interval '1 day' where status in ('PENDING', 'SUBMITTED')");
        worker.runOnce();
    }

    @Test
    void centeringIsMeasuredStoredAndCapsTheDistribution() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|centering-collector", List.of());
        String orgId = api.collectorOrg("Centering Collector", "auth0|centering-collector");
        String cardId = api.card(orgId, "Centred card", collector);
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        String base = "/api/admin/orgs/" + orgId + "/cards/" + cardId;

        runWorker(); // centering answers; the fake provider is submitted
        assertThat(jdbc.queryForObject(
                "select status from prediction_request where card_item_id = ?::uuid and provider = 'RAWMARK_CENTERING'", String.class, cardId))
                .isEqualTo("DONE");
        runWorker(); // fake: still working
        runWorker(); // fake: done, and the assessment is built from both

        // The test cards are drawn with even borders: centred, so the cap is 10 and the fake's PSA 8 stands.
        api.get(base + "/condition", api.admin())
                .andExpect(jsonPath("$.predictions[?(@.provider == 'RAWMARK_CENTERING')].centering").value(10.0))
                .andExpect(jsonPath("$.predictions[?(@.provider == 'RAWMARK_CENTERING')].overallGrade").value(org.hamcrest.Matchers.contains(
                        org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.predictions[?(@.provider == 'RAWMARK_CENTERING')].details.frontCentering.leftRight").exists())
                .andExpect(jsonPath("$.assessments[0].basis.centeringCap").value(10))
                .andExpect(jsonPath("$.assessments[0].basis.providerGrades.RAWMARK_CENTERING").doesNotExist())
                .andExpect(jsonPath("$.assessments[0].median").value("7"));
    }
}

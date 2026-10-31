package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        // Measured but not assessed yet (the fake provider is still open): no grades, but the seller sees the ratios.
        String seller = "/api/orgs/" + orgId + "/cards/" + cardId + "/status";
        api.get(seller, collector)
                .andExpect(jsonPath("$.condition.mostLikely").value(0))
                .andExpect(jsonPath("$.condition.centering.front.leftRight").value(matchesPattern("\\d+/\\d+")))
                .andExpect(jsonPath("$.condition.centering.back.topBottom").value(matchesPattern("\\d+/\\d+")));

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

        // The seller's view keeps the measurement beside the grades, and names no provider.
        String view = api.get(seller, collector)
                .andExpect(jsonPath("$.condition.mostLikely").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.condition.centering.front.leftRight").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(view).doesNotContain("RAWMARK_CENTERING", "FAKE");

        // A reviewer's assessment doesn't hide it.
        String reviewed = "{\"distribution\":{\"U\":0,\"7\":0.5,\"8\":0.5},\"confidence\":\"B\",\"reason\":\"Checked by hand\"}";
        api.postJson(base + "/assessments", reviewed, api.admin()).andExpect(status().isCreated());
        api.get(seller, collector).andExpect(jsonPath("$.condition.centering.front.leftRight").exists());
    }

    @Test
    void aPassingRetakeIsMeasuredAgainWithoutAskingThePaidProviders() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|centering-retake", List.of());
        String orgId = api.collectorOrg("Centering Retake", "auth0|centering-retake");
        String cardId = api.card(orgId, "Retaken card", collector);
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        runWorker();
        assertThat(requests(cardId, "RAWMARK_CENTERING")).isEqualTo(1);

        // A retake that fails its check isn't the photo in use, so there is nothing new to measure.
        api.uploadPhoto(orgId, cardId, "FRONT", "not a photo".getBytes(), collector).andExpect(jsonPath("$.status").value("RETAKE"));
        assertThat(requests(cardId, "RAWMARK_CENTERING")).isEqualTo(1);

        String retake = api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodRetake(1), collector)
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andReturn().getResponse().getContentAsString();
        String retakeId = com.jayway.jsonpath.JsonPath.read(retake, "$.id");
        assertThat(requests(cardId, "RAWMARK_CENTERING")).isEqualTo(2);
        assertThat(requests(cardId, "FAKE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select photo_ids->>0 from prediction_request
                where card_item_id = ?::uuid and provider = 'RAWMARK_CENTERING' order by created_at desc limit 1
                """, String.class, cardId)).isEqualTo(retakeId);
    }

    private int requests(String cardId, String provider) {
        return jdbc.queryForObject("select count(*) from prediction_request where card_item_id = ?::uuid and provider = ?",
                Integer.class, cardId, provider);
    }
}

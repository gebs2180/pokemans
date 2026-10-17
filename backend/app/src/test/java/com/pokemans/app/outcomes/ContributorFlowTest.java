package com.pokemans.app.outcomes;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A contributor agrees to the terms, shares raw photos and the grade; the admin checks the cert. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ContributorFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
    }

    @Test
    void consentThenPhotosThenGradeThenCertCheck() throws Exception {
        String orgId = TestApi.read(api.postJson("/api/admin/orgs", "{\"name\":\"Reddit poster\",\"type\":\"CONTRIBUTOR\"}", api.admin())
                .andExpect(status().isCreated()), "$.id");
        String token = TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/invites", "{}", api.admin()), "$.token");
        JwtRequestPostProcessor contributor = api.user("auth0|contributor", List.of());
        api.postJson("/api/invites/" + token + "/accept", "", contributor).andExpect(status().isOk());
        api.get("/api/me", contributor).andExpect(jsonPath("$.roles[0]").value("CONTRIBUTOR"));

        String collectionId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"Graded\"}", contributor)
                .andExpect(status().isCreated()), "$.id");
        String cardId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections/" + collectionId + "/cards",
                "{\"label\":\"Umbreon VMAX alt art\",\"lot\":false,\"quantity\":1}", contributor), "$.id");

        // No uploads before the terms are accepted.
        String consent = "/api/orgs/" + orgId + "/consent";
        api.get(consent, contributor).andExpect(jsonPath("$.acceptedAt").isEmpty()).andExpect(jsonPath("$.text").isNotEmpty());
        api.postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/photos",
                "{\"side\":\"FRONT\",\"contentType\":\"image/png\",\"sizeBytes\":1000}", contributor).andExpect(status().isForbidden());
        api.postJson(consent, "{\"version\":\"an-old-version\"}", contributor).andExpect(status().isConflict());
        api.postJson(consent, "{\"version\":\"" + Consents.CONTRIBUTOR_TERMS_VERSION + "\"}", contributor)
                .andExpect(status().isOk()).andExpect(jsonPath("$.acceptedAt").isNotEmpty());

        // The grade needs the raw photos first.
        String outcomes = "/api/orgs/" + orgId + "/cards/" + cardId + "/outcomes";
        String psa9 = "{\"grader\":\"PSA\",\"certNumber\":\"87654321\",\"grade\":9,\"gradeLabel\":\"MINT 9\"}";
        api.postJson(outcomes, psa9, contributor).andExpect(status().isConflict());
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), contributor).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), contributor).andExpect(jsonPath("$.status").value("PASSED"));

        api.postJson(outcomes, psa9, contributor)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.source").value("CONTRIBUTOR"))
                .andExpect(jsonPath("$.lookupUrl").value("https://www.psacard.com/cert/87654321"));
        api.postJson(outcomes, psa9, contributor).andExpect(status().isConflict());
        api.postJson(outcomes, "{\"grader\":\"PSA\",\"certNumber\":\"1; drop\",\"grade\":9}", contributor).andExpect(status().isBadRequest());

        // The admin checks the cert by hand.
        api.get("/api/admin/outcomes", contributor).andExpect(status().isForbidden());
        String unverified = api.get("/api/admin/outcomes", api.admin())
                .andExpect(jsonPath("$[?(@.certNumber == '87654321')].status").value("UNVERIFIED"))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(unverified, "$[?(@.certNumber == '87654321')].id");
        String outcomeId = ids.getFirst();
        api.postJson("/api/admin/outcomes/" + outcomeId + "/verifications", "{\"status\":\"VERIFIED\",\"note\":\"PSA site: Umbreon VMAX, 9\"}",
                api.admin()).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("VERIFIED"));
        api.get(outcomes, contributor).andExpect(jsonPath("$[0].status").value("VERIFIED"));

        // Collectors and partners don't record outcomes this way.
        String collectorOrg = api.collectorOrg("Plain collector", "auth0|plain-collector");
        String collectorCard = api.card(collectorOrg, "Card", api.admin());
        api.postJson("/api/orgs/" + collectorOrg + "/cards/" + collectorCard + "/outcomes", psa9.replace("87654321", "1"), api.admin())
                .andExpect(status().isNotFound());
    }
}

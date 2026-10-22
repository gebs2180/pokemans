package com.pokemans.app.identity;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A collector accepts the seller terms before uploading photos, getting an offer or posting a card. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SellerTermsTest {

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
    void nothingIsSoldOrPhotographedBeforeTheSellerTerms() throws Exception {
        String orgId = TestApi.read(api.postJson("/api/admin/orgs", "{\"name\":\"Seller " + UUID.randomUUID() + "\",\"type\":\"COLLECTOR\"}",
                api.admin()).andExpect(status().isCreated()), "$.id");
        String token = TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/invites", "{}", api.admin()), "$.token");
        JwtRequestPostProcessor seller = api.user("auth0|seller-" + UUID.randomUUID(), List.of());
        api.postJson("/api/invites/" + token + "/accept", "", seller).andExpect(status().isOk());
        String cardId = api.card(orgId, "Pikachu VMAX", seller);

        String consent = "/api/orgs/" + orgId + "/consent";
        api.get(consent, seller)
                .andExpect(jsonPath("$.version").value(Consents.SELLER_TERMS_VERSION))
                .andExpect(jsonPath("$.acceptedAt").isEmpty());
        String photos = "/api/orgs/" + orgId + "/cards/" + cardId + "/photos";
        String slot = "{\"side\":\"FRONT\",\"contentType\":\"image/png\",\"sizeBytes\":1000}";
        api.postJson(photos, slot, seller).andExpect(status().isForbidden());
        api.postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/offers", "", seller).andExpect(status().isForbidden());
        api.postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/listings", "{\"offerId\":\"" + UUID.randomUUID() + "\"}", seller)
                .andExpect(status().isForbidden());

        // Another kind of org's terms, or an older version, don't count.
        api.postJson(consent, "{\"version\":\"" + Consents.BUYER_TERMS_VERSION + "\"}", seller).andExpect(status().isConflict());
        api.postJson(consent, "{\"version\":\"" + Consents.SELLER_TERMS_VERSION + "\"}", seller)
                .andExpect(status().isOk()).andExpect(jsonPath("$.acceptedAt").isNotEmpty());
        api.postJson(photos, slot, seller).andExpect(status().isCreated());
    }
}

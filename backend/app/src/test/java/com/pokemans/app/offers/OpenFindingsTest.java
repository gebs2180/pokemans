package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Findings of the audit of 2026-11-02 that wait for a product decision. Each test states the behaviour the
 * finding asks about and FAILS today; that failure is the evidence in docs/audit-2026-11-02.md. They'd turn
 * the build red, so they run only with POKEMANS_AUDIT_OPEN=true. Once a finding is decided, move its test
 * into the suite, or delete it if the answer is to keep things as they are.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "POKEMANS_AUDIT_OPEN", matches = "true")
class OpenFindingsTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    private TestApi api() {
        return new TestApi(mvc, security);
    }

    @Test
    void anInviteForOneEmailDoesNotWorkForAnother() throws Exception {
        TestApi api = api();
        String org = TestApi.read(api.postJson("/api/admin/orgs", "{\"name\":\"Invited " + UUID.randomUUID() + "\",\"type\":\"COLLECTOR\"}", api.admin()), "$.id");
        String token = TestApi.read(api.postJson("/api/admin/orgs/" + org + "/invites", "{\"email\":\"sam@example.com\"}", api.admin())
                .andExpect(status().isCreated()), "$.token");

        // Forwarded in Discord, and opened by someone else.
        api.postJson("/api/invites/" + token + "/accept", "", api.user("auth0|forwarded-" + UUID.randomUUID(), List.of()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCardFoundDoubtfulComesOffTheMarket() throws Exception {
        TestApi api = api();
        TestMarket market = new TestMarket(api);
        Seller seller = market.seller();
        Posted card = market.posted(seller, "Doubtful Charizard");
        Buyer buyer = market.buyer();

        api.postJson("/api/admin/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/authenticity-verifications",
                "{\"status\":\"SUSPECTED_COUNTERFEIT\",\"method\":\"IN_HAND_INSPECTION\"}", api.admin()).andExpect(status().isCreated());

        api.get("/api/orgs/" + buyer.orgId() + "/market", buyer.who())
                .andExpect(jsonPath("$[?(@.listingId=='" + card.listingId() + "')]").isEmpty());
    }

    @Test
    void nobodyBuysTheirOwnCard() throws Exception {
        TestApi api = api();
        TestMarket market = new TestMarket(api);
        String subject = "auth0|both-sides-" + UUID.randomUUID();
        Seller seller = market.seller(subject);
        Buyer sameLogin = market.buyer(subject);
        Posted card = market.posted(seller, "Wash Trade Pikachu");

        api.postJson(TestMarket.accept(sameLogin, card), "", sameLogin.who()).andExpect(status().isConflict());
    }

    @Test
    void uploadSlotsRunOut() throws Exception {
        TestApi api = api();
        String subject = "auth0|flood-" + UUID.randomUUID();
        String org = api.collectorOrg("Flood " + UUID.randomUUID(), subject);
        var who = api.user(subject, List.of());
        String card = api.card(org, "Flood", who);

        int granted = 0;
        for (int i = 0; i < 300; i++) {
            int code = api.postJson("/api/orgs/" + org + "/cards/" + card + "/photos",
                    "{\"side\":\"DETAIL\",\"contentType\":\"image/jpeg\",\"sizeBytes\":26214400}", who).andReturn().getResponse().getStatus();
            granted += code == 201 ? 1 : 0;
        }
        // Each slot can hold 25 MB in our bucket, on one card of one seller, in about a minute.
        assertThat(granted).isLessThan(300);
    }
}

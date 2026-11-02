package com.pokemans.app.identity;

import static org.hamcrest.Matchers.hasSize;
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

/** The admin can take someone out of an org and stop a link that's got loose (audit of 2026-11-02). */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class EndingAccessTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    private TestApi api;
    private String subject;
    private String orgId;
    private JwtRequestPostProcessor member;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        subject = "auth0|member-" + UUID.randomUUID();
        orgId = api.collectorOrg("Org " + UUID.randomUUID(), subject);
        member = api.user(subject, List.of());
    }

    private String invite() throws Exception {
        return TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/invites", "{}", api.admin()).andExpect(status().isCreated()), "$.token");
    }

    @Test
    void someoneRemovedLosesTheOrgAtOnceAndCanBeInvitedBack() throws Exception {
        api.get("/api/orgs/" + orgId + "/collections", member).andExpect(status().isOk());
        String membership = TestApi.read(api.get("/api/admin/orgs/" + orgId + "/members", api.admin())
                .andExpect(jsonPath("$", hasSize(1))), "$[0].id");

        api.postJson("/api/admin/orgs/" + orgId + "/members/" + membership + "/end", "{\"reason\":\"broke the terms\"}", api.admin())
                .andExpect(status().isOk());

        api.get("/api/orgs/" + orgId + "/collections", member).andExpect(status().isNotFound());
        api.get("/api/me", member).andExpect(jsonPath("$.memberships", hasSize(0)));
        api.get("/api/admin/orgs/" + orgId + "/members", api.admin()).andExpect(jsonPath("$", hasSize(0)));
        api.postJson("/api/admin/orgs/" + orgId + "/members/" + membership + "/end", "{\"reason\":\"again\"}", api.admin())
                .andExpect(status().isNotFound());

        api.postJson("/api/invites/" + invite() + "/accept", "", member).andExpect(status().isOk());
        api.get("/api/orgs/" + orgId + "/collections", member).andExpect(status().isOk());
    }

    @Test
    void removingSomeoneTakesAReason() throws Exception {
        String membership = TestApi.read(api.get("/api/admin/orgs/" + orgId + "/members", api.admin()), "$[0].id");
        api.postJson("/api/admin/orgs/" + orgId + "/members/" + membership + "/end", "{\"reason\":\" \"}", api.admin())
                .andExpect(status().isBadRequest());
        api.get("/api/orgs/" + orgId + "/collections", member).andExpect(status().isOk());
    }

    @Test
    void aMemberOfAnotherOrgCantBeRemovedThroughThisOne() throws Exception {
        String other = api.collectorOrg("Other " + UUID.randomUUID(), "auth0|other-" + UUID.randomUUID());
        String membership = TestApi.read(api.get("/api/admin/orgs/" + orgId + "/members", api.admin()), "$[0].id");
        api.postJson("/api/admin/orgs/" + other + "/members/" + membership + "/end", "{\"reason\":\"x\"}", api.admin())
                .andExpect(status().isNotFound());
    }

    @Test
    void aRevokedLinkStopsWorkingAndLeavesTheList() throws Exception {
        String token = invite();
        String inviteId = TestApi.read(api.get("/api/admin/orgs/" + orgId + "/invites", api.admin()).andExpect(jsonPath("$", hasSize(1))), "$[0].id");

        api.postJson("/api/admin/orgs/" + orgId + "/invites/" + inviteId + "/revoke", "", api.admin()).andExpect(status().isOk());

        api.get("/api/admin/orgs/" + orgId + "/invites", api.admin()).andExpect(jsonPath("$", hasSize(0)));
        JwtRequestPostProcessor stranger = api.user("auth0|leak-" + UUID.randomUUID(), List.of());
        api.get("/api/invites/" + token, stranger).andExpect(jsonPath("$.usable").value(false));
        api.postJson("/api/invites/" + token + "/accept", "", stranger).andExpect(status().isGone());
        api.get("/api/orgs/" + orgId + "/collections", stranger).andExpect(status().isNotFound());
        api.postJson("/api/admin/orgs/" + orgId + "/invites/" + inviteId + "/revoke", "", api.admin()).andExpect(status().isConflict());
    }

    @Test
    void aLinkAlreadyUsedCantBeRevokedAndAnotherOrgsIsntFound() throws Exception {
        String token = invite();
        String inviteId = TestApi.read(api.get("/api/admin/orgs/" + orgId + "/invites", api.admin()), "$[0].id");
        String other = api.collectorOrg("Other " + UUID.randomUUID(), "auth0|other-" + UUID.randomUUID());
        api.postJson("/api/admin/orgs/" + other + "/invites/" + inviteId + "/revoke", "", api.admin()).andExpect(status().isNotFound());

        api.postJson("/api/invites/" + token + "/accept", "", api.user("auth0|joined-" + UUID.randomUUID(), List.of())).andExpect(status().isOk());
        api.postJson("/api/admin/orgs/" + orgId + "/invites/" + inviteId + "/revoke", "", api.admin()).andExpect(status().isConflict());
    }
}

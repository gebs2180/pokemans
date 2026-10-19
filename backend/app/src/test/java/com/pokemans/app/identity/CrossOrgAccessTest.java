package com.pokemans.app.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Org isolation, endpoint by endpoint: a member of org A reaching for org B's data, by naming org B
 * or by naming org A with B's ids, gets a 403 or 404 and changes nothing. Non-admins can't reach
 * any admin route, and nobody gets in without a token.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CrossOrgAccessTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    private TestApi api;
    private String orgA;
    private String orgB;
    private JwtRequestPostProcessor userA;
    private String collectionB;
    private String cardB;
    private String photoB;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        String subjectA = "auth0|org-a-" + UUID.randomUUID();
        String subjectB = "auth0|org-b-" + UUID.randomUUID();
        orgA = api.collectorOrg("Org A " + UUID.randomUUID(), subjectA);
        orgB = api.collectorOrg("Org B " + UUID.randomUUID(), subjectB);
        userA = api.user(subjectA, List.of());
        JwtRequestPostProcessor userB = api.user(subjectB, List.of());

        collectionB = TestApi.read(api.postJson("/api/orgs/" + orgB + "/collections", "{\"name\":\"B binder\"}", userB)
                .andExpect(status().isCreated()), "$.id");
        cardB = TestApi.read(api.postJson("/api/orgs/" + orgB + "/collections/" + collectionB + "/cards",
                "{\"label\":\"B card\",\"lot\":false,\"quantity\":1}", userB).andExpect(status().isCreated()), "$.id");
        photoB = TestApi.read(api.postJson("/api/orgs/" + orgB + "/cards/" + cardB + "/photos",
                "{\"side\":\"FRONT\",\"contentType\":\"image/png\",\"sizeBytes\":100}", userB).andExpect(status().isCreated()), "$.photoId");
    }

    private static MockHttpServletRequestBuilder postJson(String url, String json) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /** Every org-scoped route, for a given org id and B's resource ids. */
    private List<MockHttpServletRequestBuilder> orgRoutes(String org) {
        String p = "/api/orgs/" + org;
        return List.of(
                get(p + "/collections"),
                get(p + "/collections/" + collectionB),
                postJson(p + "/collections", "{\"name\":\"x\"}"),
                postJson(p + "/collections/" + collectionB + "/cards", "{\"label\":\"x\",\"lot\":false,\"quantity\":1}"),
                get(p + "/batches"),
                postJson(p + "/batches", "{\"name\":\"x\"}"),
                get(p + "/cards/" + cardB),
                postJson(p + "/cards/" + cardB + "/photos", "{\"side\":\"BACK\",\"contentType\":\"image/png\",\"sizeBytes\":100}"),
                post(p + "/photos/" + photoB + "/confirm"),
                get(p + "/cards/" + cardB + "/outcomes"),
                postJson(p + "/cards/" + cardB + "/outcomes", "{\"grader\":\"PSA\",\"certNumber\":\"" + System.nanoTime() + "\",\"grade\":9}"),
                get(p + "/cards/" + cardB + "/identification"),
                get(p + "/consent"));
    }

    @Test
    void namingAnotherOrgIsRefused() throws Exception {
        for (MockHttpServletRequestBuilder request : orgRoutes(orgB)) {
            int code = perform(request, userA);
            assertThat(code).as(describe(request)).isIn(403, 404);
        }
    }

    @Test
    void anotherOrgsIdsUnderYourOwnOrgAreNotFound() throws Exception {
        // Routes that name one of B's resources; A's own lists (collections, batches, consent) are A's to see.
        for (MockHttpServletRequestBuilder request : orgRoutes(orgA)) {
            String what = describe(request);
            if (!what.contains(collectionB) && !what.contains(cardB) && !what.contains(photoB)) {
                continue;
            }
            assertThat(perform(request, userA)).as(what).isIn(403, 404);
        }
    }

    @Test
    void adminRoutesNeedTheAdminRole() throws Exception {
        String catalogCard = UUID.randomUUID().toString();
        JwtRequestPostProcessor noMfaAdmin = jwt()
                .jwt(j -> j.subject("auth0|no-mfa").claim(security.rolesClaim(), List.of("ADMIN")))
                .authorities(new RoleClaimConverter(security)::convert);
        List<MockHttpServletRequestBuilder> admin = List.of(
                get("/api/admin/orgs"),
                postJson("/api/admin/orgs", "{\"name\":\"x\",\"type\":\"COLLECTOR\"}"),
                postJson("/api/admin/orgs/" + orgA + "/invites", "{}"),
                get("/api/admin/outcomes"),
                get("/api/admin/review-queue"),
                get("/api/admin/catalog/search?q=pikachu"),
                get("/api/admin/orgs/" + orgB + "/cards/" + cardB + "/condition"),
                postJson("/api/admin/orgs/" + orgB + "/cards/" + cardB + "/identifications", "{}"),
                post("/api/admin/orgs/" + orgB + "/cards/" + cardB + "/readings"),
                get("/api/admin/catalog-cards/" + catalogCard + "/prices"),
                postJson("/api/admin/catalog-cards/" + catalogCard + "/comps", "{}"),
                post("/api/admin/catalog-cards/" + catalogCard + "/prices/refresh"));
        for (MockHttpServletRequestBuilder request : admin) {
            assertThat(perform(request, userA)).as(describe(request)).isEqualTo(403);
            // A token that claims ADMIN without an MFA login isn't admin either.
            assertThat(perform(request, noMfaAdmin)).as("no MFA: " + describe(request)).isEqualTo(403);
        }
    }

    @Test
    void nothingWithoutAToken() throws Exception {
        for (MockHttpServletRequestBuilder request : orgRoutes(orgA)) {
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).as(describe(request)).isEqualTo(401);
        }
        mvc.perform(get("/api/admin/orgs")).andExpect(status().isUnauthorized());
    }

    private int perform(MockHttpServletRequestBuilder request, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(request.with(who)).andReturn().getResponse().getStatus();
    }

    private static String describe(MockHttpServletRequestBuilder request) {
        var r = request.buildRequest(new MockServletContext());
        return r.getMethod() + " " + r.getRequestURI();
    }
}

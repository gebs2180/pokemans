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
@SpringBootTest(properties = {"pokemans.condition.worker.enabled=false", "pokemans.collateral.enabled=true"})
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
    private String runB;
    private String reportB;

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
        runB = TestApi.read(api.postJson("/api/admin/orgs/" + orgB + "/collections/" + collectionB + "/valuation-runs", "{}", api.admin())
                .andExpect(status().isCreated()), "$.id");
        reportB = TestApi.read(api.postJson("/api/admin/valuation-runs/" + runB + "/reports", "{\"type\":\"LENDER\"}", api.admin())
                .andExpect(status().isCreated()), "$.id");
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
                get(p + "/cards/" + cardB + "/provenance"),
                postJson(p + "/cards/" + cardB + "/custody-records",
                        "{\"holderType\":\"OWNER\",\"holderName\":\"x\",\"location\":\"x\",\"heldSince\":\"2026-01-01\"}"),
                get(p + "/consent"),
                get(p + "/cards/" + cardB + "/sale"),
                get(p + "/cards/" + cardB + "/status"),
                get(p + "/collections/" + collectionB + "/status"),
                post(p + "/cards/" + cardB + "/offers"),
                postJson(p + "/cards/" + cardB + "/listings", "{\"offerId\":\"" + UUID.randomUUID() + "\"}"),
                post(p + "/listings/" + UUID.randomUUID() + "/withdraw"),
                get(p + "/market"),
                post(p + "/market/" + UUID.randomUUID() + "/accept"),
                get(p + "/deals"),
                post(p + "/deals/" + UUID.randomUUID() + "/label"),
                postJson(p + "/deals/" + UUID.randomUUID() + "/received", "{\"pricePaid\":1}"),
                postJson(p + "/deals/" + UUID.randomUUID() + "/returned", "{\"reason\":\"NOT_GENUINE\"}"),
                get(p + "/ship-address"),
                postJson(p + "/ship-address", "{\"name\":\"x\",\"street1\":\"x\",\"city\":\"x\",\"state\":\"IL\",\"zip\":\"62701\"}"),
                get(p + "/collections/" + collectionB + "/reports"),
                get(p + "/reports/" + reportB + "/download"));
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
            if (!what.contains(collectionB) && !what.contains(cardB) && !what.contains(photoB) && !what.contains(reportB)) {
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
                postJson("/api/admin/orgs/" + orgB + "/cards/" + cardB + "/authenticity-verifications",
                        "{\"status\":\"VERIFIED_AUTHENTIC\",\"method\":\"IN_HAND_INSPECTION\"}"),
                get("/api/admin/catalog-cards/" + catalogCard + "/prices"),
                postJson("/api/admin/catalog-cards/" + catalogCard + "/comps", "{}"),
                post("/api/admin/catalog-cards/" + catalogCard + "/prices/refresh"),
                get("/api/admin/valuation/defaults"),
                postJson("/api/admin/orgs/" + orgB + "/collections/" + collectionB + "/valuation-runs", "{}"),
                get("/api/admin/orgs/" + orgB + "/collections/" + collectionB + "/valuation-runs"),
                get("/api/admin/valuation-runs/" + runB),
                get("/api/admin/valuation-runs/" + runB + "/replay"),
                get("/api/admin/valuation-runs/" + runB + "/reports"),
                postJson("/api/admin/valuation-runs/" + runB + "/reports", "{\"type\":\"LENDER\"}"),
                get("/api/admin/reports/" + reportB + "/download"),
                get("/api/admin/offers/track-record"),
                get("/api/admin/shipping/charges"),
                post("/api/admin/deals/" + UUID.randomUUID() + "/label?buyerOrgId=" + UUID.randomUUID()),
                get("/api/admin/deals/awaiting-label"),
                get("/api/admin/cards/" + cardB + "/offer-preview"),
                postJson("/api/admin/deals/" + UUID.randomUUID() + "/received?buyerOrgId=" + UUID.randomUUID(), "{\"pricePaid\":1,\"note\":\"x\"}"),
                postJson("/api/admin/deals/" + UUID.randomUUID() + "/returned?buyerOrgId=" + UUID.randomUUID(),
                        "{\"reason\":\"NOT_GENUINE\",\"note\":\"x\"}"));
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

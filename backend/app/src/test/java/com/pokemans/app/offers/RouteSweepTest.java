package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.SecurityProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Every route the app really serves, read from Spring's own mapping, so a new endpoint is covered the day
 * it's added (audit of 2026-11-02). {@code CrossOrgAccessTest} names routes by hand and uses made-up deal
 * and listing ids; here the ids are real: a sold card, its label and its buyer exist, and each outsider
 * goes after them by every route, with a body that would be accepted.
 */
@SpringBootTest(properties = {"pokemans.condition.worker.enabled=false", "pokemans.collateral.enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class RouteSweepTest {

    private static final String UNSEEDED = "00000000-0000-0000-0000-00000000dead";

    /** The routes open to any signed-in user, each with its own check. */
    private static final List<String> NOT_ORG_SCOPED = List.of("/api/me", "/api/invites/{token}", "/api/invites/{token}/accept");

    /** A valid body for each route that needs one: validation runs before the controller, so a bad body proves nothing. */
    private static final Map<String, String> BODIES = Map.ofEntries(
            Map.entry("/api/orgs/{orgId}/consent", "{\"version\":\"" + Consents.SELLER_TERMS_VERSION + "\"}"),
            Map.entry("/api/orgs/{orgId}/batches", "{\"name\":\"x\"}"),
            Map.entry("/api/orgs/{orgId}/collections", "{\"name\":\"x\"}"),
            Map.entry("/api/orgs/{orgId}/batches/{batchId}/cards", "{\"label\":\"x\",\"lot\":false,\"quantity\":1}"),
            Map.entry("/api/orgs/{orgId}/collections/{collectionId}/cards", "{\"label\":\"x\",\"lot\":false,\"quantity\":1}"),
            Map.entry("/api/orgs/{orgId}/cards/{cardId}/photos", "{\"side\":\"DETAIL\",\"contentType\":\"image/png\",\"sizeBytes\":100}"),
            Map.entry("/api/orgs/{orgId}/cards/{cardId}/outcomes", "{\"grader\":\"PSA\",\"certNumber\":\"1\",\"grade\":9}"),
            Map.entry("/api/orgs/{orgId}/cards/{cardId}/custody-records",
                    "{\"holderType\":\"OWNER\",\"holderName\":\"x\",\"location\":\"x\",\"heldSince\":\"2026-01-01\"}"),
            Map.entry("/api/orgs/{orgId}/cards/{cardId}/listings", "{\"offerId\":\"%OFFER%\"}"),
            Map.entry("/api/orgs/{orgId}/ship-address", TestMarket.ADDRESS.formatted("Thief")),
            Map.entry("/api/orgs/{orgId}/deals/{listingId}/received", "{\"pricePaid\":1}"),
            Map.entry("/api/orgs/{orgId}/deals/{listingId}/paid", "{\"pricePaid\":1}"),
            Map.entry("/api/orgs/{orgId}/listings/{listingId}/dispute", "{\"note\":\"x\"}"),
            Map.entry("/api/orgs/{orgId}/deals/{listingId}/returned", "{\"reason\":\"NOT_GENUINE\"}"));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private TestApi api;
    private TestMarket market;
    private Seller victimSeller;
    private Buyer victimBuyer;
    private Posted sold;
    private String photoId;
    private String batchId;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        market = new TestMarket(api);
        victimSeller = market.seller();
        victimBuyer = market.buyer();
        sold = market.posted(victimSeller, "Stolen Charizard");
        api.postJson(TestMarket.accept(victimBuyer, sold), "", victimBuyer.who()).andExpect(status().isOk());
        // A sold card's photos are frozen, so the photo waiting to be confirmed is on another of the victim's cards.
        String unsold = api.card(victimSeller.orgId(), "Unsold", victimSeller.who());
        photoId = TestApi.read(api.postJson("/api/orgs/" + victimSeller.orgId() + "/cards/" + unsold + "/photos",
                "{\"side\":\"FRONT\",\"contentType\":\"image/png\",\"sizeBytes\":100}", victimSeller.who()), "$.photoId");
        // Only partner orgs may open batches through the API, so the victim's batch is written straight to the table.
        batchId = UUID.randomUUID().toString();
        jdbc.update("insert into submission_batch (id, org_id, name, created_by, created_at) "
                + "values (?::uuid, ?::uuid, 'Victim batch', (select id from app_user limit 1), now())", batchId, victimSeller.orgId());
    }

    @Test
    void everyRouteIsEitherAdminOrOrgScopedOrOnTheShortList() {
        for (Route route : routes()) {
            assertThat(route.path().startsWith("/api/admin/") || route.path().startsWith("/api/orgs/{orgId}/")
                    || NOT_ORG_SCOPED.contains(route.path())).as(route + ": nothing checks who may use this").isTrue();
        }
    }

    @Test
    void noRouteUnderAdminAnswersAnyoneButTheAdmin() throws Exception {
        JwtRequestPostProcessor member = victimSeller.who();
        JwtRequestPostProcessor buyer = victimBuyer.who();
        for (Route route : routes()) {
            if (route.path().startsWith("/api/admin/")) {
                String url = fill(route.path(), victimSeller.orgId());
                assertThat(call(route, url, member)).as("a seller on " + route).isEqualTo(403);
                assertThat(call(route, url, buyer)).as("a buyer on " + route).isEqualTo(403);
            }
        }
    }

    @Test
    void anOutsiderNamingTheVictimsOrgGetsNothing() throws Exception {
        String before = fingerprint();
        Seller outsider = market.seller();
        Buyer outsiderBuyer = market.buyer();
        for (Route route : orgRoutes()) {
            for (String org : List.of(victimSeller.orgId(), victimBuyer.orgId())) {
                for (JwtRequestPostProcessor who : List.of(outsider.who(), outsiderBuyer.who(), signedInWithNoOrg())) {
                    assertThat(call(route, fill(route.path(), org), who)).as(route + " on " + org).isEqualTo(404);
                }
            }
        }
        assertThat(fingerprint()).as("what the outsiders left behind").isEqualTo(before);
    }

    @Test
    void anOutsiderUsingTheirOwnOrgWithTheVictimsIdsGetsNothing() throws Exception {
        // The classic hole: the org checks out, the id belongs to someone else. Refused without changing anything.
        Seller outsider = market.seller();
        Buyer outsiderBuyer = market.buyer();
        String before = fingerprint();
        for (Route route : orgRoutes()) {
            if (!namesAResource(route.path())) {
                continue;
            }
            assertThat(call(route, fill(route.path(), outsider.orgId()), outsider.who())).as("a seller on " + route).isIn(403, 404);
            // Any buyer may try for a listing, and this one is taken: the only route where a stranger's id is not a 404.
            boolean market = route.path().endsWith("/market/{listingId}/accept");
            assertThat(call(route, fill(route.path(), outsiderBuyer.orgId()), outsiderBuyer.who())).as("a buyer on " + route)
                    .isIn(market ? List.of(409) : List.of(403, 404));
        }
        assertThat(fingerprint()).as("what the outsiders left behind").isEqualTo(before);
    }

    @Test
    void aMemberOfTheWrongKindOfOrgCantUseTheOtherSidesRoutes() throws Exception {
        // A seller reaching the market or a deal under their own org, and a buyer reaching the seller's routes.
        for (String path : List.of("/market", "/deals")) {
            api.get("/api/orgs/" + victimSeller.orgId() + path, victimSeller.who()).andExpect(status().isNotFound());
        }
        api.postJson("/api/orgs/" + victimSeller.orgId() + "/market/" + sold.listingId() + "/accept", "", victimSeller.who())
                .andExpect(status().isNotFound());
        api.postJson("/api/orgs/" + victimSeller.orgId() + "/deals/" + sold.listingId() + "/label", "", victimSeller.who())
                .andExpect(status().isNotFound());
        api.get("/api/orgs/" + victimBuyer.orgId() + "/cards/" + sold.cardId() + "/sale", victimBuyer.who()).andExpect(status().isNotFound());
        api.postJson("/api/orgs/" + victimBuyer.orgId() + "/listings/" + sold.listingId() + "/withdraw", "", victimBuyer.who())
                .andExpect(status().isNotFound());
    }

    private JwtRequestPostProcessor signedInWithNoOrg() {
        return api.user("auth0|stranger-" + UUID.randomUUID(), List.of());
    }

    /** What an outsider could have changed: rows in the tables behind these routes, and the victim's current address. */
    private String fingerprint() {
        return jdbc.queryForObject("""
                select concat_ws('/',
                  (select count(*) from listing_event where listing_id = ?::uuid),
                  (select count(*) from shipping_label where listing_id = ?::uuid),
                  (select count(*) from photo where card_item_id = ?::uuid),
                  (select count(*) from card_offer where card_item_id = ?::uuid),
                  (select count(*) from sale_listing where card_item_id = ?::uuid),
                  (select count(*) from custody_record where card_item_id = ?::uuid),
                  (select count(*) from ship_address where org_id = ?::uuid),
                  (select count(*) from ship_address where org_id = ?::uuid),
                  (select count(*) from card_item where org_id = ?::uuid),
                  (select count(*) from membership where org_id = ?::uuid),
                  (select count(*) from membership where org_id = ?::uuid))""", String.class,
                sold.listingId(), sold.listingId(), sold.cardId(), sold.cardId(), sold.cardId(), sold.cardId(),
                victimSeller.orgId(), victimBuyer.orgId(), victimSeller.orgId(), victimSeller.orgId(), victimBuyer.orgId());
    }

    private static boolean namesAResource(String path) {
        return path.replace("{orgId}", "").contains("{");
    }

    private int call(Route route, String url, JwtRequestPostProcessor who) throws Exception {
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(route.method().name()), url).with(who);
        if (route.method() == RequestMethod.POST) {
            String body = BODIES.getOrDefault(route.path(), "{}").replace("%OFFER%", sold.offerId());
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    /** The path with its variables replaced by the victim's real ids. */
    private String fill(String path, String org) {
        Map<String, String> ids = Map.ofEntries(Map.entry("orgId", org), Map.entry("cardId", sold.cardId()),
                Map.entry("collectionId", victimSeller.collectionId()), Map.entry("listingId", sold.listingId()),
                Map.entry("offerId", sold.offerId()), Map.entry("photoId", photoId),
                Map.entry("batchId", batchId),
                // No fixture seeds these yet (valuation runs, reports, memberships, ...), so each is named here on purpose:
                // a new path variable fails the sweep until someone adds it, instead of silently getting a random id.
                Map.entry("reportId", UNSEEDED), Map.entry("runId", UNSEEDED), Map.entry("outcomeId", UNSEEDED),
                Map.entry("membershipId", UNSEEDED), Map.entry("inviteId", UNSEEDED), Map.entry("catalogCardId", UNSEEDED),
                Map.entry("externalId", "base1-4"), Map.entry("token", "unseeded-token"));
        Matcher m = Pattern.compile("\\{(\\w+)}").matcher(path);
        StringBuilder url = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(url, requireId(ids, m.group(1), path));
        }
        m.appendTail(url);
        return url.toString();
    }

    private static String requireId(Map<String, String> ids, String name, String path) {
        String id = ids.get(name);
        assertThat(id).as("{" + name + "} in " + path + " has no real id: seed one rather than attacking a random UUID").isNotNull();
        return id;
    }

    private List<Route> orgRoutes() {
        return routes().stream().filter(r -> r.path().startsWith("/api/orgs/{orgId}/")).toList();
    }

    private List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String path : info.getPatternValues()) {
                if (path.startsWith("/api")) {
                    info.getMethodsCondition().getMethods().forEach(m -> routes.add(new Route(m, path)));
                }
            }
        }
        assertThat(routes).as("Spring's mapping, or this test has stopped looking").hasSizeGreaterThan(60);
        return routes;
    }

    private record Route(RequestMethod method, String path) {
    }
}

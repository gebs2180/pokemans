package com.pokemans.app;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.identity.Consents;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** Sellers, buyers and posted cards for tests that need a real market, built through the API. */
public class TestMarket {

    public static final String ADDRESS = "{\"name\":\"%s\",\"street1\":\"1 Main St\",\"city\":\"Springfield\",\"state\":\"IL\",\"zip\":\"62701\"}";
    private static final String NEAR_MINT = "{\"distribution\":{\"9\":0.5,\"10\":0.5},\"confidence\":\"A\",\"reason\":\"reviewed\"}";
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    public record Seller(String orgId, String collectionId, JwtRequestPostProcessor who) {
    }

    public record Buyer(String orgId, JwtRequestPostProcessor who) {
    }

    public record Posted(String cardId, String offerId, String listingId) {
    }

    private final TestApi api;

    public TestMarket(TestApi api) {
        this.api = api;
    }

    /** A collector org whose member accepted the terms and has an address on file. */
    public Seller seller() throws Exception {
        return seller("auth0|seller-" + UUID.randomUUID());
    }

    /** {@link #seller()}, for a given login. */
    public Seller seller(String subject) throws Exception {
        String orgId = api.collectorOrg("Seller " + UUID.randomUUID(), subject);
        JwtRequestPostProcessor who = api.user(subject, List.of());
        String collectionId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"For sale\"}", who), "$.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", ADDRESS.formatted("Sam"), who).andExpect(status().isCreated());
        return new Seller(orgId, collectionId, who);
    }

    /** A buyer org whose member accepted the terms and has an address on file. */
    public Buyer buyer() throws Exception {
        return buyer("auth0|buyer-" + UUID.randomUUID());
    }

    /** {@link #buyer()}, for a given login. */
    public Buyer buyer(String subject) throws Exception {
        String orgId = TestApi.read(api.postJson("/api/admin/orgs", "{\"name\":\"Buyer " + UUID.randomUUID() + "\",\"type\":\"BUYER\"}", api.admin())
                .andExpect(status().isCreated()), "$.id");
        String token = TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/invites", "{}", api.admin()), "$.token");
        JwtRequestPostProcessor who = api.user(subject, List.of());
        api.postJson("/api/invites/" + token + "/accept", "", who).andExpect(status().isOk());
        api.postJson("/api/orgs/" + orgId + "/consent", "{\"version\":\"" + Consents.BUYER_TERMS_VERSION + "\"}", who).andExpect(status().isOk());
        api.postJson("/api/orgs/" + orgId + "/ship-address", ADDRESS.formatted("Buyer"), who).andExpect(status().isCreated());
        return new Buyer(orgId, who);
    }

    /** A priced card with a new offer on it, not posted yet. */
    public Posted offered(Seller seller, String name) throws Exception {
        String cardId = TestApi.read(api.postJson("/api/orgs/" + seller.orgId() + "/collections/" + seller.collectionId() + "/cards",
                "{\"label\":\"" + name + "\",\"lot\":false,\"quantity\":1}", seller.who()).andExpect(status().isCreated()), "$.id");
        String catalog = TestApi.read(api.postJson("/api/admin/orgs/" + seller.orgId() + "/cards/" + cardId + "/identifications",
                "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"NORMAL\",\"setName\":\"Set\","
                        + "\"setReleaseDate\":\"2015-05-06\",\"number\":\"" + UUID.randomUUID().toString().substring(0, 6)
                        + "\",\"name\":\"" + name + " " + UUID.randomUUID().toString().substring(0, 4) + "\",\"rarity\":\"Rare\"}",
                api.admin()).andExpect(status().isCreated()), "$.current.card.id");
        priceLadder(catalog);
        api.postJson("/api/admin/orgs/" + seller.orgId() + "/cards/" + cardId + "/assessments", NEAR_MINT, api.admin())
                .andExpect(status().isCreated());
        String offerId = TestApi.read(api.postJson("/api/orgs/" + seller.orgId() + "/cards/" + cardId + "/offers", "", seller.who())
                .andExpect(status().isCreated()), "$.id");
        return new Posted(cardId, offerId, null);
    }

    /** A priced, offered card posted to buyers. */
    public Posted posted(Seller seller, String name) throws Exception {
        Posted offered = offered(seller, name);
        api.postJson("/api/orgs/" + seller.orgId() + "/cards/" + offered.cardId() + "/listings",
                "{\"offerId\":\"" + offered.offerId() + "\"}", seller.who()).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get("/api/orgs/" + seller.orgId() + "/cards/" + offered.cardId() + "/sale", seller.who()), "$.listing.id");
        return new Posted(offered.cardId(), offered.offerId(), listingId);
    }

    public static String accept(Buyer buyer, Posted posted) {
        return "/api/orgs/" + buyer.orgId() + "/market/" + posted.listingId() + "/accept";
    }

    /** PSA sales at 7 to 10, six per grade over three months: confidence B and a measured sigma. */
    private void priceLadder(String catalog) throws Exception {
        Map<Integer, Double> share = Map.of(10, 1.0, 9, 0.6, 8, 0.4, 7, 0.3);
        for (Map.Entry<Integer, Double> grade : share.entrySet()) {
            for (int daysAgo : new int[] {2, 5, 33, 36, 63, 66}) {
                api.postJson("/api/admin/catalog-cards/" + catalog + "/comps", "{\"gradingCompany\":\"PSA\",\"grade\":" + grade.getKey()
                        + ",\"price\":" + Math.round(200 * grade.getValue()) + ",\"soldOn\":\"" + TODAY.minusDays(daysAgo)
                        + "\",\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\"}", api.admin()).andExpect(status().isCreated());
            }
        }
        api.postJson("/api/admin/catalog-cards/" + catalog + "/prices/refresh", "", api.admin()).andExpect(status().isOk());
    }
}

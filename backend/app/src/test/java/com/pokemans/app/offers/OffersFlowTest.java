package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.SecurityProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Instant offers end to end: a collector gets an offer and posts it, two buyers race for it, the
 * winner sees the seller and records the inspection; withdrawn and ineligible cards get nowhere.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class OffersFlowTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final String NEAR_MINT = "{\"distribution\":{\"9\":0.5,\"10\":0.5},\"confidence\":\"A\",\"reason\":\"reviewed\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    private TestApi api;
    private String orgId;
    private String collectionId;
    private String sellerName;
    private JwtRequestPostProcessor seller;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        String subject = "auth0|seller-" + UUID.randomUUID();
        sellerName = "Seller " + UUID.randomUUID();
        orgId = api.collectorOrg(sellerName, subject);
        seller = api.user(subject, List.of());
        collectionId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"For sale\"}", seller), "$.id");
    }

    @Test
    void anOfferIsPostedTakenByTheFirstBuyerAndInspected() throws Exception {
        String card = pricedCard("Umbreon", 300);

        // No offer yet; then one, with a price for every grade, best first, never more for a worse grade.
        api.get(sale(card), seller).andExpect(status().isOk()).andExpect(jsonPath("$.offer").isEmpty());
        String offer = api.postJson(offers(card), "", seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.ladder.length()").value(11))
                .andExpect(jsonPath("$.ladder[0].grade").value("10"))
                .andExpect(jsonPath("$.subjectToAuthentication").value(false))
                .andExpect(jsonPath("$.expired").value(false))
                .andReturn().getResponse().getContentAsString();
        String offerId = JsonPath.read(offer, "$.id");
        List<Double> prices = JsonPath.read(offer, "$.ladder[*].price");
        for (int i = 1; i < prices.size(); i++) {
            assertThat(prices.get(i)).isLessThanOrEqualTo(prices.get(i - 1));
        }
        double ten = prices.get(0);
        double nine = prices.get(1);
        assertThat(ten).isGreaterThan(nine).isLessThan(300);
        assertThat(((Number) JsonPath.read(offer, "$.expectedOffer")).doubleValue()).isCloseTo((ten + nine) / 2, org.assertj.core.data.Offset.offset(0.01));

        // Buyers must take the terms before they see anything.
        JwtRequestPostProcessor first = buyer("First Buyer");
        String firstOrg = lastBuyerOrg;
        JwtRequestPostProcessor second = buyer("Second Buyer");
        String secondOrg = lastBuyerOrg;
        JwtRequestPostProcessor latecomer = buyerWithoutTerms();
        api.get("/api/orgs/" + lastBuyerOrg + "/market", latecomer).andExpect(status().isForbidden());
        api.get("/api/orgs/" + orgId + "/market", seller).andExpect(status().isNotFound());

        // Posting is not a sale; posting twice is refused.
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.listing.status").value("OPEN"));
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isConflict());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");

        String market = api.get("/api/orgs/" + firstOrg + "/market", first).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> item = JsonPath.read(market, "$[?(@.listingId == '" + listingId + "')]");
        assertThat(item).hasSize(1);
        assertThat(JsonPath.<List<String>>read(market, "$[?(@.listingId == '" + listingId + "')].card.name").getFirst()).contains("Umbreon");
        assertThat(market).doesNotContain(sellerName); // who is selling stays private until a buyer commits

        // First come, first served.
        api.postJson("/api/orgs/" + firstOrg + "/market/" + listingId + "/accept", "", first).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seller.orgName").value(sellerName));
        api.postJson("/api/orgs/" + secondOrg + "/market/" + listingId + "/accept", "", second).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("Another buyer took this card first")));
        assertThat(api.get("/api/orgs/" + secondOrg + "/market", second).andReturn().getResponse().getContentAsString())
                .doesNotContain(listingId);
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.listing.buyer.orgName").value("First Buyer"));
        api.postJson("/api/orgs/" + orgId + "/listings/" + listingId + "/withdraw", "", seller).andExpect(status().isConflict());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isConflict());

        // The buyer records what they found; only the buyer who took it, once, in half steps.
        String inspection = "/api/orgs/" + firstOrg + "/deals/" + listingId + "/inspection";
        api.postJson("/api/orgs/" + secondOrg + "/deals/" + listingId + "/inspection",
                "{\"inspectedGrade\":9,\"authentic\":true,\"pricePaid\":10}", second).andExpect(status().isNotFound());
        api.postJson(inspection, "{\"inspectedGrade\":8.3,\"authentic\":true,\"pricePaid\":10}", first).andExpect(status().isBadRequest());
        api.postJson(inspection, "{\"inspectedGrade\":9,\"authentic\":true}", first).andExpect(status().isBadRequest());
        api.postJson(inspection, "{\"inspectedGrade\":9.5,\"authentic\":true,\"pricePaid\":" + nine + "}", first)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].inspection.inspectedGrade").value(9.5))
                .andExpect(jsonPath("$[0].inspection.ladderPrice").value(nine)); // 9.5 folds to 9
        api.postJson(inspection, "{\"inspectedGrade\":9,\"authentic\":true,\"pricePaid\":1}", first).andExpect(status().isConflict());
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.inspection.pricePaid").value(nine));

        assertThatThrownBy(() -> jdbc.update("update listing_event set price_paid = 1 where listing_id = ?::uuid", listingId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update card_offer set expected_offer = 1 where id = ?::uuid", offerId))
                .hasMessageContaining("append-only");
    }

    @Test
    void aWithdrawnListingCantBeTaken() throws Exception {
        String card = pricedCard("Gengar", 200);
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");
        JwtRequestPostProcessor buyer = buyer("Late Buyer");

        api.postJson("/api/orgs/" + orgId + "/listings/" + listingId + "/withdraw", "", seller).andExpect(status().isOk());
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.status").value("WITHDRAWN"));
        api.postJson("/api/orgs/" + lastBuyerOrg + "/market/" + listingId + "/accept", "", buyer).andExpect(status().isConflict());
        // Posted again, the same offer is open again.
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.listing.status").value("OPEN"));
    }

    @Test
    void cardsThatCantBePricedGetNoOfferAndSayWhy() throws Exception {
        String lot = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections/" + collectionId + "/cards",
                "{\"label\":\"Bulk\",\"lot\":true,\"quantity\":40}", seller), "$.id");
        api.postJson(offers(lot), "", seller).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("Lots")));
        String mystery = card("Mystery");
        api.postJson(offers(mystery), "", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("identified")));
        String unsold = card("Never sold");
        identify(unsold, "Never Sold", "2015-05-06", "NORMAL", "Rare");
        assess(unsold);
        api.postJson(offers(unsold), "", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("sold comps")));
        String fake = pricedCard("Fake", 300);
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + fake + "/authenticity-verifications",
                "{\"status\":\"COUNTERFEIT\",\"method\":\"IN_HAND_INSPECTION\"}", api.admin()).andExpect(status().isCreated());
        api.postJson(offers(fake), "", seller).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("doubt")));

        // A vintage holo nobody has verified still gets an offer, subject to authentication in hand.
        String vintage = card("Base Set Charizard");
        priceLadder(identify(vintage, "Charizard", "1999-01-09", "HOLO", "Rare Holo"), 900);
        assess(vintage);
        api.postJson(offers(vintage), "", seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectToAuthentication").value(true));
    }

    @Test
    void aListingIsOpenUntilClosedOrExpired() {
        Instant now = Instant.now();
        SaleListing open = new SaleListing(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(60));
        SaleListing old = new SaleListing(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, now.minusSeconds(1));

        assertThat(ListingService.status(open, List.of(), now)).isEqualTo(ListingService.Status.OPEN);
        assertThat(ListingService.status(old, List.of(), now)).isEqualTo(ListingService.Status.EXPIRED);
        assertThat(ListingService.status(old, List.of(ListingEvent.accepted(old.getId(), UUID.randomUUID(), UUID.randomUUID())), now))
                .isEqualTo(ListingService.Status.ACCEPTED);
        assertThat(OfferService.priceAt(List.of(new OfferService.Rung(com.pokemans.valuation.Grade.G8, BigDecimal.ONE, new BigDecimal("50"))),
                new BigDecimal("8.5"))).isEqualByComparingTo("50");
    }

    private String lastBuyerOrg;

    private JwtRequestPostProcessor buyer(String name) throws Exception {
        JwtRequestPostProcessor who = buyerWithoutTerms(name);
        api.postJson("/api/orgs/" + lastBuyerOrg + "/consent", "{\"version\":\"" + Consents.BUYER_TERMS_VERSION + "\"}", who)
                .andExpect(status().isOk());
        return who;
    }

    private JwtRequestPostProcessor buyerWithoutTerms() throws Exception {
        return buyerWithoutTerms("Buyer " + UUID.randomUUID());
    }

    private JwtRequestPostProcessor buyerWithoutTerms(String name) throws Exception {
        lastBuyerOrg = TestApi.read(api.postJson("/api/admin/orgs", "{\"name\":\"" + name + "\",\"type\":\"BUYER\"}", api.admin())
                .andExpect(status().isCreated()), "$.id");
        String token = TestApi.read(api.postJson("/api/admin/orgs/" + lastBuyerOrg + "/invites", "{}", api.admin()), "$.token");
        JwtRequestPostProcessor who = api.user("auth0|buyer-" + UUID.randomUUID(), List.of());
        api.postJson("/api/invites/" + token + "/accept", "", who).andExpect(status().isOk());
        return who;
    }

    private String pricedCard(String name, int top) throws Exception {
        String card = card(name);
        priceLadder(identify(card, name + " " + UUID.randomUUID().toString().substring(0, 4), "2015-05-06", "NORMAL", "Rare"), top);
        assess(card);
        return card;
    }

    private String card(String label) throws Exception {
        return TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections/" + collectionId + "/cards",
                "{\"label\":\"" + label + "\",\"lot\":false,\"quantity\":1}", seller).andExpect(status().isCreated()), "$.id");
    }

    private String identify(String card, String name, String released, String variant, String rarity) throws Exception {
        return TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + card + "/identifications",
                "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"" + variant + "\","
                        + "\"setName\":\"Set " + released + "\",\"setReleaseDate\":\"" + released + "\",\"number\":\""
                        + UUID.randomUUID().toString().substring(0, 6) + "\",\"name\":\"" + name + "\",\"rarity\":\"" + rarity + "\"}",
                api.admin()).andExpect(status().isCreated()), "$.current.card.id");
    }

    private void assess(String card) throws Exception {
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + card + "/assessments", NEAR_MINT, api.admin()).andExpect(status().isCreated());
    }

    /** PSA sales at 7 to 10, six per grade over three months: confidence B and a measured sigma. */
    private void priceLadder(String catalog, int top) throws Exception {
        Map<Integer, Double> share = Map.of(10, 1.0, 9, 0.6, 8, 0.4, 7, 0.3);
        for (Map.Entry<Integer, Double> grade : share.entrySet()) {
            for (int daysAgo : new int[] {2, 5, 33, 36, 63, 66}) {
                api.postJson("/api/admin/catalog-cards/" + catalog + "/comps", "{\"gradingCompany\":\"PSA\",\"grade\":" + grade.getKey()
                        + ",\"price\":" + Math.round(top * grade.getValue()) + ",\"soldOn\":\"" + TODAY.minusDays(daysAgo)
                        + "\",\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\"}", api.admin()).andExpect(status().isCreated());
            }
        }
        api.postJson("/api/admin/catalog-cards/" + catalog + "/prices/refresh", "", api.admin()).andExpect(status().isOk());
    }

    private String sale(String card) {
        return "/api/orgs/" + orgId + "/cards/" + card + "/sale";
    }

    private String offers(String card) {
        return "/api/orgs/" + orgId + "/cards/" + card + "/offers";
    }

    private String listings(String card) {
        return "/api/orgs/" + orgId + "/cards/" + card + "/listings";
    }
}

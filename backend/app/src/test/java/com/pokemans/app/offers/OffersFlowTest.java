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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.data.Offset;
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
 * Firm instant offers end to end: a collector gets one price and posts it, two buyers race for it, the
 * winner's label is bought at once, and the buyer records the arrival or a return. Withdrawn and
 * ineligible cards get nowhere, and the track record counts all of it.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class OffersFlowTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final String NEAR_MINT = "{\"distribution\":{\"9\":0.5,\"10\":0.5},\"confidence\":\"A\",\"reason\":\"reviewed\"}";
    private static final String ADDRESS = "{\"name\":\"%s\",\"street1\":\"1 Main St\",\"city\":\"Springfield\",\"state\":\"IL\",\"zip\":\"62701\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FakeShippingProvider shipper;

    private TestApi api;
    private String orgId;
    private String collectionId;
    private String sellerName;
    private JwtRequestPostProcessor seller;
    private String lastBuyerOrg;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        String subject = "auth0|seller-" + UUID.randomUUID();
        sellerName = "Seller " + UUID.randomUUID();
        orgId = api.collectorOrg(sellerName, subject);
        seller = api.user(subject, List.of());
        collectionId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"For sale\"}", seller), "$.id");
        shipper.failing = false;
    }

    @Test
    void aFirmOfferIsTakenLabelledAndReceived() throws Exception {
        String card = pricedCard("Umbreon", 300);

        // One firm price: the expected value of what a buyer could pay at each grade (half 9, half 10 here).
        api.get(sale(card), seller).andExpect(status().isOk()).andExpect(jsonPath("$.offer").isEmpty());
        String offer = api.postJson(offers(card), "", seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.ladder.length()").value(11))
                .andExpect(jsonPath("$.ladder[0].grade").value("10"))
                .andExpect(jsonPath("$.expired").value(false))
                .andReturn().getResponse().getContentAsString();
        String offerId = JsonPath.read(offer, "$.id");
        List<Double> rungs = JsonPath.read(offer, "$.ladder[*].price");
        double price = ((Number) JsonPath.read(offer, "$.price")).doubleValue();
        assertThat(price).isCloseTo((rungs.get(0) + rungs.get(1)) / 2, Offset.offset(0.01)).isLessThan(300);

        // The seller needs an address before posting, since the label is bought on accept.
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("address")));
        api.postJson("/api/orgs/" + orgId + "/ship-address", ADDRESS.formatted("Sam").replace("62701", "627"), seller)
                .andExpect(status().isBadRequest());
        api.postJson("/api/orgs/" + orgId + "/ship-address", ADDRESS.formatted("Sam"), seller).andExpect(status().isCreated());
        api.get("/api/orgs/" + orgId + "/ship-address", seller).andExpect(jsonPath("$.zip").value("62701"));
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.listing.status").value("OPEN"));
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isConflict());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");

        // Buyers take the terms and give an address first.
        JwtRequestPostProcessor first = buyerWithoutTerms("First Buyer");
        String firstOrg = lastBuyerOrg;
        api.get("/api/orgs/" + firstOrg + "/market", first).andExpect(status().isForbidden());
        acceptTerms(firstOrg, first);
        String market = api.get("/api/orgs/" + firstOrg + "/market", first).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(market, "$[?(@.listingId == '" + listingId + "')]")).hasSize(1);
        assertThat(market).doesNotContain(sellerName); // who is selling stays private until a buyer commits
        api.postJson(accept(firstOrg, listingId), "", first).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("address")));
        address(firstOrg, first, "First Buyer");
        JwtRequestPostProcessor second = buyer("Second Buyer");
        String secondOrg = lastBuyerOrg;

        // First come, first served; the label is bought at once, from the seller to the buyer, insured at the price.
        api.postJson(accept(firstOrg, listingId), "", first).andExpect(status().isOk())
                .andExpect(jsonPath("$.labelPending").isEmpty())
                .andExpect(jsonPath("$.deals[0].seller.orgName").value(sellerName))
                .andExpect(jsonPath("$.deals[0].label.trackingCode").isNotEmpty())
                .andExpect(jsonPath("$.deals[0].label.insuredValue").value(price));
        FakeShippingProvider.Bought label = shipper.bought.stream().filter(b -> b.reference().equals(listingId)).findFirst().orElseThrow();
        assertThat(label.from().name()).isEqualTo("Sam");
        assertThat(label.to().name()).isEqualTo("First Buyer");
        api.postJson(accept(secondOrg, listingId), "", second).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("Another buyer took this card first")));
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.listing.buyer.orgName").value("First Buyer"))
                .andExpect(jsonPath("$.listing.label.labelUrl", containsString("https://labels.example/")));
        api.postJson("/api/orgs/" + orgId + "/listings/" + listingId + "/withdraw", "", seller).andExpect(status().isConflict());

        // On arrival: received and paid, once, by the buyer who took it.
        String received = "/api/orgs/" + firstOrg + "/deals/" + listingId + "/received";
        api.postJson("/api/orgs/" + secondOrg + "/deals/" + listingId + "/received", "{\"pricePaid\":" + price + "}", second)
                .andExpect(status().isNotFound());
        api.postJson(received, "{}", first).andExpect(status().isBadRequest());
        api.postJson(received, "{\"pricePaid\":" + price + "}", first).andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].outcome.type").value("RECEIVED"));
        api.postJson("/api/orgs/" + firstOrg + "/deals/" + listingId + "/returned", "{\"reason\":\"NOT_GENUINE\"}", first)
                .andExpect(status().isConflict());
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.outcome.pricePaid").value(price));

        // The month's postage, per buyer, for billing.
        String charges = api.get("/api/admin/shipping/charges", api.admin()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<Object>>read(charges, "$[?(@.buyerOrgId == '" + firstOrg + "')].labels")).containsExactly(1);
        api.get("/api/admin/shipping/charges?month=October", api.admin()).andExpect(status().isBadRequest());
        api.get("/api/admin/shipping/charges", first).andExpect(status().isForbidden());

        assertThatThrownBy(() -> jdbc.update("update shipping_label set postage = 0 where listing_id = ?::uuid", listingId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update ship_address set zip = '00000' where org_id = ?::uuid", orgId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update card_offer set price = 1 where id = ?::uuid", offerId))
                .hasMessageContaining("append-only");
    }

    @Test
    void aLabelFailureDoesNotUndoTheTakeAndCanBeBoughtAgain() throws Exception {
        String listingId = postedCard("Gengar", 200);
        JwtRequestPostProcessor buyer = buyer("Patient Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Patient Buyer");
        shipper.failing = true;

        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.labelPending", containsString("carrier is down")))
                .andExpect(jsonPath("$.deals[0].label").isEmpty());
        String retry = "/api/orgs/" + buyerOrg + "/deals/" + listingId + "/label";
        api.postJson(retry, "", buyer).andExpect(status().isBadGateway());

        shipper.failing = false;
        api.postJson(retry, "", buyer).andExpect(status().isOk()).andExpect(jsonPath("$[0].label.trackingCode").isNotEmpty());
        api.postJson(retry, "", buyer).andExpect(status().isOk()); // already bought: the same label, not a second one
        assertThat(shipper.bought.stream().filter(b -> b.reference().equals(listingId))).hasSize(1);
    }

    @Test
    void aCardThatIsNotAsShownGoesBack() throws Exception {
        String listingId = postedCard("Mewtwo", 250);
        JwtRequestPostProcessor buyer = buyer("Careful Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Careful Buyer");
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk());

        String returned = "/api/orgs/" + buyerOrg + "/deals/" + listingId + "/returned";
        api.postJson(returned, "{\"reason\":\"TOO_LOW_A_GRADE\"}", buyer).andExpect(status().isBadRequest());
        api.postJson(returned, "{\"reason\":\"DOES_NOT_MATCH_PHOTOS\",\"note\":\"crease not in the photos\"}", buyer)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].outcome.type").value("RETURNED"))
                .andExpect(jsonPath("$[0].outcome.returnedReason").value("DOES_NOT_MATCH_PHOTOS"))
                .andExpect(jsonPath("$[0].outcome.pricePaid").isEmpty());
    }

    @Test
    void aWithdrawnListingCantBeTaken() throws Exception {
        String listingId = postedCard("Gengar", 200);
        JwtRequestPostProcessor buyer = buyer("Late Buyer");
        address(lastBuyerOrg, buyer, "Late Buyer");

        api.postJson("/api/orgs/" + orgId + "/listings/" + listingId + "/withdraw", "", seller).andExpect(status().isOk());
        api.postJson(accept(lastBuyerOrg, listingId), "", buyer).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("withdrew")));
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
    void theTrackRecordCountsWhatHappenedToEveryListing() throws Exception {
        String trackRecord = "/api/admin/offers/track-record";
        String before = api.get(trackRecord, api.admin()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JwtRequestPostProcessor buyer = buyer("Track Record Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Track Record Buyer");

        String kept = postedCard("Espeon", 300);
        api.postJson(accept(buyerOrg, kept), "", buyer).andExpect(status().isOk());
        List<Number> prices = JsonPath.read(api.get("/api/orgs/" + buyerOrg + "/deals", buyer).andReturn().getResponse()
                .getContentAsString(), "$[?(@.listingId == '" + kept + "')].offer.price");
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + kept + "/received", "{\"pricePaid\":" + prices.getFirst() + "}", buyer)
                .andExpect(status().isCreated());
        String sentBack = postedCard("Vaporeon", 300);
        api.postJson(accept(buyerOrg, sentBack), "", buyer).andExpect(status().isOk());
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + sentBack + "/returned", "{\"reason\":\"NOT_GENUINE\"}", buyer)
                .andExpect(status().isCreated());
        String pulled = postedCard("Jolteon", 300);
        api.postJson("/api/orgs/" + orgId + "/listings/" + pulled + "/withdraw", "", seller).andExpect(status().isOk());

        String after = api.get(trackRecord, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.caveats.length()").value(TrackRecordService.CAVEATS.size()))
                .andReturn().getResponse().getContentAsString();
        assertThat(delta(after, before, "$.overall.accepted")).isEqualTo(2);
        assertThat(delta(after, before, "$.overall.withdrawn")).isEqualTo(1);
        assertThat(delta(after, before, "$.deliveries.received")).isEqualTo(1);
        assertThat(delta(after, before, "$.deliveries.returned")).isEqualTo(1);
        assertThat(delta(after, before, "$.deliveries.labels")).isEqualTo(2);
        assertThat(delta(after, before, "$.prices.atOffer")).isEqualTo(1);
        assertThat(delta(after, before, "$.shipping.labels")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(after, "$.deliveries.returnedBy.NOT_GENUINE")).isGreaterThanOrEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap("select * from v_offer_outcomes where listing_id = ?::uuid", UUID.fromString(kept));
        assertThat(row.get("outcome")).isEqualTo("RECEIVED");
        assertThat(row.get("carrier")).isEqualTo("USPS");
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
    }

    /** How much a count in the track record grew; other tests share the database. */
    private static int delta(String after, String before, String path) {
        return JsonPath.<Integer>read(after, path) - JsonPath.<Integer>read(before, path);
    }

    /** A priced card, offered and posted to buyers, with the seller's address on file. Returns the listing id. */
    private String postedCard(String name, int top) throws Exception {
        String card = pricedCard(name, top);
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", ADDRESS.formatted("Sam"), seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        return TestApi.read(api.get(sale(card), seller), "$.listing.id");
    }

    private void address(String org, JwtRequestPostProcessor who, String name) throws Exception {
        api.postJson("/api/orgs/" + org + "/ship-address", ADDRESS.formatted(name), who).andExpect(status().isCreated());
    }

    private JwtRequestPostProcessor buyer(String name) throws Exception {
        JwtRequestPostProcessor who = buyerWithoutTerms(name);
        acceptTerms(lastBuyerOrg, who);
        return who;
    }

    private void acceptTerms(String org, JwtRequestPostProcessor who) throws Exception {
        api.postJson("/api/orgs/" + org + "/consent", "{\"version\":\"" + Consents.BUYER_TERMS_VERSION + "\"}", who)
                .andExpect(status().isOk());
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

    private String accept(String buyerOrg, String listingId) {
        return "/api/orgs/" + buyerOrg + "/market/" + listingId + "/accept";
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

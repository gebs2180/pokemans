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
import com.pokemans.app.intake.TestImages;
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
        // The buyer's margin follows the card's confidence (parameter set 1.2.0).
        String confidence = JsonPath.read(offer, "$.confidence");
        double margin = ((Number) JsonPath.read(offer, "$.buyerMargin")).doubleValue();
        assertThat(margin).isEqualTo(Map.of("A", 0.10, "B", 0.13, "C", 0.17).get(confidence));

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

        // Above the maximum firm price, no offer (Zach, 2026-10-21; pokemans.offers.max-price, $500 by default).
        String grail = pricedCard("Grail", 3000);
        api.postJson(offers(grail), "", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("go up to $500")));
        assertThat(jdbc.queryForObject("select count(*) from card_offer where card_item_id = ?::uuid", Integer.class, grail)).isZero();

        // A vintage holo nobody has verified still gets an offer, subject to authentication in hand.
        String vintage = card("Base Set Charizard");
        priceLadder(identify(vintage, "Charizard", "1999-01-09", "HOLO", "Rare Holo"), 400);
        assess(vintage);
        api.postJson(offers(vintage), "", seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectToAuthentication").value(true));
    }

    @Test
    void aVoidedOfferComesOffTheMarketAndCantBeSold() throws Exception {
        String card = pricedCard("Flareon", 300);
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", ADDRESS.formatted("Sam"), seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");
        JwtRequestPostProcessor buyer = buyer("Void Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Void Buyer");

        String recent = api.get("/api/admin/offers", api.admin()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(recent, "$[?(@.offerId == '" + offerId + "')].listing.status")).containsExactly("OPEN");

        // Who, when and why: a reason is required.
        String voidPath = "/api/admin/offers/" + offerId + "/void";
        api.postJson(voidPath, "{\"reason\":\" \"}", api.admin()).andExpect(status().isBadRequest());
        api.postJson(voidPath, "{\"reason\":\"wrong set\"}", seller).andExpect(status().isForbidden());
        api.postJson(voidPath, "{\"reason\":\"wrong set\"}", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.voided.reason").value("wrong set"))
                .andExpect(jsonPath("$.voided.byUserId").isNotEmpty())
                .andExpect(jsonPath("$.listing.status").value("VOIDED"))
                .andExpect(jsonPath("$.listing.undone.reason").value("wrong set"));
        api.postJson(voidPath, "{\"reason\":\"again\"}", api.admin()).andExpect(status().isConflict());

        // Off the market, can't be taken, and the seller sees why.
        String market = api.get("/api/orgs/" + buyerOrg + "/market", buyer).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(market, "$[?(@.listingId == '" + listingId + "')]")).isEmpty();
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("no longer for sale")));
        api.get(sale(card), seller).andExpect(jsonPath("$.offer.voided.reason").value("wrong set"))
                .andExpect(jsonPath("$.listing.status").value("VOIDED"));
        api.postJson("/api/orgs/" + orgId + "/listings/" + listingId + "/withdraw", "", seller).andExpect(status().isConflict());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("withdrew this offer")));

        // A new offer can be posted.
        String fresh = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson(listings(card), "{\"offerId\":\"" + fresh + "\"}", seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.listing.status").value("OPEN"));

        assertThatThrownBy(() -> jdbc.update("update card_offer_void set reason = 'x' where offer_id = ?::uuid", UUID.fromString(offerId)))
                .hasMessageContaining("append-only");
        Map<String, Object> row = jdbc.queryForMap("select status, voided, undo_reason from v_offer_outcomes where listing_id = ?::uuid",
                UUID.fromString(listingId));
        assertThat(row).containsEntry("status", "VOIDED").containsEntry("voided", true).containsEntry("undo_reason", "wrong set");
    }

    @Test
    void aCancelledDealFreesTheCardAndItsLabelIsRefunded() throws Exception {
        String card = pricedCard("Leafeon", 300);
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", ADDRESS.formatted("Sam"), seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");
        JwtRequestPostProcessor buyer = buyer("Cancelled Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Cancelled Buyer");
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk());

        // A taken offer is undone by cancelling the deal, and a live deal's label isn't refunded.
        api.postJson("/api/admin/offers/" + offerId + "/void", "{\"reason\":\"x\"}", api.admin()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("cancel the deal")));
        String refund = "/api/admin/deals/" + listingId + "/label/refund";
        api.postJson(refund, "{\"reason\":\"unused\"}", api.admin()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("Cancel the deal first")));

        String cancel = "/api/admin/deals/" + listingId + "/cancel";
        api.postJson(cancel, "{}", api.admin()).andExpect(status().isBadRequest());
        api.postJson(cancel, "{\"reason\":\"seller sold it elsewhere\"}", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.status").value("CANCELLED"))
                .andExpect(jsonPath("$.listing.undone.reason").value("seller sold it elsewhere"))
                .andExpect(jsonPath("$.voided.reason").value("Deal cancelled: seller sold it elsewhere"));
        api.postJson(cancel, "{\"reason\":\"again\"}", api.admin()).andExpect(status().isConflict());

        // The buyer sees it cancelled and can't record anything more on it.
        api.get("/api/orgs/" + buyerOrg + "/deals", buyer)
                .andExpect(jsonPath("$[?(@.listingId == '" + listingId + "')].cancelled.reason").value("seller sold it elsewhere"));
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + listingId + "/received", "{\"pricePaid\":1}", buyer)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("cancelled")));
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + listingId + "/label", "", buyer).andExpect(status().isConflict());

        // The card is free for a new offer.
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.status").value("CANCELLED"))
                .andExpect(jsonPath("$.listing.undone.reason").value("seller sold it elsewhere"));
        String fresh = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson(listings(card), "{\"offerId\":\"" + fresh + "\"}", seller).andExpect(status().isCreated());

        // The label's refund: a provider that's down is recorded and can be asked again; one answer per label.
        shipper.failing = true;
        api.postJson(refund, "{\"reason\":\"unused\"}", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.labelRefund.providerStatus").isEmpty())
                .andExpect(jsonPath("$.listing.labelRefund.providerError", containsString("carrier is down")));
        shipper.failing = false;
        api.postJson(refund, "{\"reason\":\"unused\"}", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.labelRefund.providerStatus").value("submitted"))
                .andExpect(jsonPath("$.listing.labelRefund.reason").value("unused"));
        api.postJson(refund, "{\"reason\":\"unused\"}", api.admin()).andExpect(status().isConflict());
        String shipment = jdbc.queryForObject("select provider_shipment_id from shipping_label where listing_id = ?::uuid", String.class,
                UUID.fromString(listingId));
        assertThat(shipper.refunded).containsOnlyOnce(shipment);
        assertThat(jdbc.queryForObject("select count(*) from label_refund where listing_id = ?::uuid", Integer.class,
                UUID.fromString(listingId))).isEqualTo(2);

        // Listed, but not billed to the buyer.
        String charges = api.get("/api/admin/shipping/charges", api.admin()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(charges, "$[?(@.buyerOrgId == '" + buyerOrg + "')].labels")).containsExactly(0);
        assertThat(JsonPath.<List<Object>>read(charges, "$[*].lines[?(@.listingId == '" + listingId + "')].refund"))
                .containsExactly("submitted");

        assertThatThrownBy(() -> jdbc.update("update label_refund set reason = 'x' where listing_id = ?::uuid", UUID.fromString(listingId)))
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("select status from v_offer_outcomes where listing_id = ?::uuid", String.class,
                UUID.fromString(listingId))).isEqualTo("CANCELLED");
    }

    @Test
    void aDealCantBeCancelledOnceTheCardArrived() throws Exception {
        String listingId = postedCard("Glaceon", 300);
        JwtRequestPostProcessor buyer = buyer("Arrived Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Arrived Buyer");
        String cancel = "/api/admin/deals/" + listingId + "/cancel";
        api.postJson(cancel, "{\"reason\":\"x\"}", api.admin()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("void the offer")));
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk());
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + listingId + "/received", "{\"pricePaid\":10,\"note\":\"agreed a lower price\"}", buyer)
                .andExpect(status().isCreated());
        api.postJson(cancel, "{\"reason\":\"x\"}", api.admin()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("received")));
        api.postJson("/api/admin/deals/" + UUID.randomUUID() + "/cancel", "{\"reason\":\"x\"}", api.admin())
                .andExpect(status().isNotFound());
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
    void arrivalWaitsForTheLabelAndADifferentPriceNeedsAReason() throws Exception {
        String listingId = postedCard("Sylveon", 300);
        JwtRequestPostProcessor buyer = buyer("Early Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Early Buyer");
        shipper.failing = true;
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.deals[0].photos").isArray());
        double price = ((Number) JsonPath.read(api.get("/api/orgs/" + buyerOrg + "/deals", buyer).andReturn().getResponse()
                .getContentAsString(), "$[0].offer.price")).doubleValue();

        // Nothing can have shipped without a label, so nothing can have arrived or gone back.
        String received = "/api/orgs/" + buyerOrg + "/deals/" + listingId + "/received";
        api.postJson(received, "{\"pricePaid\":" + price + "}", buyer).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("no label")));
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + listingId + "/returned", "{\"reason\":\"NOT_GENUINE\"}", buyer)
                .andExpect(status().isConflict());

        // The admin sees the deal waiting for its label and buys it once shipping works.
        String waiting = api.get("/api/admin/deals/awaiting-label", api.admin()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(waiting, "$[?(@.listingId == '" + listingId + "')].buyer.orgName")).containsExactly("Early Buyer");
        assertThat(JsonPath.<List<String>>read(waiting, "$[?(@.listingId == '" + listingId + "')].seller.orgName")).containsExactly(sellerName);
        shipper.failing = false;
        api.postJson("/api/admin/deals/" + listingId + "/label?buyerOrgId=" + buyerOrg, "", api.admin()).andExpect(status().isOk());
        assertThat(api.get("/api/admin/deals/awaiting-label", api.admin()).andReturn().getResponse().getContentAsString())
                .doesNotContain(listingId);

        // Paying less than the firm price needs a reason.
        api.postJson(received, "{\"pricePaid\":" + (price - 5) + "}", buyer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("why the amount paid differs")));
        api.postJson(received, "{\"pricePaid\":" + (price - 5) + ",\"note\":\"seller agreed a discount for a soft corner\"}", buyer)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].outcome.note").value("seller agreed a discount for a soft corner"));
    }

    @Test
    void theAdminRecordsAnArrivalByHandWhenTheCardShippedWithoutOurLabel() throws Exception {
        String before = api.get("/api/admin/offers/track-record", api.admin()).andReturn().getResponse().getContentAsString();
        String listingId = postedCard("Leafeon", 300);
        JwtRequestPostProcessor buyer = buyer("Offline Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Offline Buyer");
        shipper.failing = true;
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk());
        String during = api.get("/api/admin/offers/track-record", api.admin()).andReturn().getResponse().getContentAsString();
        assertThat(delta(during, before, "$.deliveries.awaitingLabel")).isEqualTo(1);
        assertThat(delta(during, before, "$.deliveries.inTransit")).isZero();

        String byHand = "/api/admin/deals/" + listingId + "/received?buyerOrgId=" + buyerOrg;
        api.postJson(byHand, "{\"pricePaid\":10}", api.admin()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("how the card was shipped")));
        api.postJson(byHand, "{\"pricePaid\":10,\"note\":\"shipped with the seller's own stamps\"}", buyer).andExpect(status().isForbidden());
        api.postJson("/api/admin/deals/" + listingId + "/received?buyerOrgId=" + UUID.randomUUID(),
                "{\"pricePaid\":10,\"note\":\"x\"}", api.admin()).andExpect(status().isNotFound());
        api.postJson(byHand, "{\"pricePaid\":10,\"note\":\"shipped with the seller's own stamps\"}", api.admin()).andExpect(status().isCreated());
        api.get("/api/orgs/" + buyerOrg + "/deals", buyer)
                .andExpect(jsonPath("$[0].outcome.type").value("RECEIVED"))
                .andExpect(jsonPath("$[0].outcome.note").value("Recorded by the admin: shipped with the seller's own stamps"));
    }

    @Test
    void photosFreezeOnceTheCardIsPostedAndBuyersSeeOnlyTheCurrentOnes() throws Exception {
        String card = card("Photographed Umbreon");
        api.uploadPhoto(orgId, card, "FRONT", TestImages.goodBack(), seller).andExpect(jsonPath("$.status").value("PASSED")); // the back, by mistake
        api.uploadPhoto(orgId, card, "BACK", TestImages.goodRetake(0), seller).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, card, "FRONT", TestImages.goodFront(), seller).andExpect(jsonPath("$.status").value("PASSED")); // retaken
        api.get("/api/orgs/" + orgId + "/cards/" + card, seller)
                .andExpect(jsonPath("$.photos.length()").value(3))
                .andExpect(jsonPath("$.photos[?(@.current == true)].side", org.hamcrest.Matchers.containsInAnyOrder("FRONT", "BACK")))
                .andExpect(jsonPath("$.photos[0].url").isNotEmpty())
                .andExpect(jsonPath("$.photosFrozen").isEmpty());
        priceLadder(identify(card, "Umbreon " + UUID.randomUUID().toString().substring(0, 4), "2015-05-06", "NORMAL", "Rare"), 300);
        assess(card);
        // The reviewer sees the current front and back only, front first.
        api.get("/api/admin/orgs/" + orgId + "/cards/" + card + "/condition", api.admin())
                .andExpect(jsonPath("$.photos[*].side", org.hamcrest.Matchers.contains("FRONT", "BACK")));

        // A photo taken after the offer means buyers would see photos it wasn't priced on.
        String stale = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.uploadPhoto(orgId, card, "DETAIL", TestImages.goodRetake(1), seller).andExpect(jsonPath("$.status").value("PASSED"));
        api.postJson("/api/orgs/" + orgId + "/ship-address", ADDRESS.formatted("Sam"), seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + stale + "\"}", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("photos changed")));
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");

        // Buyers see the current front, back and close-up, labelled and in that order: never the superseded front.
        JwtRequestPostProcessor buyer = buyer("Photo Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Photo Buyer");
        api.get("/api/orgs/" + buyerOrg + "/market", buyer)
                .andExpect(jsonPath("$[?(@.listingId == '" + listingId + "')].photos[*].side",
                        org.hamcrest.Matchers.contains("FRONT", "BACK", "DETAIL")));

        // Posted: the photos stay as buyers see them.
        String upload = "/api/orgs/" + orgId + "/cards/" + card + "/photos";
        String slot = "{\"side\":\"FRONT\",\"contentType\":\"image/png\",\"sizeBytes\":100}";
        api.postJson(upload, slot, seller).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("posted to buyers")));
        api.get("/api/orgs/" + orgId + "/cards/" + card, seller).andExpect(jsonPath("$.photosFrozen", containsString("posted")));

        // Sold: for good.
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.deals[0].photos[*].side", org.hamcrest.Matchers.contains("FRONT", "BACK", "DETAIL")));
        api.postJson(upload, slot, seller).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("sold")));
    }

    @Test
    void aWithdrawnCardCanBeRetakenAgain() throws Exception {
        String card = card("Withdrawn Espeon");
        api.uploadPhoto(orgId, card, "FRONT", TestImages.goodFront(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, card, "BACK", TestImages.goodBack(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        priceLadder(identify(card, "Espeon " + UUID.randomUUID().toString().substring(0, 4), "2015-05-06", "NORMAL", "Rare"), 300);
        assess(card);
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", ADDRESS.formatted("Sam"), seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");
        api.postJson("/api/orgs/" + orgId + "/listings/" + listingId + "/withdraw", "", seller).andExpect(status().isOk());

        api.uploadPhoto(orgId, card, "DETAIL", TestImages.goodRetake(2), seller).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));
    }

    @Test
    void theAdminPreviewsTheOfferOrSeesWhyThereIsNone() throws Exception {
        String priced = pricedCard("Flareon", 300);
        String preview = api.get("/api/admin/cards/" + priced + "/offer-preview", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.offered").value(true))
                .andExpect(jsonPath("$.refusal").isEmpty())
                .andExpect(jsonPath("$.why[?(@.subject == 'confidence_base')]").isNotEmpty())
                .andExpect(jsonPath("$.why[?(@.subject == 'parameters')]").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        // Nothing is stored, and the preview is the price the seller then gets.
        api.get(sale(priced), seller).andExpect(jsonPath("$.offer").isEmpty());
        String offer = api.postJson(offers(priced), "", seller).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(offer, "$.price")).doubleValue())
                .isEqualTo(((Number) JsonPath.read(preview, "$.price")).doubleValue());
        assertThat(JsonPath.<String>read(offer, "$.confidence")).isEqualTo(JsonPath.read(preview, "$.confidence"));

        String unknown = card("Unknown");
        api.get("/api/admin/cards/" + unknown + "/offer-preview", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.offered").value(false))
                .andExpect(jsonPath("$.refusal", containsString("identified")))
                .andExpect(jsonPath("$.confidence").isEmpty());
        api.get("/api/admin/cards/" + UUID.randomUUID() + "/offer-preview", api.admin()).andExpect(status().isNotFound());
    }

    @Test
    void realisticHandEnteredCompsGetAnOffer() throws Exception {
        // Audit finding 1: three fresh sales each at PSA 8, 9 and 10, as comps.csv has for Pikachu VMAX. Too few 30-day changes to
        // measure sigma, and the stress reaches the derived 5 to 7. Both only bear on the FLV, so the offer
        // is priced at the comps' own confidence, C (Zach, 2026-10-21).
        String card = card("Realistic Pikachu");
        String catalog = identify(card, "Pikachu VMAX " + UUID.randomUUID().toString().substring(0, 4), "2020-11-13", "HOLO", "Rare Rainbow");
        Map<Integer, Integer> top = Map.of(10, 280, 9, 200, 8, 120);
        for (Map.Entry<Integer, Integer> grade : top.entrySet()) {
            for (int daysAgo : new int[] {1, 2, 3}) {
                api.postJson("/api/admin/catalog-cards/" + catalog + "/comps", "{\"gradingCompany\":\"PSA\",\"grade\":" + grade.getKey()
                        + ",\"price\":" + (grade.getValue() + daysAgo) + ",\"soldOn\":\"" + TODAY.minusDays(daysAgo)
                        + "\",\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\"}", api.admin()).andExpect(status().isCreated());
            }
        }
        api.postJson("/api/admin/catalog-cards/" + catalog + "/prices/refresh", "", api.admin()).andExpect(status().isOk());
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + card + "/assessments",
                "{\"distribution\":{\"8\":0.3,\"9\":0.5,\"10\":0.2},\"confidence\":\"B\",\"reason\":\"reviewed\"}", api.admin())
                .andExpect(status().isCreated());

        api.get("/api/admin/cards/" + card + "/offer-preview", api.admin())
                .andExpect(jsonPath("$.offered").value(true))
                .andExpect(jsonPath("$.confidence").value("C"))
                .andExpect(jsonPath("$.why[?(@.subject == 'offer_confidence')].detail", org.hamcrest.Matchers.contains(
                        containsString("FLV-only penalties"))))
                .andExpect(jsonPath("$.why[?(@.subject == 'sigma')]").isEmpty());
        api.postJson(offers(card), "", seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.confidence").value("C"))
                .andExpect(jsonPath("$.buyerMargin").value(0.17));
    }

    @Test
    void theCardStatusSaysWhatHappensNext() throws Exception {
        String card = card("Status Jolteon");
        String status = "/api/orgs/" + orgId + "/cards/" + card + "/status";
        api.get(status, seller).andExpect(jsonPath("$.stage").value("PHOTOS")).andExpect(jsonPath("$.canOffer").value(false));
        api.uploadPhoto(orgId, card, "FRONT", TestImages.goodFront(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, card, "BACK", TestImages.goodBack(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        api.get(status, seller).andExpect(jsonPath("$.stage").value("IDENTIFYING"))
                .andExpect(jsonPath("$.nextStep", containsString("by hand"))).andExpect(jsonPath("$.identity").isEmpty());
        String name = "Jolteon " + UUID.randomUUID().toString().substring(0, 4);
        String catalog = identify(card, name, "2015-05-06", "HOLO", "Rare");
        api.get(status, seller).andExpect(jsonPath("$.stage").value("ASSESSING"))
                .andExpect(jsonPath("$.identity", containsString(name + " · Set 2015-05-06")))
                .andExpect(jsonPath("$.identity", containsString("holo")));
        assess(card);
        api.get(status, seller).andExpect(jsonPath("$.stage").value("PRICING"));
        priceLadder(catalog, 300);
        api.get(status, seller).andExpect(jsonPath("$.stage").value("READY")).andExpect(jsonPath("$.canOffer").value(true));
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.get(status, seller).andExpect(jsonPath("$.stage").value("OFFERED"));
        api.postJson("/api/orgs/" + orgId + "/ship-address", ADDRESS.formatted("Sam"), seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        api.get(status, seller).andExpect(jsonPath("$.stage").value("LISTED"));

        String lot = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections/" + collectionId + "/cards",
                "{\"label\":\"Bulk\",\"lot\":true,\"quantity\":40}", seller), "$.id");
        String all = api.get("/api/orgs/" + orgId + "/collections/" + collectionId + "/status", seller).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(all, "$[?(@.cardId == '" + card + "')].stage")).containsExactly("LISTED");
        assertThat(JsonPath.<List<String>>read(all, "$[?(@.cardId == '" + lot + "')].stage")).containsExactly("LOT");
    }

    @Test
    void aListingIsOpenUntilClosedOrExpired() {
        Instant now = Instant.now();
        SaleListing open = new SaleListing(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(60));
        SaleListing old = new SaleListing(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, now.minusSeconds(1));

        assertThat(ListingService.status(open, List.of(), now)).isEqualTo(ListingService.Status.OPEN);
        assertThat(ListingService.status(old, List.of(), now)).isEqualTo(ListingService.Status.EXPIRED);
        ListingEvent taken = ListingEvent.accepted(old.getId(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(ListingService.status(old, List.of(taken), now)).isEqualTo(ListingService.Status.ACCEPTED);
        assertThat(ListingService.status(old, List.of(taken, ListingEvent.cancelled(old.getId(), UUID.randomUUID(), "x")), now))
                .isEqualTo(ListingService.Status.CANCELLED);
        assertThat(ListingService.status(open, List.of(ListingEvent.voided(open.getId(), UUID.randomUUID(), "x")), now))
                .isEqualTo(ListingService.Status.VOIDED);
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

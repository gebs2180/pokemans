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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * winner's label is bought at once, the buyer records the arrival and the payment or a return, and the
 * seller says what they got. Withdrawn and ineligible cards get nowhere, and the track record counts it.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class OffersFlowTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final String NEAR_MINT = "{\"distribution\":{\"9\":0.5,\"10\":0.5},\"confidence\":\"A\",\"reason\":\"reviewed\"}";
    private static final String ADDRESS = "{\"name\":\"%s\",\"street1\":\"1 Main St\",\"city\":\"Springfield\",\"state\":\"IL\",\"zip\":\"62701\"}";
    private static final String SELLER_ADDRESS = "{\"name\":\"Sam\",\"street1\":\"1 Main St\",\"city\":\"Springfield\",\"state\":\"IL\",\"zip\":\"62701\","
            + "\"paymentMethod\":\"VENMO\",\"paymentHandle\":\" @sam-sells \"}";

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
        shipper.gate = null;
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
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS.replace("62701", "627"), seller)
                .andExpect(status().isBadRequest());
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
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
                // The buyer never learns where the seller is: no printable label (it shows the seller's address), no city.
                .andExpect(jsonPath("$.deals[0].label.labelUrl").isEmpty())
                .andExpect(jsonPath("$.deals[0].seller.city").doesNotExist())
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
        String paid = "/api/orgs/" + firstOrg + "/deals/" + listingId + "/paid";
        api.postJson("/api/orgs/" + secondOrg + "/deals/" + listingId + "/received", "{}", second).andExpect(status().isNotFound());
        api.postJson(paid, "{\"pricePaid\":" + price + "}", first).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("arrived first")));
        api.postJson(received, "{}", first).andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].outcome.type").value("RECEIVED"))
                .andExpect(jsonPath("$[0].progress.stage").value("ARRIVED"))
                .andExpect(jsonPath("$[0].progress.payBy").isNotEmpty());
        api.postJson("/api/orgs/" + firstOrg + "/deals/" + listingId + "/returned", "{\"reason\":\"NOT_GENUINE\",\"note\":\"x\"}", first)
                .andExpect(status().isConflict());
        api.postJson(paid, "{}", first).andExpect(status().isBadRequest());
        api.postJson(paid, "{\"pricePaid\":" + price + "}", first).andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].progress.stage").value("PAID"));
        api.postJson(paid, "{\"pricePaid\":" + price + "}", first).andExpect(status().isConflict());
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.progress.pricePaid").value(price));

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
                // What the provider said stays with us (it can quote the seller's address): the buyer gets a plain reason.
                .andExpect(jsonPath("$.labelPending", containsString("couldn't buy the shipping label")))
                .andExpect(jsonPath("$.labelPending", org.hamcrest.Matchers.not(containsString("carrier is down"))))
                .andExpect(jsonPath("$.deals[0].label").isEmpty());
        String retry = "/api/orgs/" + buyerOrg + "/deals/" + listingId + "/label";
        api.postJson(retry, "", buyer).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.not(containsString("carrier is down"))));

        shipper.failing = false;
        api.postJson(retry, "", buyer).andExpect(status().isOk()).andExpect(jsonPath("$[0].label.trackingCode").isNotEmpty());
        api.postJson(retry, "", buyer).andExpect(status().isOk()); // already bought: the same label, not a second one
        assertThat(shipper.bought.stream().filter(b -> b.reference().equals(listingId))).hasSize(1);
    }

    @Test
    void twoLabelRequestsAtOnceBuyOneLabel() throws Exception {
        // Security review 2026-10-26: a retry while the first purchase was still with the provider bought a second label
        // on our account, which the unique index then refused to record, so it was neither billed nor refundable here.
        String listingId = postedCard("Double Click Dragonite", 200);
        JwtRequestPostProcessor buyer = buyer("Double Clicker");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Double Clicker");
        shipper.failing = true;
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk());
        shipper.failing = false;

        String retry = "/api/orgs/" + buyerOrg + "/deals/" + listingId + "/label";
        shipper.gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> calls = List.of(
                    pool.submit(() -> api.postJson(retry, "", buyer).andReturn().getResponse().getStatus()),
                    pool.submit(() -> api.postJson(retry, "", buyer).andReturn().getResponse().getStatus()));
            long deadline = System.currentTimeMillis() + 10_000;
            while (shipper.inFlight.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            Thread.sleep(500); // time for the second request to reach the provider too, if nothing stops it
            shipper.gate.countDown();
            for (Future<Integer> call : calls) {
                assertThat(call.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(shipper.bought.stream().filter(b -> b.reference().equals(listingId))).hasSize(1);
    }

    @Test
    void buyersNeverGetThePhotosMetadata() throws Exception {
        // Security review 2026-10-26: a close-up has no card crop, so buyers were linked to the file as uploaded, and a
        // phone photo's EXIF carries where it was taken: the seller's home, which buyers must never learn.
        String card = card("Geotagged Lugia");
        api.uploadPhoto(orgId, card, "FRONT", TestImages.goodFront(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, card, "BACK", TestImages.goodBack(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, card, "DETAIL", geotaggedJpeg(TestImages.goodRetake(3)), "image/jpeg", seller)
                .andExpect(jsonPath("$.status").value("PASSED"));
        priceLadder(identify(card, "Lugia " + UUID.randomUUID().toString().substring(0, 4), "2015-05-06", "NORMAL", "Rare"), 300);
        assess(card);
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");

        JwtRequestPostProcessor buyer = buyer("Curious Buyer");
        String buyerOrg = lastBuyerOrg;
        String market = api.get("/api/orgs/" + buyerOrg + "/market", buyer).andReturn().getResponse().getContentAsString();
        List<String> urls = JsonPath.read(market, "$[?(@.listingId == '" + listingId + "')].photos[*].url");
        assertThat(urls).hasSize(3);
        for (String url : urls) {
            byte[] served = api.download(url);
            assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(served))).as("an image").isNotNull();
            assertThat(new String(served, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Exif").doesNotContain(GPS_SECRET);
        }

        // The full-size photos are the upload's own image data, not a crop or a re-encode (UX review finding 8), still without
        // the metadata: the close-up served is the uploaded file with its EXIF segment cut out and nothing else changed.
        List<String> full = JsonPath.read(market, "$[?(@.listingId == '" + listingId + "')].photos[*].fullUrl");
        assertThat(full).hasSize(3);
        for (String url : full) {
            byte[] served = api.download(url);
            assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(served))).as("an image").isNotNull();
            assertThat(new String(served, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(GPS_SECRET);
        }
        List<String> detail = JsonPath.read(market, "$[?(@.listingId == '" + listingId + "')].photos[?(@.side == 'DETAIL')].fullUrl");
        byte[] served = api.download(detail.get(0));
        byte[] uploaded = geotaggedJpeg(TestImages.goodRetake(3));
        assertThat(java.util.Arrays.copyOfRange(uploaded, uploaded.length - (served.length - 2), uploaded.length))
                .isEqualTo(java.util.Arrays.copyOfRange(served, 2, served.length));
    }

    @Test
    void aBuyerSeesPostageAndInsuranceBeforeAcceptingAndNoLabelIsBought() throws Exception {
        String listingId = postedCard("Espeon", 300);
        JwtRequestPostProcessor buyer = buyer("Thrifty Buyer");
        String buyerOrg = lastBuyerOrg;
        String postage = "/api/orgs/" + buyerOrg + "/market/" + listingId + "/postage";
        String market = api.get("/api/orgs/" + buyerOrg + "/market", buyer).andReturn().getResponse().getContentAsString();
        double price = JsonPath.<List<Number>>read(market, "$[?(@.listingId == '" + listingId + "')].offer.price").get(0).doubleValue();
        double insurance = Math.max(1.0, Math.ceil(price) / 100.0);
        int boughtBefore = shipper.bought.size();

        // Without the buyer's address there is nothing to quote: the configured estimate.
        api.get(postage, buyer).andExpect(status().isOk()).andExpect(jsonPath("$.quoted").value(false))
                .andExpect(jsonPath("$.postage").value(6.00));
        address(buyerOrg, buyer, "Thrifty Buyer");
        String quote = api.get(postage, buyer).andExpect(status().isOk()).andExpect(jsonPath("$.quoted").value(true))
                .andExpect(jsonPath("$.carrier").value("USPS")).andExpect(jsonPath("$.postage").value(5.10))
                .andReturn().getResponse().getContentAsString();
        double insured = ((Number) JsonPath.read(quote, "$.insurance")).doubleValue();
        assertThat(insured).isCloseTo(insurance, Offset.offset(0.011));
        assertThat(((Number) JsonPath.read(quote, "$.total")).doubleValue()).isCloseTo(5.10 + insured, Offset.offset(0.001));
        // A quote that fails falls back to the estimate rather than keeping the buyer from the confirm step.
        shipper.failing = true;
        api.get(postage, buyer).andExpect(status().isOk()).andExpect(jsonPath("$.quoted").value(false));
        shipper.failing = false;
        assertThat(shipper.bought).as("an estimate never buys a label").hasSize(boughtBefore);

        api.get("/api/orgs/" + orgId + "/market/" + listingId + "/postage", seller).andExpect(status().isNotFound());
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk());
        api.get(postage, buyer).andExpect(status().isConflict());
    }

    @Test
    void theOfferShowsWhatEachGradeRecentlySoldForAndWhyItIsLessSure() throws Exception {
        String card = pricedCard("Sylveon", 300);

        String offer = api.postJson(offers(card), "", seller).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // From the comps stored with the offer: six PSA sales a grade over 90 days, 300 at a 10 and 180 at a 9.
        assertThat(JsonPath.<List<String>>read(offer, "$.sold[*].grade")).containsExactlyInAnyOrder("7", "8", "9", "10");
        assertThat(JsonPath.<List<String>>read(offer, "$.sold[*].gradingCompany")).containsOnly("PSA");
        assertThat(JsonPath.<List<Integer>>read(offer, "$.sold[*].sales")).containsOnly(6);
        assertThat(JsonPath.<List<Integer>>read(offer, "$.sold[*].windowDays")).containsOnly(90);
        assertThat(JsonPath.<List<Number>>read(offer, "$.sold[?(@.grade == '10')].price").get(0).doubleValue()).isEqualTo(300.0);
        assertThat(JsonPath.<List<Number>>read(offer, "$.sold[?(@.grade == '9')].price").get(0).doubleValue()).isEqualTo(180.0);
        // Confidence B from comps: the seller is told it's the number of sales, not just the letter.
        assertThat((String) JsonPath.read(offer, "$.confidence")).isEqualTo("B");
        assertThat(JsonPath.<List<String>>read(offer, "$.lessSureBecause")).containsExactly("FEW_SALES");
        // The seller's sale view shows the same, from the same stored offer.
        String sale = api.get(sale(card), seller).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(sale, "$.offer.sold")).hasSize(4);
    }

    private static final String GPS_SECRET = "GPS 39.7817N 89.6501W";

    /** The photo as a JPEG with an EXIF segment after the start marker, as a phone camera writes one. */
    private static byte[] geotaggedJpeg(byte[] png) throws Exception {
        java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(decoded.getWidth(), decoded.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(decoded, 0, 0, null);
        java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(rgb, "jpg", jpeg);
        byte[] plain = jpeg.toByteArray();
        // "Exif  ", then a big-endian TIFF header with an empty first IFD, then the payload a GPS IFD would hold.
        java.io.ByteArrayOutputStream exif = new java.io.ByteArrayOutputStream();
        exif.write("Exif  ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        exif.write(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0});
        exif.write(GPS_SECRET.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        int length = exif.size() + 2;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(plain, 0, 2); // SOI
        out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        exif.writeTo(out);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
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
    void theSellerFollowsTheDealFromSoldToPaid() throws Exception {
        String card = pricedCard("Stage Umbreon", 300);
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");

        // A seller says how they get paid along with where they ship from, and can't post without it.
        String shipAddress = "/api/orgs/" + orgId + "/ship-address";
        api.postJson(shipAddress, ADDRESS.formatted("Sam"), seller).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("how you'd like to be paid")));
        // An address from before handles existed.
        jdbc.update("insert into ship_address (id, org_id, name, street1, city, state, zip, country, recorded_by, created_at) "
                + "select gen_random_uuid(), org_id, 'Sam', '1 Main St', 'Springfield', 'IL', '62701', 'US', created_by, now() "
                + "from card_offer where id = ?::uuid", UUID.fromString(offerId));
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("how you'd like to be paid")));
        api.postJson(shipAddress, SELLER_ADDRESS, seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentMethod").value("VENMO"))
                .andExpect(jsonPath("$.paymentHandle").value("@sam-sells"));
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");

        // Nobody learns the handle before they've bought, and a buyer gives none.
        JwtRequestPostProcessor buyer = buyer("Stage Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Stage Buyer");
        api.get("/api/orgs/" + buyerOrg + "/ship-address", buyer).andExpect(jsonPath("$.paymentHandle").isEmpty());
        assertThat(api.get("/api/orgs/" + buyerOrg + "/market", buyer).andReturn().getResponse().getContentAsString())
                .doesNotContain("sam-sells").doesNotContain("VENMO");
        String deals = "/api/orgs/" + buyerOrg + "/deals";
        String mine = "$[?(@.listingId == '" + listingId + "')]";
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk());
        api.get(deals, buyer).andExpect(jsonPath(mine + ".payTo.method").value("VENMO"))
                .andExpect(jsonPath(mine + ".payTo.handle").value("@sam-sells"));
        assertThat(shipper.bought.stream().filter(b -> b.reference().equals(listingId)).findFirst().orElseThrow().from().toString())
                .doesNotContain("sam-sells"); // the shipping provider never gets it

        // Sold: the label is the next step, and there's nothing to say about money yet.
        String status = "/api/orgs/" + orgId + "/cards/" + card + "/status";
        String listing = "/api/orgs/" + orgId + "/listings/" + listingId;
        api.get(status, seller).andExpect(jsonPath("$.stage").value("SOLD"));
        api.postJson(listing + "/payment-confirmed", "", seller).andExpect(status().isConflict());
        api.postJson(listing + "/dispute", "{}", seller).andExpect(status().isConflict());

        // Shipped: the label is used, so it's gone; tracking stays.
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.label.labelUrl").isNotEmpty());
        api.postJson(listing + "/shipped", "", seller).andExpect(status().isOk());
        api.postJson(listing + "/shipped", "", seller).andExpect(status().isConflict());
        api.get(status, seller).andExpect(jsonPath("$.stage").value("SHIPPED"));
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.progress.stage").value("SHIPPED"))
                .andExpect(jsonPath("$.listing.label.labelUrl").isEmpty())
                .andExpect(jsonPath("$.listing.label.trackingCode").isNotEmpty());

        // Arrived, not paid: the buyer has 7 days, and both sides see the date.
        api.postJson(deals + "/" + listingId + "/received", "{}", buyer).andExpect(status().isCreated());
        api.get(status, seller).andExpect(jsonPath("$.stage").value("ARRIVED"));
        String payBy = TestApi.read(api.get(sale(card), seller), "$.listing.progress.payBy");
        String arrivedAt = TestApi.read(api.get(sale(card), seller), "$.listing.progress.arrivedAt");
        assertThat(java.time.Duration.between(Instant.parse(arrivedAt), Instant.parse(payBy))).isEqualTo(java.time.Duration.ofDays(7));
        api.get(deals, buyer).andExpect(jsonPath(mine + ".progress.payBy").value(payBy));

        // The buyer says they paid; only the seller's word closes it.
        double price = ((Number) JsonPath.<List<Number>>read(api.get(deals, buyer).andReturn().getResponse().getContentAsString(),
                mine + ".offer.price").getFirst()).doubleValue();
        api.postJson(deals + "/" + listingId + "/paid", "{\"pricePaid\":" + price + "}", buyer).andExpect(status().isCreated());
        api.get(status, seller).andExpect(jsonPath("$.stage").value("PAID"))
                .andExpect(jsonPath("$.nextStep", containsString("whether you got it")));
        api.postJson(listing + "/payment-confirmed", "", seller).andExpect(status().isOk());
        api.postJson(listing + "/payment-confirmed", "", seller).andExpect(status().isConflict());
        api.postJson(listing + "/dispute", "{}", seller).andExpect(status().isConflict());
        api.get(status, seller).andExpect(jsonPath("$.nextStep", containsString("Nothing more to do")));
        api.get(deals, buyer).andExpect(jsonPath(mine + ".progress.confirmedAt").isNotEmpty());

        // Someone else's listing is nobody's to speak for.
        JwtRequestPostProcessor other = buyer("Nosy Buyer");
        api.postJson("/api/orgs/" + lastBuyerOrg + "/listings/" + listingId + "/shipped", "", other).andExpect(status().isNotFound());

        // Still only rows added: nothing to update.
        assertThat(jdbc.queryForList("select type from listing_event where listing_id = ?::uuid order by created_at", String.class,
                UUID.fromString(listingId))).containsExactly("ACCEPTED", "SHIPPED", "RECEIVED", "PAID", "PAYMENT_CONFIRMED");
    }

    @Test
    void aSellerWhoWasntPaidReachesTheAdmin() throws Exception {
        String card = pricedCard("Unpaid Mew", 300);
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");
        JwtRequestPostProcessor buyer = buyer("Slow Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Slow Buyer");
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk());
        api.postJson("/api/orgs/" + orgId + "/listings/" + listingId + "/shipped", "", seller).andExpect(status().isOk());
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + listingId + "/received", "{}", buyer).andExpect(status().isCreated());

        String dispute = "/api/orgs/" + orgId + "/listings/" + listingId + "/dispute";
        api.postJson(dispute, "{\"note\":\"nothing in my Venmo\"}", seller).andExpect(status().isOk());
        api.postJson(dispute, "{}", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already have this")));

        // It reaches the admin as something to do, with who to chase, and only the admin.
        api.get("/api/admin/deals/attention", buyer).andExpect(status().isForbidden());
        String mine = "$[?(@.listingId == '" + listingId + "')]";
        String attention = api.get("/api/admin/deals/attention", api.admin()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(attention, mine + ".kind")).containsExactly("NOT_PAID");
        assertThat(JsonPath.<List<String>>read(attention, mine + ".note")).containsExactly("nothing in my Venmo");
        assertThat(JsonPath.<List<String>>read(attention, mine + ".buyer.orgName")).containsExactly("Slow Buyer");
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.progress.disputeNote").value("nothing in my Venmo"));
        // The buyer sees that it was raised, not the seller's words to us.
        String deals = "/api/orgs/" + buyerOrg + "/deals";
        api.get(deals, buyer).andExpect(jsonPath("$[0].progress.disputedAt").isNotEmpty()).andExpect(jsonPath("$[0].progress.disputeNote").isEmpty());

        // Closing it needs a reason, both sides see it, and it can be raised again.
        String resolve = "/api/admin/deals/" + listingId + "/resolve";
        api.postJson(resolve, "{}", api.admin()).andExpect(status().isBadRequest());
        api.postJson(resolve, "{\"reason\":\"x\"}", buyer).andExpect(status().isForbidden());
        api.postJson(resolve, "{\"reason\":\"Spoke to both: paying Friday\"}", api.admin()).andExpect(status().isOk());
        api.postJson(resolve, "{\"reason\":\"again\"}", api.admin()).andExpect(status().isConflict());
        assertThat(api.get("/api/admin/deals/attention", api.admin()).andReturn().getResponse().getContentAsString()).doesNotContain(listingId);
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.progress.review.reason").value("Spoke to both: paying Friday"))
                .andExpect(jsonPath("$.listing.progress.disputedAt").isEmpty());
        api.get(deals, buyer).andExpect(jsonPath("$[0].progress.review.reason").value("Spoke to both: paying Friday"));
        api.postJson(dispute, "{\"note\":\"Friday came and went\"}", seller).andExpect(status().isOk());

        // The seller's own "I got paid" closes it too.
        api.postJson("/api/orgs/" + orgId + "/listings/" + listingId + "/payment-confirmed", "", seller).andExpect(status().isOk());
        assertThat(api.get("/api/admin/deals/attention", api.admin()).andReturn().getResponse().getContentAsString()).doesNotContain(listingId);
        api.get("/api/admin/deals/attention", api.admin()).andExpect(status().isOk());
    }

    @Test
    void aReturnedCardGetsANewOfferOnceItsBack() throws Exception {
        String card = pricedCard("Returned Lapras", 300);
        api.uploadPhoto(orgId, card, "FRONT", TestImages.goodFront(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, card, "BACK", TestImages.goodBack(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        assess(card); // again: an assessment made before the photos doesn't count for them
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");
        JwtRequestPostProcessor buyer = buyer("Returning Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Returning Buyer");
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk());

        String listing = "/api/orgs/" + orgId + "/listings/" + listingId;
        api.postJson(listing + "/card-back", "", seller).andExpect(status().isConflict());
        String returned = "/api/orgs/" + buyerOrg + "/deals/" + listingId + "/returned";
        api.postJson(returned, "{\"reason\":\"DOES_NOT_MATCH_PHOTOS\",\"note\":\"crease not in the photos\"}", buyer).andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].payTo").isEmpty()) // nothing is owed any more
                .andExpect(jsonPath("$[0].progress.stage").value("RETURNED"));
        String status = "/api/orgs/" + orgId + "/cards/" + card + "/status";
        api.get(status, seller).andExpect(jsonPath("$.stage").value("RETURNED"));
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.outcome.note").value("crease not in the photos"))
                .andExpect(jsonPath("$.listing.label.labelUrl").isEmpty());
        // Nothing can be paid on it, every return reaches the admin, and a seller who disagrees has to say why.
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + listingId + "/paid", "{\"pricePaid\":1,\"note\":\"x\"}", buyer)
                .andExpect(status().isConflict());
        api.postJson(listing + "/payment-confirmed", "", seller).andExpect(status().isConflict());
        String attention = api.get("/api/admin/deals/attention", api.admin()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(attention, "$[?(@.listingId == '" + listingId + "')].kind")).containsExactly("RETURNED");
        api.postJson(listing + "/dispute", "{}", seller).andExpect(status().isBadRequest());
        api.postJson(listing + "/dispute", "{\"note\":\"that crease is in the back photo\"}", seller).andExpect(status().isOk());
        attention = api.get("/api/admin/deals/attention", api.admin()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(attention, "$[?(@.listingId == '" + listingId + "')].kind")).containsExactly("RETURN_DISPUTED");

        // Until it's back it stays sold.
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isConflict());
        String upload = "/api/orgs/" + orgId + "/cards/" + card + "/photos";
        String slot = "{\"side\":\"FRONT\",\"contentType\":\"image/png\",\"sizeBytes\":100}";
        api.postJson(upload, slot, seller).andExpect(status().isConflict());

        // Back: free for a new offer, not the one it sold at, and its photos can change again.
        api.postJson(listing + "/card-back", "", seller).andExpect(status().isOk());
        api.postJson(listing + "/card-back", "", seller).andExpect(status().isConflict());
        api.get(sale(card), seller).andExpect(jsonPath("$.listing.status").value("BACK"))
                .andExpect(jsonPath("$.listing.progress.stage").value("BACK"));
        api.get(status, seller).andExpect(jsonPath("$.stage").value("READY")).andExpect(jsonPath("$.canOffer").value(true));
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already been sold")));
        api.postJson(upload, slot, seller).andExpect(status().is2xxSuccessful());
        String fresh = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson(listings(card), "{\"offerId\":\"" + fresh + "\"}", seller).andExpect(status().isCreated())
                .andExpect(jsonPath("$.listing.status").value("OPEN"));

        // The deal stays a returned deal in the track record.
        Map<String, Object> row = jdbc.queryForMap("select status, outcome from v_offer_outcomes where listing_id = ?::uuid", UUID.fromString(listingId));
        assertThat(row).containsEntry("status", "ACCEPTED").containsEntry("outcome", "RETURNED");
    }

    @Test
    void aNotGenuineReturnNeedsANoteAndWaitsForOurReview() throws Exception {
        String card = pricedCard("Doubtful Ditto", 300);
        api.uploadPhoto(orgId, card, "FRONT", TestImages.goodFront(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, card, "BACK", TestImages.goodBack(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        assess(card); // again: an assessment made before the photos doesn't count for them
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(card), seller), "$.listing.id");
        JwtRequestPostProcessor buyer = buyer("Suspicious Buyer");
        String buyerOrg = lastBuyerOrg;
        address(buyerOrg, buyer, "Suspicious Buyer");
        api.postJson(accept(buyerOrg, listingId), "", buyer).andExpect(status().isOk());

        String returned = "/api/orgs/" + buyerOrg + "/deals/" + listingId + "/returned";
        api.postJson(returned, "{\"reason\":\"NOT_GENUINE\"}", buyer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("isn't genuine")));
        api.postJson(returned, "{\"reason\":\"NOT_GENUINE\",\"note\":\" \"}", buyer).andExpect(status().isBadRequest());
        api.postJson(returned, "{\"reason\":\"NOT_GENUINE\",\"note\":\"wrong font on the back\"}", buyer).andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].progress.held").value(true));

        // Back with the seller, but no new offer until the admin has looked: a fake must not go straight out to the next buyer.
        String listing = "/api/orgs/" + orgId + "/listings/" + listingId;
        String status = "/api/orgs/" + orgId + "/cards/" + card + "/status";
        api.postJson(listing + "/card-back", "", seller).andExpect(status().isOk());
        api.get(status, seller).andExpect(jsonPath("$.stage").value("RETURNED")).andExpect(jsonPath("$.canOffer").value(false))
                .andExpect(jsonPath("$.nextStep", containsString("review")));
        api.postJson(offers(card), "", seller).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("review")));
        api.postJson("/api/admin/deals/" + listingId + "/resolve", "{\"reason\":\"Looks genuine from the photos; the font matches\"}", api.admin())
                .andExpect(status().isOk());
        api.get(status, seller).andExpect(jsonPath("$.canOffer").value(true));
        api.postJson(offers(card), "", seller).andExpect(status().isCreated());
    }

    @Test
    void aDealFromBeforeTheSplitCountsAsArrivedAndPaid() {
        ListingEvent taken = ListingEvent.accepted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ListingEvent old = legacyReceived(taken.getListingId());
        ListingService.Progress progress = ListingService.progress(List.of(taken, old));
        assertThat(progress.stage()).isEqualTo(ListingService.DealStage.PAID);
        assertThat(progress.pricePaid()).isEqualByComparingTo("99.50");
        assertThat(progress.arrivedAt()).isEqualTo(progress.paidAt());
        assertThat(ListingService.progress(List.of(taken, ListingEvent.cancelled(taken.getListingId(), UUID.randomUUID(), "x")))).isNull();
    }

    /** A RECEIVED row as it was written before arrival and payment were split. */
    private static ListingEvent legacyReceived(UUID listingId) {
        ListingEvent received = ListingEvent.received(listingId, UUID.randomUUID(), UUID.randomUUID(), null);
        org.springframework.test.util.ReflectionTestUtils.setField(received, "pricePaid", new java.math.BigDecimal("99.50"));
        return received;
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

        // Above the maximum firm price, no offer: EasyPost's $5,000 insurance limit (pokemans.offers.max-price;
        // the $500 beta maximum was lifted on 2026-10-26).
        String grail = pricedCard("Grail", 30000);
        api.postJson(offers(grail), "", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("go up to $5,000")));
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
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
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
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
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
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + listingId + "/received", "{}", buyer)
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
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + listingId + "/received", "{}", buyer).andExpect(status().isCreated());
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
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + kept + "/received", "{}", buyer).andExpect(status().isCreated());
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + kept + "/paid", "{\"pricePaid\":" + prices.getFirst() + "}", buyer)
                .andExpect(status().isCreated());
        String sentBack = postedCard("Vaporeon", 300);
        api.postJson(accept(buyerOrg, sentBack), "", buyer).andExpect(status().isOk());
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + sentBack + "/returned", "{\"reason\":\"NOT_GENUINE\",\"note\":\"fails the light test\"}", buyer)
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
        assertThat(row.get("price_paid")).isNotNull();
        assertThat(row.get("paid_at")).isNotNull();
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
        api.postJson(received, "{}", buyer).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("no label")));
        api.postJson("/api/orgs/" + buyerOrg + "/deals/" + listingId + "/returned", "{\"reason\":\"NOT_GENUINE\",\"note\":\"x\"}", buyer)
                .andExpect(status().isConflict());
        api.postJson("/api/orgs/" + orgId + "/listings/" + listingId + "/shipped", "", seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("until its label shows")));

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
        api.postJson(received, "{}", buyer).andExpect(status().isCreated());
        String paid = "/api/orgs/" + buyerOrg + "/deals/" + listingId + "/paid";
        api.postJson(paid, "{\"pricePaid\":" + (price - 5) + "}", buyer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("why the amount paid differs")));
        api.postJson(paid, "{\"pricePaid\":" + (price - 5) + ",\"note\":\"seller agreed a discount for a soft corner\"}", buyer)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].progress.paidNote").value("seller agreed a discount for a soft corner"));
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
        api.postJson(byHand, "{}", api.admin()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("how the card was shipped")));
        api.postJson(byHand, "{\"note\":\"shipped with the seller's own stamps\"}", buyer).andExpect(status().isForbidden());
        api.postJson("/api/admin/deals/" + listingId + "/received?buyerOrgId=" + UUID.randomUUID(),
                "{\"note\":\"x\"}", api.admin()).andExpect(status().isNotFound());
        api.postJson(byHand, "{\"note\":\"shipped with the seller's own stamps\"}", api.admin()).andExpect(status().isCreated());
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
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
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
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
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
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
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
    @Test
    void homeListsWhatIsWaitingOnTheSeller() throws Exception {
        String needs = "/api/orgs/" + orgId + "/needs-you";
        api.get(needs, seller).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // An offer the seller hasn't acted on, with when it ends.
        String card = pricedCard("Waiting", 400);
        api.postJson(offers(card), "", seller).andExpect(status().isCreated());
        api.get(needs, seller).andExpect(jsonPath("$[0].cardId").value(card)).andExpect(jsonPath("$[0].label").value("Waiting"))
                .andExpect(jsonPath("$[0].need").value("OFFER_READY")).andExpect(jsonPath("$[0].due").isNotEmpty());

        // Posted: it's waiting on a buyer. Taken: the label's ready, and shipping is due 3 days after it.
        String offerId = TestApi.read(api.get(sale(card), seller), "$.offer.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
        api.postJson(listings(card), "{\"offerId\":\"" + offerId + "\"}", seller).andExpect(status().isCreated());
        api.get(needs, seller).andExpect(jsonPath("$.length()").value(0));
        String listing = TestApi.read(api.get(sale(card), seller), "$.listing.id");
        JwtRequestPostProcessor buyer = buyer("Vendor " + UUID.randomUUID());
        address(lastBuyerOrg, buyer, "Vendor");
        api.postJson(accept(lastBuyerOrg, listing), "", buyer).andExpect(status().isOk());
        api.get(needs, seller).andExpect(jsonPath("$[0].need").value("LABEL_READY"));
        Instant due = Instant.parse(TestApi.read(api.get(needs, seller), "$[0].due"));
        Instant bought = Instant.parse(TestApi.read(api.get(sale(card), seller), "$.listing.label.boughtAt"));
        assertThat(Duration.between(bought, due)).isEqualTo(Duration.ofDays(3));
        api.get("/api/orgs/" + orgId + "/cards/" + card + "/status", seller)
                .andExpect(jsonPath("$.stage").value("SOLD")).andExpect(jsonPath("$.deadline").isNotEmpty());

        // Once the seller says it shipped, nothing is left to do, and the date goes.
        api.postJson("/api/orgs/" + orgId + "/listings/" + listing + "/shipped", "", seller).andExpect(status().is2xxSuccessful());
        api.get(needs, seller).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/orgs/" + orgId + "/cards/" + card + "/status", seller).andExpect(jsonPath("$.deadline").doesNotExist());

        // It arrived: the seller says when they've been paid, by the pay-by date.
        String deal = "/api/orgs/" + lastBuyerOrg + "/deals/" + listing;
        api.postJson(deal + "/received", "{}", buyer).andExpect(status().isCreated());
        api.get(needs, seller).andExpect(jsonPath("$[0].need").value("PAYMENT_DUE"))
                .andExpect(jsonPath("$[0].due").value(TestApi.read(api.get(sale(card), seller), "$.listing.progress.payBy")));

        // Raised with us, it's off their list while we look.
        String mine = "/api/orgs/" + orgId + "/listings/" + listing;
        api.postJson(mine + "/dispute", "{\"note\":\"Nothing yet\"}", seller).andExpect(status().isOk());
        api.get(needs, seller).andExpect(jsonPath("$.length()").value(0));
        api.postJson("/api/admin/deals/" + listing + "/resolve", "{\"reason\":\"Spoke to the buyer\"}", api.admin()).andExpect(status().isOk());

        // The buyer says they paid: only the seller's word closes it.
        api.postJson(deal + "/paid", "{\"pricePaid\":1,\"note\":\"Agreed less\"}", buyer).andExpect(status().isCreated());
        api.get(needs, seller).andExpect(jsonPath("$[0].need").value("PAYMENT_CHECK"));
        api.postJson(mine + "/payment-confirmed", "", seller).andExpect(status().isOk());
        api.get(needs, seller).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void homeListsAReturnUntilTheCardIsBack() throws Exception {
        String needs = "/api/orgs/" + orgId + "/needs-you";
        String listing = postedCard("Coming back", 400);
        JwtRequestPostProcessor buyer = buyer("Vendor " + UUID.randomUUID());
        address(lastBuyerOrg, buyer, "Vendor");
        api.postJson(accept(lastBuyerOrg, listing), "", buyer).andExpect(status().isOk());
        api.postJson("/api/orgs/" + lastBuyerOrg + "/deals/" + listing + "/returned", "{\"reason\":\"DOES_NOT_MATCH_PHOTOS\",\"note\":\"crease\"}", buyer)
                .andExpect(status().isCreated());
        String mine = "$[?(@.label == 'Coming back')].need";
        assertThat(JsonPath.<List<String>>read(api.get(needs, seller).andReturn().getResponse().getContentAsString(), mine)).containsExactly("CARD_RETURNING");
        api.postJson("/api/orgs/" + orgId + "/listings/" + listing + "/card-back", "", seller).andExpect(status().isOk());
        assertThat(JsonPath.<List<String>>read(api.get(needs, seller).andReturn().getResponse().getContentAsString(), mine)).doesNotContain("CARD_RETURNING");
    }

    @Test
    void homeListsAFailedPhotoUntilItIsRetaken() throws Exception {
        String card = card("Blurry");
        api.uploadPhoto(orgId, card, "FRONT", new byte[] {1, 2, 3}, seller).andExpect(status().isOk());
        api.get("/api/orgs/" + orgId + "/needs-you", seller).andExpect(jsonPath("$[0].need").value("RETAKE"))
                .andExpect(jsonPath("$[0].sides[0]").value("FRONT")).andExpect(jsonPath("$[0].sides.length()").value(1));
    }

    @Test
    void aLabelJustBoughtIsNotOverdue() throws Exception {
        String overdue = "/api/admin/deals/overdue";
        api.get(overdue, seller).andExpect(status().isForbidden());
        String before = api.get(overdue, api.admin()).andReturn().getResponse().getContentAsString();

        String late = postedCard("Late", 400);
        JwtRequestPostProcessor buyer = buyer("Vendor " + UUID.randomUUID());
        address(lastBuyerOrg, buyer, "Vendor");
        api.postJson(accept(lastBuyerOrg, late), "", buyer).andExpect(status().isOk());
        assertThat(delta(api.get(overdue, api.admin()).andReturn().getResponse().getContentAsString(), before, "$.labels")).isZero();
    }

    @Test
    void aNewSellerStartsWithOneBinder() throws Exception {
        String subject = "auth0|new-seller-" + UUID.randomUUID();
        String org = api.collectorOrg("New seller", subject);
        JwtRequestPostProcessor who = api.user(subject, List.of());
        api.get("/api/orgs/" + org + "/collections", who).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("My cards"));

        // Agreeing again, as a new version of the terms would ask, doesn't add another.
        api.postJson("/api/orgs/" + org + "/consent", "{\"version\":\"" + Consents.SELLER_TERMS_VERSION + "\"}", who).andExpect(status().isOk());
        api.get("/api/orgs/" + org + "/collections", who).andExpect(jsonPath("$.length()").value(1));
    }

    private static int delta(String after, String before, String path) {
        return JsonPath.<Integer>read(after, path) - JsonPath.<Integer>read(before, path);
    }

    /** A priced card, offered and posted to buyers, with the seller's address on file. Returns the listing id. */
    private String postedCard(String name, int top) throws Exception {
        String card = pricedCard(name, top);
        String offerId = TestApi.read(api.postJson(offers(card), "", seller).andExpect(status().isCreated()), "$.id");
        api.postJson("/api/orgs/" + orgId + "/ship-address", SELLER_ADDRESS, seller).andExpect(status().isCreated());
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

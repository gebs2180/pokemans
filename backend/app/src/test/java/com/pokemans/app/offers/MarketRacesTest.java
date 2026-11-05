package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What strangers do at the same moment, or twice (audit of 2026-11-02): racing for one card, double
 * clicks and retries on every action that closes or pays for something, and the edges of the offer
 * and listing windows. Requests run at once on real threads against the real database.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class MarketRacesTest {

    private static final String RECEIVED = "{\"pricePaid\":%s}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FakeShippingProvider shipper;

    @Autowired
    private SaleListingRepository listings;

    @Autowired
    private ListingLock listingLock;

    @Autowired
    private PlatformTransactionManager transactions;

    private TestApi api;
    private TestMarket market;
    private Seller seller;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        market = new TestMarket(api);
        seller = market.seller();
        shipper.failing = false;
        shipper.failure = "carrier is down";
        shipper.unrecordable = false;
        shipper.gate = null;
    }

    @Test
    void manyBuyersRacingForOneCardLeaveOneDealAndOneLabel() throws Exception {
        for (int round = 0; round < 3; round++) {
            Posted card = market.posted(seller, "Raced Charizard " + round);
            List<Buyer> buyers = List.of(market.buyer(), market.buyer(), market.buyer(), market.buyer());

            List<Callable<Integer>> calls = new ArrayList<>();
            for (Buyer b : buyers) {
                calls.add(() -> code(TestMarket.accept(b, card), "", b));
            }
            List<Integer> codes = allAtOnce(calls);

            assertThat(codes).containsExactlyInAnyOrder(200, 409, 409, 409);
            assertThat(events(card, "ACCEPTED")).isEqualTo(1);
            assertThat(labelsBought(card)).isEqualTo(1);
            // The buyer who got 200 is the one the deal belongs to, and the losers have nothing.
            Buyer winner = buyers.get(codes.indexOf(200));
            assertThat(TestApi.read(api.get("/api/orgs/" + winner.orgId() + "/deals", winner.who()), "$[0].listingId")).isEqualTo(card.listingId());
            for (Buyer loser : buyers) {
                if (loser != winner) {
                    api.get("/api/orgs/" + loser.orgId() + "/deals", loser.who()).andExpect(jsonPath("$").isEmpty());
                }
            }
        }
    }

    @Test
    void aBuyerAndTheSellerRacingLeaveOneOutcome() throws Exception {
        for (int round = 0; round < 3; round++) {
            Posted card = market.posted(seller, "Withdrawn Mew " + round);
            Buyer buyer = market.buyer();
            List<Integer> codes = allAtOnce(List.of(
                    () -> code(TestMarket.accept(buyer, card), "", buyer),
                    () -> code("/api/orgs/" + seller.orgId() + "/listings/" + card.listingId() + "/withdraw", "", seller)));

            assertThat(codes).containsExactlyInAnyOrder(200, 409);
            assertThat(events(card, "ACCEPTED") + events(card, "WITHDRAWN")).isEqualTo(1);
            assertThat(labelsBought(card)).isEqualTo(codes.getFirst() == 200 ? 1 : 0);
        }
    }

    @Test
    void aBuyerAndAVoidRacingLeaveOneOutcome() throws Exception {
        for (int round = 0; round < 3; round++) {
            Posted card = market.posted(seller, "Voided Lugia " + round);
            Buyer buyer = market.buyer();
            List<Integer> codes = allAtOnce(List.of(
                    () -> code(TestMarket.accept(buyer, card), "", buyer),
                    () -> mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post("/api/admin/offers/" + card.offerId() + "/void").with(api.admin())
                                    .contentType("application/json").content("{\"reason\":\"wrong card\"}"))
                            .andReturn().getResponse().getStatus()));

            assertThat(codes).containsExactlyInAnyOrder(200, 409);
            assertThat(events(card, "ACCEPTED") + events(card, "VOIDED")).isEqualTo(1);
        }
    }

    @Test
    void aSecondAcceptByTheSameBuyerGetsTheirDealNotAnotherBuyersRefusal() throws Exception {
        // The answer to the first accept can be lost on a phone. The retry must not tell the buyer they lost the card they won.
        Posted card = market.posted(seller, "Retried Blastoise");
        Buyer buyer = market.buyer();

        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk())
                .andExpect(jsonPath("$.deals[0].listingId").value(card.listingId()));

        assertThat(events(card, "ACCEPTED")).isEqualTo(1);
        assertThat(labelsBought(card)).isEqualTo(1);
    }

    @Test
    void aDoubleClickOnAcceptBuysOneCardAndOneLabel() throws Exception {
        for (int round = 0; round < 3; round++) {
            Posted card = market.posted(seller, "Double Clicked Venusaur " + round);
            Buyer buyer = market.buyer();
            List<Integer> codes = allAtOnce(List.of(
                    () -> code(TestMarket.accept(buyer, card), "", buyer),
                    () -> code(TestMarket.accept(buyer, card), "", buyer)));

            assertThat(codes).containsExactly(200, 200);
            assertThat(events(card, "ACCEPTED")).isEqualTo(1);
            assertThat(labelsBought(card)).isEqualTo(1);
        }
    }

    @Test
    void aDoubleClickOnPostOpensOneListing() throws Exception {
        Posted card = market.offered(seller, "Posted Twice Gyarados");
        String post = "/api/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/listings";
        String body = "{\"offerId\":\"" + card.offerId() + "\"}";

        List<Integer> codes = allAtOnce(List.of(() -> code(post, body, seller), () -> code(post, body, seller)));

        assertThat(codes).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from sale_listing where card_item_id = ?::uuid", Integer.class, card.cardId())).isEqualTo(1);
    }

    @Test
    void aDoubleClickOnVoidOrCancelCountsOnce() throws Exception {
        Posted voided = market.posted(seller, "Voided Twice Snorlax");
        String voidIt = "/api/admin/offers/" + voided.offerId() + "/void";
        assertThat(allAtOnce(List.of(() -> adminStatus(voidIt), () -> adminStatus(voidIt)))).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from card_offer_void where offer_id = ?::uuid", Integer.class, voided.offerId())).isEqualTo(1);

        Posted taken = market.posted(seller, "Cancelled Twice Eevee");
        Buyer buyer = market.buyer();
        api.postJson(TestMarket.accept(buyer, taken), "", buyer.who()).andExpect(status().isOk());
        String cancel = "/api/admin/deals/" + taken.listingId() + "/cancel";
        assertThat(allAtOnce(List.of(() -> adminStatus(cancel), () -> adminStatus(cancel)))).containsExactlyInAnyOrder(200, 409);
        assertThat(events(taken, "CANCELLED")).isEqualTo(1);
    }

    @Test
    void aDoubleClickOnRecordingAnArrivalCountsOnce() throws Exception {
        Posted card = market.posted(seller, "Arrived Mewtwo");
        Buyer buyer = market.buyer();
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());
        String received = "/api/orgs/" + buyer.orgId() + "/deals/" + card.listingId() + "/received";
        String price = TestApi.read(api.get("/api/orgs/" + buyer.orgId() + "/deals", buyer.who()), "$[0].offer.price");
        String body = RECEIVED.formatted(price);

        assertThat(allAtOnce(List.of(() -> code(received, body, buyer), () -> code(received, body, buyer))))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(events(card, "RECEIVED")).isEqualTo(1);
    }

    @Test
    void aFinishedDealGetsNoLabel() throws Exception {
        // The admin recorded the arrival by hand because the card shipped another way. A retry from the buyer's
        // deals page, or the admin's, must not then buy a label on our account and bill it to the buyer.
        Posted card = market.posted(seller, "Hand Shipped Rayquaza");
        Buyer buyer = market.buyer();
        shipper.failing = true;
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());
        shipper.failing = false;
        api.postJson("/api/admin/deals/" + card.listingId() + "/received?buyerOrgId=" + buyer.orgId(),
                "{\"pricePaid\":1,\"note\":\"met at a show\"}", api.admin()).andExpect(status().isCreated());

        api.postJson("/api/orgs/" + buyer.orgId() + "/deals/" + card.listingId() + "/label", "", buyer.who()).andExpect(status().isConflict());
        api.postJson("/api/admin/deals/" + card.listingId() + "/label?buyerOrgId=" + buyer.orgId(), "", api.admin()).andExpect(status().isConflict());

        assertThat(labelsBought(card)).isZero();
    }

    @Test
    void aCancelledDealStopsShowingTheSellerTheLabel() throws Exception {
        // The label PDF carries the buyer's address and is paid for on our account. Once we've cancelled, the seller
        // shouldn't be offered it: mailing the card with it would use a label we've asked to refund.
        Posted card = market.posted(seller, "Cancelled Label Zapdos");
        Buyer buyer = market.buyer();
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());
        String sale = "/api/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/sale";
        api.get(sale, seller.who()).andExpect(jsonPath("$.listing.label.labelUrl").isNotEmpty());

        api.postJson("/api/admin/deals/" + card.listingId() + "/cancel", "{\"reason\":\"seller can't ship\"}", api.admin()).andExpect(status().isOk());

        api.get(sale, seller.who())
                .andExpect(jsonPath("$.listing.status").value("CANCELLED"))
                .andExpect(jsonPath("$.listing.label.labelUrl").isEmpty());
    }

    @Test
    void buyersDontGetTheSellersOwnLabelForTheCard() throws Exception {
        // Free text the seller typed for themselves. No buyer screen uses it, and it can hold a name or a phone number.
        Posted card = market.posted(seller, "Call Sam 555-0100");
        Buyer buyer = market.buyer();
        String item = "$[?(@.listingId=='" + card.listingId() + "')]";

        api.get("/api/orgs/" + buyer.orgId() + "/market", buyer.who()).andExpect(jsonPath(item + ".card.label").value(org.hamcrest.Matchers.contains((Object) null)));
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());
        api.get("/api/orgs/" + buyer.orgId() + "/deals", buyer.who()).andExpect(jsonPath("$[0].card.label").isEmpty());
    }

    @Test
    void addressesInAProvidersErrorStayOutOfTheLogs(CapturedOutput output) throws Exception {
        // RestClient puts the response body in the exception message, and EasyPost's address errors quote the address.
        // ERROR lines and their stack traces go to Sentry as well as the log.
        Posted card = market.posted(seller, "Unlabelled Moltres");
        Buyer buyer = market.buyer();
        shipper.failing = true;
        shipper.failure = "422 Unprocessable Entity: address invalid: 1 Secret Lane, Springfield IL";

        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk())
                .andExpect(jsonPath("$.labelPending", not(containsString("Secret Lane"))));
        api.postJson("/api/orgs/" + buyer.orgId() + "/deals/" + card.listingId() + "/label", "", buyer.who()).andExpect(status().isBadGateway());

        assertThat(output.getAll()).contains("Couldn't buy the label for listing " + card.listingId());
        assertThat(output.getAll()).doesNotContain("Secret Lane");
    }

    @Test
    void aListingIsGoneAtTheInstantItExpires() throws Exception {
        Posted card = market.posted(seller, "Expiring Articuno");
        SaleListing listing = listings.findById(UUID.fromString(card.listingId())).orElseThrow();
        Instant end = listing.getExpiresAt();
        Instant before = end.minus(1, ChronoUnit.MICROS);

        assertThat(ListingStateMachine.status(listing, List.of(), before)).isEqualTo(ListingStateMachine.Status.OPEN);
        assertThat(ListingStateMachine.status(listing, List.of(), end)).isEqualTo(ListingStateMachine.Status.EXPIRED);
        // The market's query and the status agree on both sides of the line.
        assertThat(listings.findOpen(before).stream().map(SaleListing::getId)).contains(listing.getId());
        assertThat(listings.findOpen(end).stream().map(SaleListing::getId)).doesNotContain(listing.getId());
    }

    @Test
    void anExpiredListingCantBeTakenOrWithdrawnAndLeavesTheMarket() throws Exception {
        Posted card = expiredListing("Lapsed Ho-Oh");
        Buyer buyer = market.buyer();

        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("expired")));
        api.postJson("/api/orgs/" + seller.orgId() + "/listings/" + card.listingId() + "/withdraw", "", seller.who())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("expired")));
        api.get("/api/orgs/" + buyer.orgId() + "/market", buyer.who()).andExpect(jsonPath("$[?(@.listingId=='" + card.listingId() + "')]").isEmpty());
        assertThat(labelsBought(card)).isZero();
    }

    @Test
    void anExpiredOfferCantBePosted() throws Exception {
        Posted card = market.offered(seller, "Stale Offer Celebi");
        String expired = jdbc.queryForObject("""
                insert into card_offer (id, org_id, card_item_id, catalog_card_id, assessment_id, snapshot_set_id, graded, inputs,
                  parameter_set, buyer_margin, ladder, price, fmv, confidence, subject_to_authentication, trace, created_by, created_at, expires_at)
                select gen_random_uuid(), org_id, card_item_id, catalog_card_id, assessment_id, snapshot_set_id, graded, inputs,
                  parameter_set, buyer_margin, ladder, price, fmv, confidence, subject_to_authentication, trace, created_by, created_at, now()
                from card_offer where id = ?::uuid returning id::text""", String.class, card.offerId());

        api.postJson("/api/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/listings", "{\"offerId\":\"" + expired + "\"}", seller.who())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("expired")));
    }

    @Test
    void everyWriteOnACardWaitsForItsListingLock() throws Exception {
        // Review finding 39: the lock was copied into three places; each write path must still take the one ListingLock.
        Posted offered = market.offered(seller, "Locked Post Pidgeot");
        waitsForTheLock(offered, () -> code("/api/orgs/" + seller.orgId() + "/cards/" + offered.cardId() + "/listings",
                "{\"offerId\":\"" + offered.offerId() + "\"}", seller), 201);

        Posted withdrawn = market.posted(seller, "Locked Withdraw Raichu");
        waitsForTheLock(withdrawn, () -> code("/api/orgs/" + seller.orgId() + "/listings/" + withdrawn.listingId() + "/withdraw", "", seller), 200);

        Posted voided = market.posted(seller, "Locked Void Arcanine");
        waitsForTheLock(voided, () -> adminStatus("/api/admin/offers/" + voided.offerId() + "/void"), 200);

        Posted taken = market.posted(seller, "Locked Accept Dragonite");
        Buyer buyer = market.buyer();
        waitsForTheLock(taken, () -> code(TestMarket.accept(buyer, taken), "", buyer), 200);

        String price = TestApi.read(api.get("/api/orgs/" + buyer.orgId() + "/deals", buyer.who()), "$[0].offer.price");
        waitsForTheLock(taken, () -> code("/api/orgs/" + buyer.orgId() + "/deals/" + taken.listingId() + "/received",
                RECEIVED.formatted(price), buyer), 201);

        Posted cancelled = market.posted(seller, "Locked Cancel Machamp");
        api.postJson(TestMarket.accept(buyer, cancelled), "", buyer.who()).andExpect(status().isOk());
        waitsForTheLock(cancelled, () -> adminStatus("/api/admin/deals/" + cancelled.listingId() + "/cancel"), 200);
    }

    /** Holds the card's listing lock in another transaction, checks the call waits for it, then lets it through. */
    private void waitsForTheLock(Posted card, Callable<Integer> call, int expected) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                listingLock.lock(UUID.fromString(card.cardId()));
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> waiting = pool.submit(call);
            assertThatThrownBy(() -> waiting.get(750, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(waiting.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** A posted card whose listing window has closed: a second listing row, since the table refuses updates. */
    private Posted expiredListing(String name) throws Exception {
        Posted card = market.offered(seller, name);
        String id = jdbc.queryForObject("""
                insert into sale_listing (id, offer_id, org_id, card_item_id, listed_by, created_at, expires_at)
                select gen_random_uuid(), o.id, o.org_id, o.card_item_id, o.created_by, now() - interval '15 days', now()
                from card_offer o where o.id = ?::uuid returning id::text""", String.class, card.offerId());
        return new Posted(card.cardId(), card.offerId(), id);
    }

    private int code(String url, String body, Seller who) throws Exception {
        return api.postJson(url, body, who.who()).andReturn().getResponse().getStatus();
    }

    private int code(String url, String body, Buyer who) throws Exception {
        return api.postJson(url, body, who.who()).andReturn().getResponse().getStatus();
    }

    private int adminStatus(String url) throws Exception {
        return api.postJson(url, "{\"reason\":\"twice\"}", api.admin()).andReturn().getResponse().getStatus();
    }

    private int events(Posted card, String type) {
        return jdbc.queryForObject("select count(*) from listing_event where listing_id = ?::uuid and type = ?", Integer.class, card.listingId(), type);
    }

    private long labelsBought(Posted card) {
        return shipper.bought.stream().filter(b -> b.reference().equals(card.listingId())).count();
    }

    /** Runs the calls at the same instant and returns each one's answer, in order. */
    private static List<Integer> allAtOnce(List<Callable<Integer>> calls) throws Exception {
        CyclicBarrier start = new CyclicBarrier(calls.size());
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Integer>> running = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                running.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : running) {
                codes.add(f.get(60, TimeUnit.SECONDS));
            }
            return Collections.unmodifiableList(codes);
        } finally {
            pool.shutdownNow();
        }
    }
}

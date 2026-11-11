package com.pokemans.app.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.notify.EmailOutbox.Template;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Notification emails end to end against a fake Resend (deep review C2, 2026-11-10): each deal step queues its
 * email in the step's transaction, the worker sends each one once, and the digest goes once a day to buyers who want it.
 */
@SpringBootTest(properties = {"pokemans.condition.worker.enabled=false", "pokemans.notify.email.enabled=true",
        "pokemans.notify.email.api-key=re_test_key", "pokemans.notify.email.poll-interval=1h", "pokemans.notify.email.digest-cron=-"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class EmailOutboxTest {

    private static final FakeResend RESEND = new FakeResend();

    @DynamicPropertySource
    static void resend(DynamicPropertyRegistry registry) {
        registry.add("pokemans.notify.email.base-url", () -> RESEND.url().toString());
    }

    @AfterAll
    static void stop() {
        RESEND.close();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmailSender sender;

    @Autowired
    private Notifier notifier;

    @Autowired
    private ListingDigest digest;

    private TestApi api;
    private TestMarket market;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
        market = new TestMarket(api);
    }

    private record Deal(Seller seller, String sellerEmail, Buyer buyer, String buyerEmail, Posted card) {

        String deals() {
            return "/api/orgs/" + buyer.orgId() + "/deals/" + card.listingId();
        }
    }

    /** A posted card taken by a buyer; both sides' emails synced from their logins. */
    private Deal taken(String name) throws Exception {
        String sellerSubject = "auth0|seller-" + UUID.randomUUID();
        String buyerSubject = "auth0|buyer-" + UUID.randomUUID();
        Seller seller = market.seller(sellerSubject);
        Buyer buyer = market.buyer(buyerSubject);
        String sellerEmail = login(sellerSubject);
        String buyerEmail = login(buyerSubject);
        Posted card = market.posted(seller, name);
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());
        return new Deal(seller, sellerEmail, buyer, buyerEmail, card);
    }

    /** Signs in with an email claim, the way Auth0 sends it, so the address is synced. Returns the address. */
    private String login(String subject) throws Exception {
        String email = subject.substring(subject.indexOf('|') + 1) + "@example.com";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/me")
                        .with(jwt().jwt(b -> b.subject(subject).claim("email", email).claim(security.mfaClaim(), true))))
                .andExpect(status().isOk());
        return email;
    }

    /** No label in these tests, so the admin records the arrival, with the note that needs. */
    private void arrive(Deal deal) throws Exception {
        api.postJson("/api/admin/deals/" + deal.card().listingId() + "/received?buyerOrgId=" + deal.buyer().orgId(),
                "{\"note\":\"Arrived; shipped without our label\"}", api.admin()).andExpect(status().is2xxSuccessful());
    }

    private List<Map<String, Object>> rows(String listingId) {
        return jdbc.queryForList("select * from email_message where listing_id = ?::uuid order by created_at, template", listingId);
    }

    private Map<String, Object> row(String listingId, Template template) {
        return rows(listingId).stream().filter(r -> r.get("template").equals(template.name())).findFirst().orElseThrow();
    }

    @Test
    void aTakenCardEmailsTheSellerOnceWhateverHowOftenTheWorkerRuns() throws Exception {
        Deal deal = taken("Emailed Charizard");

        assertThat(rows(deal.card().listingId())).singleElement().satisfies(r -> {
            assertThat(r.get("template")).isEqualTo("SALE_TAKEN");
            assertThat(r.get("status")).isEqualTo("PENDING");
            assertThat(r.get("recipient_email")).isEqualTo(deal.sellerEmail());
            assertThat((String) r.get("subject")).startsWith("Sold: Emailed Charizard");
            assertThat((String) r.get("text_body")).contains("https://beta.rawmark.app/orgs/" + deal.seller().orgId() + "/cards/" + deal.card().cardId());
        });

        sender.sendDue(Instant.now());
        sender.sendDue(Instant.now());

        List<FakeResend.Request> sent = RESEND.requestsTo(deal.sellerEmail());
        assertThat(sent).hasSize(1);
        Map<String, Object> r = row(deal.card().listingId(), Template.SALE_TAKEN);
        assertThat(r.get("status")).isEqualTo("SENT");
        assertThat(r.get("provider_id")).isNotNull();
        assertThat(r.get("attempts")).isEqualTo(1);
        // The idempotency key is the row: a retry after a lost answer is the same email to Resend.
        assertThat(sent.getFirst().idempotencyKey()).isEqualTo(r.get("id").toString());
        assertThat(sent.getFirst().authorization()).isEqualTo("Bearer re_test_key");
    }

    @Test
    void theSameEmailQueuedTwiceIsOneRow() {
        String key = "TEST:" + UUID.randomUUID();
        EmailContent content = EmailContent.of("Hi", List.of("Hello"), "Open", "https://beta.rawmark.app/", "Footer");
        Instant now = Instant.now();

        assertThat(notifier.queue(key, Template.NEW_LISTINGS, null, null, null, content, now, now)).isTrue();
        assertThat(notifier.queue(key, Template.NEW_LISTINGS, null, null, null, content, now, now)).isFalse();

        assertThat(jdbc.queryForObject("select count(*) from email_message where dedupe_key = ?", Integer.class, key)).isOne();
        // No recipient: stored, never sent.
        assertThat(jdbc.queryForObject("select status_reason from email_message where dedupe_key = ?", String.class, key))
                .isEqualTo(Notifier.NO_EMAIL);
    }

    @Test
    void aSellerWithNoEmailIsSkippedAndTheSkipIsStored() throws Exception {
        Seller seller = market.seller();
        Buyer buyer = market.buyer();
        Posted card = market.posted(seller, "Unmailed Blastoise");

        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());

        Map<String, Object> r = row(card.listingId(), Template.SALE_TAKEN);
        assertThat(r.get("status")).isEqualTo("SKIPPED");
        assertThat(r.get("status_reason")).isEqualTo(Notifier.NO_EMAIL);
        assertThat(r.get("recipient_email")).isNull();
    }

    @Test
    void arrivalTellsBothSidesAndPayingCancelsTheReminder() throws Exception {
        Deal deal = taken("Arrived Venusaur");
        arrive(deal);

        String listing = deal.card().listingId();
        assertThat(row(listing, Template.CARD_ARRIVED).get("recipient_email")).isEqualTo(deal.sellerEmail());
        assertThat(row(listing, Template.PAYMENT_DUE).get("recipient_email")).isEqualTo(deal.buyerEmail());
        Map<String, Object> reminder = row(listing, Template.PAYMENT_REMINDER);
        assertThat(reminder.get("status")).isEqualTo("PENDING");
        Instant remindAt = ((java.sql.Timestamp) reminder.get("next_attempt_at")).toInstant();
        assertThat(remindAt).isBetween(Instant.now().plus(Duration.ofDays(4)), Instant.now().plus(Duration.ofDays(6)));

        // The reminder isn't due yet: only the seller's and buyer's arrival emails go.
        sender.sendDue(Instant.now());
        assertThat(RESEND.requestsTo(deal.buyerEmail())).singleElement()
                .satisfies(r -> assertThat(r.field("subject")).startsWith("Payment due"));

        api.postJson(deal.deals() + "/paid", "{\"pricePaid\":1,\"note\":\"part paid\"}", deal.buyer().who()).andExpect(status().isCreated());

        assertThat(row(listing, Template.PAYMENT_REMINDER).get("status")).isEqualTo("CANCELLED");
        Map<String, Object> paid = row(listing, Template.BUYER_PAID);
        assertThat(paid.get("recipient_email")).isEqualTo(deal.sellerEmail());
        assertThat((String) paid.get("text_body")).contains("$1.00");

        // Even past its date, a cancelled reminder never goes.
        sender.sendDue(remindAt.plusSeconds(1));
        assertThat(RESEND.requestsTo(deal.buyerEmail())).hasSize(1);
    }

    @Test
    void theReminderGoesWhenStillUnpaidAndOnlyThen() throws Exception {
        Deal deal = taken("Unpaid Gengar");
        arrive(deal);
        sender.sendDue(Instant.now());
        Instant remindAt = ((java.sql.Timestamp) row(deal.card().listingId(), Template.PAYMENT_REMINDER).get("next_attempt_at")).toInstant();

        sender.sendDue(remindAt.plusSeconds(1));

        assertThat(row(deal.card().listingId(), Template.PAYMENT_REMINDER).get("status")).isEqualTo("SENT");
        assertThat(RESEND.requestsTo(deal.buyerEmail())).extracting(r -> r.field("subject"))
                .anySatisfy(s -> assertThat(s).startsWith("Reminder: payment due"));
    }

    @Test
    void aFailedSendIsRetriedLaterAndARefusedOneFailsForGood() {
        Instant now = Instant.now();
        String retried = "TEST:" + UUID.randomUUID();
        String refused = "TEST:" + UUID.randomUUID();
        EmailContent content = EmailContent.of("Hi", List.of("Hello"), "Open", "https://beta.rawmark.app/", "Footer");
        UUID user = UUID.randomUUID();
        jdbc.update("insert into app_user (id, subject, email, created_at) values (?, ?, ?, now())", user, "auth0|" + user, user + "@example.com");
        // Due slightly in the past so other tests' rows don't take this batch's place.
        notifier.queue(retried, Template.NEW_LISTINGS, user, null, null, content, now.minusSeconds(3600), now);
        notifier.queue(refused, Template.NEW_LISTINGS, user, null, null, content, now.minusSeconds(3600), now);
        drainOthers(now);
        RESEND.answerNext(503, "{\"name\":\"internal_server_error\"}");
        RESEND.answerNext(422, "{\"name\":\"validation_error\"}");

        sender.sendDue(now);

        Map<String, Object> a = jdbc.queryForMap("select * from email_message where dedupe_key = ?", retried);
        Map<String, Object> b = jdbc.queryForMap("select * from email_message where dedupe_key = ?", refused);
        Map<String, Object> wasRetried = a.get("status").equals("PENDING") ? a : b;
        Map<String, Object> wasRefused = wasRetried == a ? b : a;
        assertThat(wasRetried.get("status")).isEqualTo("PENDING");
        assertThat((String) wasRetried.get("last_error")).startsWith("HTTP 503");
        assertThat(((java.sql.Timestamp) wasRetried.get("next_attempt_at")).toInstant()).isAfter(now);
        assertThat(wasRefused.get("status")).isEqualTo("FAILED");
        assertThat(wasRefused.get("status_reason")).isEqualTo("Refused by the provider");

        sender.sendDue(now.plus(Duration.ofMinutes(2)));
        assertThat(jdbc.queryForObject("select status from email_message where id = ?", String.class, wasRetried.get("id"))).isEqualTo("SENT");
    }

    /** Sends whatever other tests left due, so the next batch is this test's rows. */
    private void drainOthers(Instant now) {
        jdbc.update("update email_message set next_attempt_at = ? where status = 'PENDING' and dedupe_key not like 'TEST:%'",
                java.sql.Timestamp.from(now.plus(Duration.ofDays(30))));
    }

    @Test
    void theDigestGoesOncePerDayOnlyWithNewCardsAndNeverAboutTheBuyersOwnOrg() throws Exception {
        String subject = "auth0|digest-" + UUID.randomUUID();
        Buyer buyer = market.buyer(subject);
        String email = login(subject);
        Seller seller = market.seller();
        // The same person sells too: their own cards are no news to them.
        Seller own = market.seller(subject);
        market.posted(seller, "Digest Pikachu");
        market.posted(own, "Own Mewtwo");
        Instant now = Instant.now();

        digest.run(now);
        digest.run(now);

        List<Map<String, Object>> sent = digests(buyer);
        assertThat(sent).singleElement().satisfies(r -> {
            assertThat(r.get("recipient_email")).isEqualTo(email);
            assertThat((String) r.get("text_body")).contains("Digest Pikachu").doesNotContain("Own Mewtwo")
                    .contains("https://beta.rawmark.app/orgs/" + buyer.orgId() + "/market");
        });

        // The next day, with nothing new since: no email.
        digest.run(now.plus(Duration.ofDays(1)));
        assertThat(digests(buyer)).hasSize(1);

        // Turned off: no email even with a new card.
        mvc.perform(put("/api/me/email-preferences").with(buyer.who()).contentType(MediaType.APPLICATION_JSON).content("{\"digest\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.digest").value(false));
        api.get("/api/me/email-preferences", buyer.who()).andExpect(jsonPath("$.digest").value(false));
        market.posted(seller, "Later Eevee");
        digest.run(now.plus(Duration.ofDays(2)));
        assertThat(digests(buyer)).hasSize(1);
    }

    private List<Map<String, Object>> digests(Buyer buyer) {
        return jdbc.queryForList("select * from email_message where template = 'NEW_LISTINGS' and org_id = ?::uuid", buyer.orgId());
    }

    @Test
    void theDigestIsOnUntilTurnedOff() throws Exception {
        Buyer buyer = market.buyer();
        api.get("/api/me/email-preferences", buyer.who()).andExpect(status().isOk()).andExpect(jsonPath("$.digest").value(true));
        mvc.perform(put("/api/me/email-preferences").with(buyer.who()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}

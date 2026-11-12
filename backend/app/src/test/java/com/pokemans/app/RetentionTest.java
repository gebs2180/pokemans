package com.pokemans.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.notify.EmailRetention;
import com.pokemans.app.offers.AddressRetention;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Emails and addresses are kept only as long as they're needed (privacy review 2026-11-12). The rows are written with
 * old timestamps, so the job runs on today's clock and leaves every other test's (recent) data alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class RetentionTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EmailRetention emails;

    @Autowired
    private AddressRetention addresses;

    private final Instant now = Instant.now();
    private TestApi api;
    private Map<String, Object> listing;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        TestMarket market = new TestMarket(api);
        TestMarket.Seller seller = market.seller();
        TestMarket.Posted posted = market.posted(seller, "Retention " + UUID.randomUUID());
        // The old deals below reuse this listing's offer, card and user; only their dates and events are their own.
        listing = jdbc.sql("select offer_id, org_id, card_item_id, listed_by from sale_listing where id = :id")
                .param("id", UUID.fromString(posted.listingId())).query().singleRow();
    }

    @Test
    void anOldEmailLosesItsAddressAndContentAndKeepsItsRecord() {
        UUID oldSent = email("SENT", 91);
        UUID oldFailed = email("FAILED", 200);
        UUID recent = email("SENT", 89);
        UUID pending = email("PENDING", 200);

        emails.run(now);

        Map<String, Object> cleared = emailRow(oldSent);
        assertThat(cleared).containsEntry("recipient_email", null).containsEntry("subject", null).containsEntry("html_body", null)
                .containsEntry("text_body", null).containsEntry("last_error", null)
                .containsEntry("template", "DEAL_TAKEN").containsEntry("status", "SENT").containsEntry("status_reason", "why");
        assertThat(cleared.get("content_cleared_at")).isNotNull();
        assertThat(cleared.get("created_at")).isNotNull();
        assertThat(cleared.get("sent_at")).isNotNull();
        assertThat(emailRow(oldFailed).get("recipient_email")).isNull();
        assertThat(emailRow(recent).get("recipient_email")).isEqualTo("seller@example.com");
        assertThat(emailRow(recent).get("content_cleared_at")).isNull();
        // Still to go out: never cleared, however old.
        assertThat(emailRow(pending).get("html_body")).isEqualTo("<p>Your card was taken</p>");

        Instant clearedAt = ((Timestamp) cleared.get("content_cleared_at")).toInstant();
        emails.run(now.plusSeconds(60));
        assertThat(((Timestamp) emailRow(oldSent).get("content_cleared_at")).toInstant()).isEqualTo(clearedAt);
    }

    @Test
    void anAddressGoesOnceEveryDealOnItIsLongClosedAndTheNewestIsKept() throws Exception {
        UUID org = (UUID) listing.get("org_id");
        UUID settled = address(org, 800);
        deal(settled, Map.of(700, "ACCEPTED", 400, "PAYMENT_CONFIRMED"));
        UUID settledRecently = address(org, 790);
        deal(settledRecently, Map.of(700, "ACCEPTED", 300, "PAYMENT_CONFIRMED"));
        UUID paidNotConfirmed = address(org, 780);
        deal(paidNotConfirmed, Map.of(700, "ACCEPTED", 690, "PAID"));
        UUID cancelled = address(org, 770);
        deal(cancelled, Map.of(700, "ACCEPTED", 500, "CANCELLED"));
        UUID disputedAfter = address(org, 760);
        deal(disputedAfter, Map.of(700, "ACCEPTED", 600, "PAYMENT_CONFIRMED", 500, "DISPUTED"));
        UUID resolvedAfter = address(org, 755);
        deal(resolvedAfter, Map.of(700, "ACCEPTED", 600, "PAYMENT_CONFIRMED", 500, "DISPUTED", 450, "RESOLVED"));
        UUID oneOpen = address(org, 750);
        deal(oneOpen, Map.of(700, "ACCEPTED", 400, "PAYMENT_CONFIRMED"));
        deal(oneOpen, Map.of(700, "ACCEPTED"));
        UUID replacedLongAgo = address(org, 100);
        UUID replacedRecently = address(org, 60);
        UUID current = address(org, 1);

        // An org whose only address a long-closed deal used: it's still the one the next label uses.
        UUID otherOrg = UUID.fromString(api.collectorOrg("Retention " + UUID.randomUUID(), "auth0|retention-" + UUID.randomUUID()));
        UUID newest = address(otherOrg, 800);
        deal(newest, Map.of(700, "ACCEPTED", 400, "PAYMENT_CONFIRMED"));

        addresses.run(now);

        assertAnonymised(settled);
        assertAnonymised(cancelled);
        assertAnonymised(resolvedAfter);
        assertKept(settledRecently);
        assertKept(paidNotConfirmed);
        assertKept(disputedAfter);
        assertKept(oneOpen);
        assertKept(replacedRecently);
        assertKept(current);
        assertKept(newest);
        assertThat(jdbc.sql("select count(*) from ship_address where id = :id").param("id", replacedLongAgo).query(Long.class).single()).isZero();

        // A second run changes nothing it already did.
        Object anonymisedAt = addressRow(settled).get("anonymised_at");
        addresses.run(now.plusSeconds(60));
        assertThat(addressRow(settled).get("anonymised_at")).isEqualTo(anonymisedAt);
    }

    @Test
    void addressesStayAppendOnlyApartFromAnonymising() {
        UUID org = (UUID) listing.get("org_id");
        UUID id = address(org, 10);

        assertThatThrownBy(() -> jdbc.sql("update ship_address set street1 = '1 Other St' where id = :id").param("id", id).update())
                .hasMessageContaining("append-only");
        // Anonymising may not move the address to another state or zip, or touch anything else.
        assertThatThrownBy(() -> jdbc.sql("""
                update ship_address set name = null, company = null, street1 = null, street2 = null, city = null, phone = null,
                    payment_method = null, payment_handle = null, anonymised_at = now(), zip = '00000' where id = :id""")
                .param("id", id).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("update ship_address set name = null, anonymised_at = now() where id = :id")
                .param("id", id).update()).hasMessageContaining("append-only");

        jdbc.sql("""
                update ship_address set name = null, company = null, street1 = null, street2 = null, city = null, phone = null,
                    payment_method = null, payment_handle = null, anonymised_at = now() where id = :id""").param("id", id).update();
        assertAnonymised(id);
        // Once.
        assertThatThrownBy(() -> jdbc.sql("update ship_address set anonymised_at = now() where id = :id").param("id", id).update())
                .hasMessageContaining("append-only");
    }

    private UUID email(String status, int daysAgo) {
        UUID id = UUID.randomUUID();
        Timestamp at = ago(daysAgo);
        jdbc.sql("""
                insert into email_message (id, dedupe_key, template, recipient_email, subject, html_body, text_body, status,
                    status_reason, next_attempt_at, last_error, created_at, sent_at, updated_at)
                values (:id, :key, 'DEAL_TAKEN', 'seller@example.com', 'Your card was taken', '<p>Your card was taken</p>',
                    'Your card was taken', :status, 'why', :at, '422 seller@example.com rejected', :at, :at, :at)""")
                .param("id", id).param("key", "retention-" + id).param("status", status).param("at", at).update();
        return id;
    }

    private Map<String, Object> emailRow(UUID id) {
        return jdbc.sql("select * from email_message where id = :id").param("id", id).query().singleRow();
    }

    private UUID address(UUID org, int daysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into ship_address (id, org_id, name, company, street1, street2, city, state, zip, country, phone,
                    payment_method, payment_handle, recorded_by, created_at)
                values (:id, :org, 'Ash Ketchum', 'Pallet Cards', '1 Route St', 'Apt 2', 'Pallet', 'IL', '62701', 'US',
                    '555-0100', 'VENMO', '@ash', :by, :at)""")
                .param("id", id).param("org", org).param("by", listing.get("listed_by")).param("at", ago(daysAgo)).update();
        return id;
    }

    /** An old deal on its own listing, shipped to and from this address, with these events (days ago → type). */
    private void deal(UUID address, Map<Integer, String> events) {
        UUID listingId = UUID.randomUUID();
        jdbc.sql("""
                insert into sale_listing (id, offer_id, org_id, card_item_id, listed_by, created_at, expires_at)
                values (:id, :offer, :org, :card, :by, :at, :at)""")
                .param("id", listingId).param("offer", listing.get("offer_id")).param("org", listing.get("org_id"))
                .param("card", listing.get("card_item_id")).param("by", listing.get("listed_by")).param("at", ago(720)).update();
        jdbc.sql("""
                insert into shipping_label (id, listing_id, buyer_org_id, from_address_id, to_address_id, provider, carrier, service,
                    label_url, postage, insured_value, insurance_cost, currency, created_at)
                values (:id, :listing, :org, :address, :address, 'FAKE', 'USPS', 'Priority', 'https://example.com/label.pdf',
                    9.50, 100, 1, 'USD', :at)""")
                .param("id", UUID.randomUUID()).param("listing", listingId).param("org", listing.get("org_id"))
                .param("address", address).param("at", ago(700)).update();
        events.forEach((daysAgo, type) -> jdbc.sql("""
                insert into listing_event (id, listing_id, type, actor_org_id, actor_user_id, price_paid, declined_reason, note, created_at)
                values (:id, :listing, :type, :org, :by, :paid, null, :note, :at)""")
                .param("id", UUID.randomUUID()).param("listing", listingId).param("type", type).param("org", listing.get("org_id"))
                .param("by", listing.get("listed_by")).param("paid", type.equals("PAID") ? 100 : null, java.sql.Types.NUMERIC)
                .param("note", type.equals("CANCELLED") || type.equals("RESOLVED") ? "closed by the admin" : null, java.sql.Types.VARCHAR)
                .param("at", ago(daysAgo)).update());
    }

    private Map<String, Object> addressRow(UUID id) {
        return jdbc.sql("select * from ship_address where id = :id").param("id", id).query().singleRow();
    }

    private void assertAnonymised(UUID id) {
        Map<String, Object> row = addressRow(id);
        assertThat(row).containsEntry("name", null).containsEntry("company", null).containsEntry("street1", null)
                .containsEntry("street2", null).containsEntry("city", null).containsEntry("phone", null)
                .containsEntry("payment_method", null).containsEntry("payment_handle", null)
                .containsEntry("state", "IL").containsEntry("zip", "62701").containsEntry("country", "US");
        assertThat(row.get("anonymised_at")).isNotNull();
    }

    private void assertKept(UUID id) {
        Map<String, Object> row = addressRow(id);
        assertThat(row).containsEntry("name", "Ash Ketchum").containsEntry("street1", "1 Route St").containsEntry("payment_handle", "@ash");
        assertThat(row.get("anonymised_at")).isNull();
    }

    private static Timestamp ago(int days) {
        return Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
    }
}

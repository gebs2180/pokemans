package com.pokemans.app.notify;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The email_message table: every email, queued, sent or not. Joins the caller's transaction when there is one. */
@Repository
class EmailOutbox {

    enum Status { PENDING, SENDING, SENT, FAILED, SKIPPED, CANCELLED }

    /** What an email is, so its rows can be found again: e.g. reminders to cancel once paid. */
    enum Template { SALE_TAKEN, CARD_ARRIVED, BUYER_PAID, PAYMENT_DUE, PAYMENT_REMINDER, NEW_LISTINGS }

    /** A new email. {@code sendAt} is when it's due; a reminder's is in the future. */
    record New(String dedupeKey, Template template, UUID recipientUserId, String recipientEmail, UUID orgId, UUID listingId,
               EmailContent content, Status status, String statusReason, Instant sendAt, Instant createdAt) {
    }

    /** A row claimed for sending. {@code attempts} counts this one. */
    record Claimed(UUID id, String dedupeKey, Template template, String recipientEmail, UUID listingId, String subject, String html,
                   String text, int attempts) {
    }

    private final JdbcClient jdbc;

    EmailOutbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores it unless an email with its dedupe key already is. Returns whether it was stored. */
    boolean queue(New e) {
        return jdbc.sql("""
                        insert into email_message (id, dedupe_key, template, recipient_user_id, recipient_email, org_id, listing_id,
                          subject, html_body, text_body, status, status_reason, attempts, next_attempt_at, created_at, updated_at)
                        values (:id, :key, :template, :user, :email, :org, :listing, :subject, :html, :text, :status, :reason, 0,
                          :sendAt, :createdAt, :createdAt)
                        on conflict (dedupe_key) do nothing
                        """)
                .param("id", UUID.randomUUID())
                .param("key", e.dedupeKey())
                .param("template", e.template().name())
                .param("user", e.recipientUserId())
                .param("email", e.recipientEmail())
                .param("org", e.orgId())
                .param("listing", e.listingId())
                .param("subject", e.content().subject())
                .param("html", e.content().html())
                .param("text", e.content().text())
                .param("status", e.status().name())
                .param("reason", e.statusReason())
                .param("sendAt", ts(e.sendAt()))
                .param("createdAt", ts(e.createdAt()))
                .update() == 1;
    }

    /**
     * Marks up to {@code limit} due emails SENDING and returns them, in one statement. Rows another worker holds are
     * skipped, so two workers never send the same row.
     */
    List<Claimed> claim(Instant now, int limit) {
        return jdbc.sql("""
                        update email_message set status = 'SENDING', attempts = attempts + 1, updated_at = :now
                        where id in (select id from email_message where status = 'PENDING' and next_attempt_at <= :now
                                     order by next_attempt_at limit :limit for update skip locked)
                        returning id, dedupe_key, template, recipient_email, listing_id, subject, html_body, text_body, attempts
                        """)
                .param("now", ts(now))
                .param("limit", limit)
                .query(EmailOutbox::claimed)
                .list();
    }

    /**
     * Puts back emails left SENDING by a worker that stopped mid-send. Sending one again is safe: Resend answers a repeated
     * idempotency key with the first send.
     */
    int releaseStuck(Instant before) {
        return jdbc.sql("update email_message set status = 'PENDING', updated_at = :before where status = 'SENDING' and updated_at < :before")
                .param("before", ts(before))
                .update();
    }

    void sent(UUID id, String providerId, Instant now) {
        jdbc.sql("""
                        update email_message set status = 'SENT', provider_id = :provider, sent_at = :now, updated_at = :now, last_error = null
                        where id = :id
                        """)
                .param("id", id).param("provider", providerId).param("now", ts(now))
                .update();
    }

    void retry(UUID id, String error, Instant next, Instant now) {
        jdbc.sql("update email_message set status = 'PENDING', last_error = :error, next_attempt_at = :next, updated_at = :now where id = :id")
                .param("id", id).param("error", error).param("next", ts(next)).param("now", ts(now))
                .update();
    }

    void failed(UUID id, String reason, String error, Instant now) {
        jdbc.sql("update email_message set status = 'FAILED', status_reason = :reason, last_error = :error, updated_at = :now where id = :id")
                .param("id", id).param("reason", reason).param("error", error).param("now", ts(now))
                .update();
    }

    void cancel(UUID id, String reason, Instant now) {
        jdbc.sql("update email_message set status = 'CANCELLED', status_reason = :reason, updated_at = :now where id = :id")
                .param("id", id).param("reason", reason).param("now", ts(now))
                .update();
    }

    /** Cancels a listing's emails of the template that haven't gone yet. Returns how many. */
    int cancelPending(UUID listingId, Template template, String reason, Instant now) {
        return jdbc.sql("""
                        update email_message set status = 'CANCELLED', status_reason = :reason, updated_at = :now
                        where listing_id = :listing and template = :template and status = 'PENDING'
                        """)
                .param("listing", listingId).param("template", template.name()).param("reason", reason).param("now", ts(now))
                .update();
    }

    /** When the user's last email of the template for the org was queued, whatever became of it. */
    Optional<Instant> lastQueued(UUID userId, UUID orgId, Template template) {
        return jdbc.sql("""
                        select max(created_at) from email_message
                        where recipient_user_id = :user and org_id = :org and template = :template
                        """)
                .param("user", userId).param("org", orgId).param("template", template.name())
                .query((rs, n) -> rs.getTimestamp(1))
                .list().stream()
                .filter(Objects::nonNull)
                .map(Timestamp::toInstant)
                .findFirst();
    }

    private static Claimed claimed(ResultSet rs, int n) throws SQLException {
        return new Claimed(rs.getObject("id", UUID.class), rs.getString("dedupe_key"), Template.valueOf(rs.getString("template")),
                rs.getString("recipient_email"), rs.getObject("listing_id", UUID.class), rs.getString("subject"), rs.getString("html_body"),
                rs.getString("text_body"), rs.getInt("attempts"));
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}

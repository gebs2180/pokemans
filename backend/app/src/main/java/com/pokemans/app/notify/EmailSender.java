package com.pokemans.app.notify;

import com.pokemans.app.notify.EmailOutbox.Claimed;
import com.pokemans.app.notify.EmailOutbox.Template;
import com.pokemans.app.offers.Deals;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends due emails from the outbox. A failure Resend may get past (no answer, 429, 5xx) is retried after 1, 2, 4…
 * minutes, at most 6 hours apart, up to the configured tries; any other is failed at once. Each try carries the same
 * idempotency key, so an email whose first answer was lost is not sent twice.
 */
class EmailSender {

    static final Duration STUCK_AFTER = Duration.ofMinutes(10);
    static final Duration MAX_BACKOFF = Duration.ofHours(6);
    static final String NOT_OWED = "Paid, returned or cancelled before the reminder was due";

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final EmailOutbox outbox;
    private final ResendClient resend;
    private final Deals deals;
    private final EmailProperties properties;

    EmailSender(EmailOutbox outbox, ResendClient resend, Deals deals, EmailProperties properties) {
        this.outbox = outbox;
        this.resend = resend;
        this.deals = deals;
        this.properties = properties;
    }

    /** Sends what's due. Returns how many went. */
    int sendDue(Instant now) {
        int released = outbox.releaseStuck(now.minus(STUCK_AFTER));
        if (released > 0) {
            log.warn("Put back {} email(s) left sending", released);
        }
        int sent = 0;
        List<Claimed> due = outbox.claim(now, properties.batchSize());
        for (Claimed c : due) {
            if (c.template() == Template.PAYMENT_REMINDER && c.listingId() != null
                    && deals.deal(c.listingId()).map(d -> !d.owed()).orElse(true)) {
                outbox.cancel(c.id(), NOT_OWED, now);
                continue;
            }
            try {
                String providerId = resend.send(new ResendClient.Message(properties.from(), c.recipientEmail(), c.subject(), c.html(), c.text(),
                        c.id().toString()));
                outbox.sent(c.id(), providerId, Instant.now());
                sent++;
            } catch (ResendClient.Failure e) {
                if (e.retryable() && c.attempts() < properties.maxAttempts()) {
                    Instant next = Instant.now().plus(backoff(c.attempts()));
                    outbox.retry(c.id(), e.getMessage(), next, Instant.now());
                    log.warn("Email {} try {} failed, retrying at {}: {}", c.dedupeKey(), c.attempts(), next, e.getMessage());
                } else {
                    outbox.failed(c.id(), e.retryable() ? "Gave up after " + c.attempts() + " tries" : "Refused by the provider", e.getMessage(),
                            Instant.now());
                    log.error("Email {} failed for good after {} tries: {}", c.dedupeKey(), c.attempts(), e.getMessage());
                }
            }
        }
        return sent;
    }

    /** 1 minute after the first try, doubling, at most {@link #MAX_BACKOFF}. */
    static Duration backoff(int attempts) {
        int doublings = Math.min(Math.max(attempts - 1, 0), 20);
        Duration d = Duration.ofMinutes(1L << doublings);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}

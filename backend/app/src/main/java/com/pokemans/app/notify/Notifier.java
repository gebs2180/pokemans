package com.pokemans.app.notify;

import com.pokemans.app.identity.Directory;
import com.pokemans.app.notify.EmailOutbox.Status;
import com.pokemans.app.notify.EmailOutbox.Template;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Queues an email to a user, in the caller's transaction. The address is the user's login email as last synced from
 * Auth0. Every email is stored: one to a user with no email on file, or while sending is off, is stored as skipped
 * with that reason.
 */
@Component
class Notifier {

    static final String NO_EMAIL = "no email on file";
    static final String SENDING_OFF = "sending is off";

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final EmailOutbox outbox;
    private final Directory directory;
    private final EmailProperties properties;

    Notifier(EmailOutbox outbox, Directory directory, EmailProperties properties) {
        this.outbox = outbox;
        this.directory = directory;
        this.properties = properties;
    }

    /** Returns whether it was queued; false if this dedupe key already was. */
    boolean queue(String dedupeKey, Template template, UUID userId, UUID orgId, UUID listingId, EmailContent content, Instant sendAt,
                  Instant now) {
        String email = userId == null ? null : blankToNull(directory.email(userId));
        Status status = Status.PENDING;
        String reason = null;
        if (email == null) {
            status = Status.SKIPPED;
            reason = NO_EMAIL;
        } else if (!properties.enabled()) {
            status = Status.SKIPPED;
            reason = SENDING_OFF;
        }
        boolean queued = outbox.queue(new EmailOutbox.New(dedupeKey, template, userId, email, orgId, listingId, content, status, reason,
                sendAt, now));
        if (queued) {
            // The key names the template and ids only: no address, no content.
            log.info("Email {} {}{}", dedupeKey, status, reason == null ? "" : " (" + reason + ")");
        }
        return queued;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

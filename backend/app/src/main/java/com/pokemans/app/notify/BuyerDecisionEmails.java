package com.pokemans.app.notify;

import com.pokemans.app.identity.Buying;
import com.pokemans.app.notify.EmailOutbox.Template;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails a person once when the admin approves or declines their request to buy (UX review finding 6, Zach,
 * 2026-11-23). A decline carries the admin's note. Queued in the transaction that recorded the answer, so the email
 * exists exactly when the answer does; one per request, ever.
 */
@Component
class BuyerDecisionEmails {

    private static final Logger log = LoggerFactory.getLogger(BuyerDecisionEmails.class);
    private static final String FOOTER = "You're getting this because you asked to buy on Rawmark. Emails about your own requests always go out.";

    private final Notifier notifier;
    private final EmailProperties properties;

    BuyerDecisionEmails(Notifier notifier, EmailProperties properties) {
        this.notifier = notifier;
        this.properties = properties;
    }

    /** Never fails the answer it's about: a missed email is logged. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(Buying.Decided event) {
        try {
            Instant now = Instant.now();
            notifier.queue("BUYER_DECISION:" + event.requestId(), Template.BUYER_DECISION, event.userId(), event.orgId(), null,
                    content(event), now, now);
        } catch (RuntimeException e) {
            log.error("Couldn't queue the buyer decision email for request {}", event.requestId(), e);
        }
    }

    EmailContent content(Buying.Decided event) {
        String account = properties.link("/account");
        if (event.approved()) {
            return EmailContent.of("You can buy on Rawmark now",
                    List.of("We approved your request to buy.", "The market is open to you: you can take cards at their firm prices."),
                    "Open the market", account, FOOTER);
        }
        return EmailContent.of("About your request to buy on Rawmark",
                List.of("We couldn't approve your request to buy.", "Why: " + event.note(),
                        "You can fix that and ask again from your account page."),
                "Your account", account, FOOTER);
    }
}

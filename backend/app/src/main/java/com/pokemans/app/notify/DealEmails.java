package com.pokemans.app.notify;

import com.pokemans.app.notify.EmailOutbox.Template;
import com.pokemans.app.offers.DealStepRecorded;
import com.pokemans.app.offers.Deals;
import com.pokemans.app.offers.Deals.Deal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails each side of a sale as it moves: the seller when a buyer takes the card, when it arrives and when the buyer
 * says they paid; the buyer when payment is due, and once more two days before the deadline. Queued just before the
 * step commits, in its transaction, so an email exists exactly when its step does; the worker sends it after commit.
 */
@Component
class DealEmails {

    /** How long before the payment deadline the reminder goes. */
    static final Duration REMIND_BEFORE = Duration.ofDays(2);

    private static final Logger log = LoggerFactory.getLogger(DealEmails.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US).withZone(ZoneOffset.UTC);
    private static final String DEAL_FOOTER = "You're getting this because you're part of this sale on Rawmark. Emails about your own sales always go out.";

    private final Deals deals;
    private final Notifier notifier;
    private final EmailOutbox outbox;
    private final EmailProperties properties;

    DealEmails(Deals deals, Notifier notifier, EmailOutbox outbox, EmailProperties properties) {
        this.deals = deals;
        this.notifier = notifier;
        this.outbox = outbox;
        this.properties = properties;
    }

    /** Never fails the step it's about: a missed email is logged, the sale goes on. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(DealStepRecorded event) {
        try {
            handle(event, Instant.now());
        } catch (RuntimeException e) {
            log.error("Couldn't queue the email for {} on listing {}", event.step(), event.listingId(), e);
        }
    }

    void handle(DealStepRecorded event, Instant now) {
        switch (event.step()) {
            case TAKEN -> deals.deal(event.listingId()).ifPresent(d -> taken(d, now));
            case ARRIVED -> deals.deal(event.listingId()).ifPresent(d -> arrived(d, now));
            case PAID -> {
                cancelReminder(event, "The buyer recorded paying", now);
                deals.deal(event.listingId()).ifPresent(d -> paid(d, now));
            }
            case PAYMENT_CONFIRMED -> cancelReminder(event, "The seller confirmed payment", now);
            case RETURNED -> cancelReminder(event, "The card was returned", now);
            case CANCELLED -> cancelReminder(event, "The deal was cancelled", now);
        }
    }

    private void taken(Deal d, Instant now) {
        String title = d.card().title();
        EmailContent content = EmailContent.of("Sold: " + title + " for " + money(d.price()),
                List.of("A buyer took your " + title + " at " + money(d.price()) + ".",
                        "Your prepaid, insured shipping label is on the card's page in Rawmark. Print it, pack the card in a "
                                + "toploader inside a bubble mailer, and ship it within 3 days.",
                        "The buyer pays you directly within 7 days of the card arriving as shown."),
                "Get your label", properties.link("/orgs/" + d.sellerOrgId() + "/cards/" + d.cardItemId()), DEAL_FOOTER);
        notifier.queue(key(Template.SALE_TAKEN, d), Template.SALE_TAKEN, d.sellerUserId(), d.sellerOrgId(), d.listingId(), content, now, now);
    }

    private void arrived(Deal d, Instant now) {
        String title = d.card().title();
        String payBy = d.payBy() == null ? "within 7 days" : "by " + DAY.format(d.payBy());
        notifier.queue(key(Template.CARD_ARRIVED, d), Template.CARD_ARRIVED, d.sellerUserId(), d.sellerOrgId(), d.listingId(),
                EmailContent.of("Arrived: " + title,
                        List.of("Your " + title + " arrived genuine and as shown.",
                                "They owe you " + money(d.price()) + ", due " + payBy + " (UTC). We'll email you when they say they've paid."),
                        "See the sale", properties.link("/orgs/" + d.sellerOrgId() + "/cards/" + d.cardItemId()), DEAL_FOOTER),
                now, now);
        String dealsLink = properties.link("/orgs/" + d.buyerOrgId() + "/deals");
        notifier.queue(key(Template.PAYMENT_DUE, d), Template.PAYMENT_DUE, d.buyerUserId(), d.buyerOrgId(), d.listingId(),
                EmailContent.of("Payment due " + payBy + ": " + title,
                        List.of("You recorded the " + title + " as arrived genuine and as shown.",
                                "Pay the seller " + money(d.price()) + " " + payBy + " (UTC), then record the payment in Rawmark."),
                        "Record payment", dealsLink, DEAL_FOOTER),
                now, now);
        if (d.payBy() != null) {
            Instant remindAt = d.payBy().minus(REMIND_BEFORE);
            notifier.queue(key(Template.PAYMENT_REMINDER, d), Template.PAYMENT_REMINDER, d.buyerUserId(), d.buyerOrgId(), d.listingId(),
                    EmailContent.of("Reminder: payment due " + payBy + ": " + title,
                            List.of("You haven't recorded paying for the " + title + " yet.",
                                    "Pay the seller " + money(d.price()) + " " + payBy + " (UTC), then record the payment in Rawmark. "
                                            + "If you've already paid, record it so the seller knows."),
                            "Record payment", dealsLink, DEAL_FOOTER),
                    remindAt.isAfter(now) ? remindAt : now, now);
        }
    }

    private void paid(Deal d, Instant now) {
        String title = d.card().title();
        BigDecimal amount = d.pricePaid() == null ? d.price() : d.pricePaid();
        notifier.queue(key(Template.BUYER_PAID, d), Template.BUYER_PAID, d.sellerUserId(), d.sellerOrgId(), d.listingId(),
                EmailContent.of("Paid: " + title,
                        List.of("The buyer says they paid you " + money(amount) + " for the " + title + ".",
                                "Once the money is in your account, confirm it on the card's page. If it isn't, say so there and we'll look into it."),
                        "Confirm payment", properties.link("/orgs/" + d.sellerOrgId() + "/cards/" + d.cardItemId()), DEAL_FOOTER),
                now, now);
    }

    private void cancelReminder(DealStepRecorded event, String reason, Instant now) {
        outbox.cancelPending(event.listingId(), Template.PAYMENT_REMINDER, reason, now);
    }

    /** One of each email per listing, ever. */
    private static String key(Template template, Deal d) {
        return template.name() + ":" + d.listingId();
    }

    static String money(BigDecimal amount) {
        return "$" + Optional.ofNullable(amount).orElse(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

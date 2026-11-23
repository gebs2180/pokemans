package com.pokemans.app.notify;

import com.pokemans.app.identity.Directory;
import com.pokemans.app.identity.OrgType;
import com.pokemans.app.notify.EmailOutbox.Template;
import com.pokemans.app.offers.OfferReadiness;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * One email per card when its offer is ready, to whoever added it (review finding 1; Zach, 2026-11-23). Only a
 * collector hears: a contributor isn't there to sell. The dedupe key makes it once per card, ever; a sweep rather than
 * an event, since a card becomes ready by several routes (identified, assessed, comps added).
 */
@Service
class OfferReadyEmails {

    /** Cards photographed before this shipped already showed their price on screen: they get no email. */
    static final Instant FROM = Instant.parse("2026-11-24T00:00:00Z");

    /** Comps may take 14 days; a card photographed longer ago than this is ready already or gets no offer. */
    static final Duration LOOKBACK = Duration.ofDays(15);

    private final OfferReadiness readiness;
    private final Directory directory;
    private final Notifier notifier;
    private final EmailProperties properties;

    OfferReadyEmails(OfferReadiness readiness, Directory directory, Notifier notifier, EmailProperties properties) {
        this.readiness = readiness;
        this.directory = directory;
        this.notifier = notifier;
        this.properties = properties;
    }

    /** Queues an email for each newly ready card. Returns how many were queued. */
    int run(Instant now) {
        Instant since = now.minus(LOOKBACK).isAfter(FROM) ? now.minus(LOOKBACK) : FROM;
        if (!since.isBefore(now)) {
            return 0;
        }
        List<OfferReadiness.Ready> ready = readiness.photographedBetween(since, now);
        if (ready.isEmpty()) {
            return 0;
        }
        Set<UUID> collectors = new HashSet<>(directory.orgsOfType(OrgType.COLLECTOR));
        int queued = 0;
        for (OfferReadiness.Ready r : ready) {
            if (r.addedBy() == null || !collectors.contains(r.orgId())) {
                continue;
            }
            if (notifier.queue(key(r.cardId()), Template.OFFER_READY, r.addedBy(), r.orgId(), null, content(r), now, now)) {
                queued++;
            }
        }
        return queued;
    }

    static String key(UUID cardId) {
        return Template.OFFER_READY.name() + ":" + cardId;
    }

    private EmailContent content(OfferReadiness.Ready r) {
        String link = properties.link("/orgs/" + r.orgId() + "/cards/" + r.cardId());
        return EmailContent.of("Your offer is ready: " + r.title(),
                List.of("We've finished checking " + r.title() + ", and its offer is ready.",
                        "Open the card to see our price. It's yours to take or leave."),
                "See the offer", link,
                "You get one email per card, when its offer is ready.");
    }
}

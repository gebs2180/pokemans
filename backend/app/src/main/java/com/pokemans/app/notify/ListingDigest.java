package com.pokemans.app.notify;

import com.pokemans.app.identity.Directory;
import com.pokemans.app.identity.OrgAccess;
import com.pokemans.app.identity.OrgType;
import com.pokemans.app.notify.EmailOutbox.Template;
import com.pokemans.app.offers.Deals;
import com.pokemans.app.offers.Deals.Listing;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily email to each member of each buyer org: cards posted since their last one, only when there are some. A
 * buyer never hears about a card from an org they're also in, since they couldn't buy it. At most one a day per member
 * and org; the first one looks back a day.
 */
@Service
class ListingDigest {

    static final Duration FIRST_LOOKBACK = Duration.ofDays(1);
    private static final int MAX_LISTED = 20;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneOffset.UTC);

    private final Directory directory;
    private final OrgAccess orgAccess;
    private final Deals deals;
    private final EmailOutbox outbox;
    private final EmailPreferences preferences;
    private final Notifier notifier;
    private final EmailProperties properties;

    ListingDigest(Directory directory, OrgAccess orgAccess, Deals deals, EmailOutbox outbox, EmailPreferences preferences, Notifier notifier,
                  EmailProperties properties) {
        this.directory = directory;
        this.orgAccess = orgAccess;
        this.deals = deals;
        this.outbox = outbox;
        this.preferences = preferences;
        this.notifier = notifier;
        this.properties = properties;
    }

    /** Queues today's digests. Returns how many were queued. */
    @Transactional
    int run(Instant now) {
        String date = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        int queued = 0;
        for (UUID buyerOrgId : directory.orgsOfType(OrgType.BUYER)) {
            Map<UUID, Boolean> sharesMember = new HashMap<>();
            for (UUID userId : directory.members(buyerOrgId)) {
                if (!preferences.digest(userId)) {
                    continue;
                }
                Instant since = outbox.lastQueued(userId, buyerOrgId, Template.NEW_LISTINGS).orElse(now.minus(FIRST_LOOKBACK));
                List<Listing> fresh = new ArrayList<>();
                for (Listing l : deals.openPostedSince(since, now)) {
                    if (!sharesMember.computeIfAbsent(l.sellerOrgId(), seller -> orgAccess.shareMember(buyerOrgId, seller))) {
                        fresh.add(l);
                    }
                }
                if (fresh.isEmpty()) {
                    continue;
                }
                String key = Template.NEW_LISTINGS.name() + ":" + userId + ":" + buyerOrgId + ":" + date;
                if (notifier.queue(key, Template.NEW_LISTINGS, userId, buyerOrgId, null, content(buyerOrgId, fresh), now, now)) {
                    queued++;
                }
            }
        }
        return queued;
    }

    private EmailContent content(UUID buyerOrgId, List<Listing> fresh) {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add(fresh.size() == 1 ? "1 new card is on the Rawmark market:" : fresh.size() + " new cards are on the Rawmark market:");
        StringBuilder list = new StringBuilder();
        for (Listing l : fresh.subList(0, Math.min(MAX_LISTED, fresh.size()))) {
            if (!list.isEmpty()) {
                list.append("\n");
            }
            list.append("• ").append(l.card().title()).append(": ").append(DealEmails.money(l.price()))
                    .append(" (until ").append(DAY.format(l.expiresAt())).append(")");
        }
        if (fresh.size() > MAX_LISTED) {
            list.append("\n…and ").append(fresh.size() - MAX_LISTED).append(" more.");
        }
        paragraphs.add(list.toString());
        paragraphs.add("The first buyer to accept gets the card.");
        String market = properties.link("/orgs/" + buyerOrgId + "/market");
        return EmailContent.of(fresh.size() == 1 ? "1 new card on Rawmark" : fresh.size() + " new cards on Rawmark", paragraphs,
                "See the market", market,
                "You get this daily email as a buyer on Rawmark, only on days with new cards. Turn it off on the Market page: " + market);
    }
}

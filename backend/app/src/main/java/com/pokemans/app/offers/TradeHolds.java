package com.pokemans.app.offers;

import com.pokemans.app.offers.ListingStateMachine.Progress;
import com.pokemans.app.offers.ListingStateMachine.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What keeps a card from being hard-deleted by the admin: money or a trade still in play. A delete must never pull a
 * card out from under a buyer, so a live offer, a card on the market and a deal not yet closed or cancelled all hold
 * it. The definitions are the state machine's, so they match what the deal pages show.
 */
@Service
public class TradeHolds {

    /** Why a card can't be deleted yet. */
    public enum Reason { LIVE_OFFER, ON_THE_MARKET, DEAL_IN_PROGRESS, BOUGHT_FROM_ANOTHER_ORG }

    /** One thing holding a card, in words for the admin. */
    public record Hold(UUID cardItemId, UUID listingId, Reason reason, String message) {
    }

    private final CardOfferRepository offers;
    private final CardOfferVoidRepository voids;
    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final ListingLock lock;
    private final JdbcTemplate jdbc;

    TradeHolds(CardOfferRepository offers, CardOfferVoidRepository voids, SaleListingRepository listings, ListingEventRepository events,
               ListingLock lock, JdbcTemplate jdbc) {
        this.offers = offers;
        this.voids = voids;
        this.listings = listings;
        this.events = events;
        this.lock = lock;
        this.jdbc = jdbc;
    }

    /**
     * Takes each card's listing lock (the one posting, taking and every deal step take) until the caller's transaction
     * ends, so nothing can be posted or taken between the check and the delete. In card order, so two deletes can't
     * deadlock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Collection<UUID> cardItemIds) {
        cardItemIds.stream().sorted().forEach(lock::lock);
    }

    /** What holds these cards, as their seller's: an offer, a listing or a deal of their own. */
    @Transactional(readOnly = true)
    public List<Hold> onCards(Collection<UUID> cardItemIds) {
        Instant now = Instant.now();
        List<Hold> holds = new ArrayList<>();
        for (UUID cardId : cardItemIds) {
            List<CardOffer> cardOffers = offers.findByCardItemId(cardId);
            if (cardOffers.isEmpty()) {
                continue;
            }
            Set<UUID> offerIds = cardOffers.stream().map(CardOffer::getId).collect(Collectors.toSet());
            Set<UUID> voided = voids.findByOfferIdIn(offerIds).stream().map(CardOfferVoid::getOfferId).collect(Collectors.toSet());
            List<SaleListing> cardListings = listings.findByOfferIdIn(offerIds);
            Map<UUID, List<ListingEvent>> history = events.history(cardListings);
            Set<UUID> takenOffers = new java.util.HashSet<>();
            for (SaleListing l : cardListings) {
                List<ListingEvent> h = history.getOrDefault(l.getId(), List.of());
                Status status = ListingStateMachine.status(l, h, now);
                if (status == Status.OPEN) {
                    holds.add(new Hold(cardId, l.getId(), Reason.ON_THE_MARKET,
                            "It's on the market until " + l.getExpiresAt() + ": void its offer first."));
                }
                if (status.taken() || status == Status.CANCELLED) {
                    takenOffers.add(l.getOfferId());
                }
                if (status.taken() && !closed(h)) {
                    holds.add(new Hold(cardId, l.getId(), Reason.DEAL_IN_PROGRESS,
                            "A buyer took it and the deal isn't closed: it closes when the seller confirms payment or has the card "
                                    + "back with nothing waiting on the admin, or when the admin cancels it."));
                }
            }
            for (CardOffer o : cardOffers) {
                boolean listed = cardListings.stream().anyMatch(l -> l.getOfferId().equals(o.getId())
                        && ListingStateMachine.status(l, history.getOrDefault(l.getId(), List.of()), now) == Status.OPEN);
                if (!voided.contains(o.getId()) && o.getExpiresAt().isAfter(now) && !takenOffers.contains(o.getId()) && !listed) {
                    holds.add(new Hold(cardId, null, Reason.LIVE_OFFER,
                            "It has a live offer until " + o.getExpiresAt() + ": void the offer first."));
                }
            }
        }
        return holds;
    }

    /**
     * Other orgs' cards this org took, even in a deal since cancelled. Deleting the buyer would leave the seller's deal,
     * label or outcome pointing at nobody, so these hold the org: delete those cards first (they go with their deals),
     * or leave the org.
     */
    @Transactional(readOnly = true)
    public List<Hold> asBuyer(UUID buyerOrgId) {
        return jdbc.query("""
                select l.id, l.card_item_id from sale_listing l
                where l.org_id <> ?
                  and (exists (select 1 from listing_event e where e.listing_id = l.id and e.actor_org_id = ?)
                    or exists (select 1 from shipping_label s where s.listing_id = l.id and s.buyer_org_id = ?)
                    or exists (select 1 from deal_outcome d where d.listing_id = l.id and d.buyer_org_id = ?))
                order by l.id""",
                (rs, i) -> {
                    UUID card = rs.getObject(2, UUID.class);
                    return new Hold(card, rs.getObject(1, UUID.class), Reason.BOUGHT_FROM_ANOTHER_ORG,
                            "This org took card " + card + " from another org: delete that card first.");
                }, buyerOrgId, buyerOrgId, buyerOrgId, buyerOrgId);
    }

    /**
     * A deal is closed once the seller confirmed payment, or the card is back with them, and nothing on it waits on the
     * admin (an open dispute or an unreviewed return). A cancelled deal isn't taken, so it never gets here.
     */
    static boolean closed(List<ListingEvent> history) {
        Progress progress = ListingStateMachine.progress(history);
        if (progress == null) {
            return true;
        }
        boolean settled = progress.confirmedAt() != null || progress.stage() == ListingStateMachine.DealStage.BACK;
        return settled && ListingStateMachine.awaitingAdmin(history) == null;
    }
}

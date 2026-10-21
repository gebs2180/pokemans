package com.pokemans.app.offers;

import com.pokemans.app.intake.CardItems;
import com.pokemans.app.valuation.CardInputs;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where a seller's card is on the way to a sale, and what happens next, so the card list and page
 * never leave the collector at a dead end. No access checks: the controllers have done them.
 */
@Service
class CardStatusService {

    /** In order: photos, then our review (identity, condition, prices), then the offer and the sale. */
    enum Stage { PHOTOS, LOT, IDENTIFYING, ASSESSING, PRICING, NO_OFFER, READY, OFFERED, LISTED, SOLD }

    /**
     * @param identity  the card as we identified it, e.g. "Charizard · Base Set 4/102 · EN · holo", or null
     * @param nextStep  what happens next, and who does it, in words for the seller
     * @param canOffer  true when asking for an offer can work right now
     */
    record CardStatus(UUID cardId, Stage stage, String identity, String nextStep, boolean canOffer) {
    }

    private static final String NO_NOTIFICATIONS = " We don't send notifications yet, so check back here.";

    private final OfferService offers;
    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final CardItems cardItems;
    private final CardOfferVoidRepository voids;

    CardStatusService(OfferService offers, SaleListingRepository listings, ListingEventRepository events, CardItems cardItems,
                      CardOfferVoidRepository voids) {
        this.offers = offers;
        this.voids = voids;
        this.listings = listings;
        this.events = events;
        this.cardItems = cardItems;
    }

    @Transactional(readOnly = true)
    CardStatus status(UUID cardId) {
        CardItems.CardItemRef card = cardItems.get(cardId);
        if (card.lot()) {
            return new CardStatus(cardId, Stage.LOT, null, "Lots don't get offers: add the cards worth $25 or more one by one.", false);
        }
        Optional<SaleListing> listing = listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardId);
        ListingService.Status listed = listing.map(l -> ListingService.status(l, events.findByListingIdInOrderByCreatedAtAsc(List.of(l.getId())),
                Instant.now())).orElse(null);
        OfferService.Quote quote = offers.quote(cardId);
        CardInputs in = quote.value().inputs();
        String identity = identity(in.identity());
        if (listed == ListingService.Status.ACCEPTED) {
            return new CardStatus(cardId, Stage.SOLD, identity, "A buyer took it: ship it with the prepaid label once it shows on this card's page.", false);
        }
        if (listed == ListingService.Status.OPEN) {
            return new CardStatus(cardId, Stage.LISTED, identity, "Posted to buyers: the first to accept buys it at the price.", false);
        }
        Optional<CardOffer> latest = offers.latest(cardId).filter(o -> o.getExpiresAt().isAfter(Instant.now()))
                .filter(o -> voids.findByOfferId(o.getId()).isEmpty());
        if (latest.isPresent()) {
            return new CardStatus(cardId, Stage.OFFERED, identity, "You have an offer: sell at it, or let it expire.", false);
        }
        List<CardItems.PhotoRef> photos = cardItems.passedPhotos(cardId);
        boolean captured = photos.stream().anyMatch(p -> p.side().equals("FRONT")) && photos.stream().anyMatch(p -> p.side().equals("BACK"));
        if (!captured) {
            return new CardStatus(cardId, Stage.PHOTOS, identity, "Take the front and back photos. We review the card from them.", false);
        }
        String reason = in.unvaluedReason();
        if (in.identity() == null) {
            return new CardStatus(cardId, Stage.IDENTIFYING, null,
                    "We identify each card by hand from its photos, usually within a couple of days." + NO_NOTIFICATIONS, false);
        }
        if (reason != null && reason.equals("no condition assessment")) {
            return new CardStatus(cardId, Stage.ASSESSING, identity,
                    "We're reviewing its condition from the photos." + NO_NOTIFICATIONS, false);
        }
        if (reason != null) {
            return new CardStatus(cardId, Stage.PRICING, identity,
                    "We're gathering sold prices for this card before we can price it." + NO_NOTIFICATIONS, false);
        }
        if (quote.refusal() != null) {
            return new CardStatus(cardId, Stage.NO_OFFER, identity, quote.refusal() + nextStep(quote.refusal()), false);
        }
        return new CardStatus(cardId, Stage.READY, identity, "Ready for an instant offer.", true);
    }

    private static String nextStep(String refusal) {
        if (refusal.contains("sales data")) {
            return " We add sold prices as they come in, and it may qualify then." + NO_NOTIFICATIONS;
        }
        if (refusal.contains("minimum")) {
            return " Instant offers are for cards worth $25 or more.";
        }
        return "";
    }

    static String identity(CardInputs.Identity id) {
        if (id == null) {
            return null;
        }
        StringBuilder s = new StringBuilder(id.name()).append(" · ").append(id.setName()).append(' ').append(id.number())
                .append(" · ").append(id.language().toUpperCase(java.util.Locale.ROOT));
        if (!"UNLIMITED".equals(id.edition())) {
            s.append(" · ").append(id.edition().replace('_', ' ').toLowerCase(java.util.Locale.ROOT));
        }
        if (!"NORMAL".equals(id.variant())) {
            s.append(" · ").append(id.variant().replace('_', ' ').toLowerCase(java.util.Locale.ROOT));
        }
        return s.toString();
    }
}

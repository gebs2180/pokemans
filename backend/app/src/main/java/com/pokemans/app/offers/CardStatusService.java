package com.pokemans.app.offers;

import com.pokemans.app.condition.Assessments;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.valuation.CardInputs;
import java.time.Duration;
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
    enum Stage { PHOTOS, LOT, IDENTIFYING, ASSESSING, PRICING, NO_OFFER, READY, OFFERED, LISTED, SOLD, SHIPPED, ARRIVED, PAID, RETURNED }

    /**
     * @param identity  the card as we identified it, e.g. "Charizard · Base Set 4/102 · EN · holo", or null
     * @param nextStep  what happens next, and who does it, in words for the seller
     * @param canOffer  true when asking for an offer can work right now
     * @param condition the grades the card likely is, in our view, once we've assessed it, and our centering
     *                  measurement if there is one; else null
     * @param deadline  when the stage runs out: the seller's last day to ship, or when the offer or posting ends; else null
     */
    record CardStatus(UUID cardId, Stage stage, String identity, String nextStep, boolean canOffer, Assessments.Summary condition,
                      Instant deadline) {

        CardStatus(UUID cardId, Stage stage, String identity, String nextStep, boolean canOffer) {
            this(cardId, stage, identity, nextStep, canOffer, null, null);
        }

        CardStatus withCondition(Assessments.Summary summary) {
            return new CardStatus(cardId, stage, identity, nextStep, canOffer, summary, deadline);
        }

        CardStatus withDeadline(Instant at) {
            return new CardStatus(cardId, stage, identity, nextStep, canOffer, condition, at);
        }
    }

    /** What's waiting on the seller, for the home page. Everything else on the way to a sale is waiting on us or a buyer. */
    enum Need { LABEL_READY, OFFER_EXPIRING, OFFER_READY, GET_OFFER, RETAKE }

    /**
     * @param due   when it runs out, or null
     * @param sides for a retake, the sides to take again
     */
    record NeedsYou(UUID cardId, String label, Need need, Instant due, List<String> sides) {
    }

    /** An offer this close to ending says so, on top of saying it's ready. */
    static final Duration EXPIRING_SOON = Duration.ofHours(48);

    private static final String NO_NOTIFICATIONS = " We don't send notifications yet, so check back here.";

    private final OfferService offers;
    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final CardItems cardItems;
    private final CardOfferVoidRepository voids;
    private final Assessments assessments;
    private final Shipping shipping;

    CardStatusService(OfferService offers, SaleListingRepository listings, ListingEventRepository events, CardItems cardItems,
                      CardOfferVoidRepository voids, Assessments assessments, Shipping shipping) {
        this.offers = offers;
        this.shipping = shipping;
        this.assessments = assessments;
        this.voids = voids;
        this.listings = listings;
        this.events = events;
        this.cardItems = cardItems;
    }

    @Transactional(readOnly = true)
    CardStatus status(UUID cardId) {
        CardStatus status = stage(cardId);
        return status.stage() == Stage.LOT ? status : status.withCondition(assessments.summary(cardId).orElse(null));
    }

    /**
     * The org's cards that are waiting on the seller. Only a collector is nudged about offers: a contributor
     * isn't there to sell, but still ships what they sold and retakes what failed.
     */
    @Transactional(readOnly = true)
    List<NeedsYou> needsYou(UUID orgId, boolean selling) {
        Instant now = Instant.now();
        return cardItems.inCollectionsOf(orgId).stream().map(card -> {
            CardStatus status = stage(card.id());
            List<String> sides = status.stage() == Stage.PHOTOS ? cardItems.sidesToRetake(card.id()) : List.of();
            Need need = need(status, !sides.isEmpty(), now);
            boolean wanted = need != null && (selling || need == Need.LABEL_READY || need == Need.RETAKE);
            return wanted ? new NeedsYou(card.id(), card.label(), need, status.deadline(), sides) : null;
        }).filter(java.util.Objects::nonNull).toList();
    }

    /** What a card in this state needs from its seller, or null. A sold card needs them until they say it shipped, or an arrival shows it did. */
    static Need need(CardStatus status, boolean retake, Instant now) {
        return switch (status.stage()) {
            case SOLD -> status.deadline() == null ? null : Need.LABEL_READY;
            case OFFERED -> status.deadline().isBefore(now.plus(EXPIRING_SOON)) ? Need.OFFER_EXPIRING : Need.OFFER_READY;
            case READY -> Need.GET_OFFER;
            case PHOTOS -> retake ? Need.RETAKE : null;
            default -> null;
        };
    }

    private CardStatus stage(UUID cardId) {
        CardItems.CardItemRef card = cardItems.get(cardId);
        if (card.lot()) {
            return new CardStatus(cardId, Stage.LOT, null, "Lots don't get offers: add the cards worth $25 or more one by one.", false);
        }
        Optional<SaleListing> listing = listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardId);
        List<ListingEvent> history = listing.map(l -> events.findByListingIdInOrderByCreatedAtAsc(List.of(l.getId()))).orElse(List.of());
        ListingService.Status listed = listing.map(l -> ListingService.status(l, history, Instant.now())).orElse(null);
        OfferService.Quote quote = offers.quote(cardId);
        CardInputs in = quote.value().inputs();
        String identity = identity(in.identity());
        ListingService.Progress deal = ListingService.progress(history);
        if (listed == ListingService.Status.ACCEPTED) {
            return afterTheSale(cardId, identity, deal, listing.orElseThrow().getId());
        }
        if (listed == ListingService.Status.BACK && deal.held()) {
            return new CardStatus(cardId, Stage.RETURNED, identity, ListingService.HELD + NO_NOTIFICATIONS, false);
        }
        if (listed == ListingService.Status.OPEN) {
            return new CardStatus(cardId, Stage.LISTED, identity, "Posted to buyers: the first to accept buys it at the price.", false)
                    .withDeadline(listing.orElseThrow().getExpiresAt());
        }
        // A card that came back can't go out again on the offer it sold at.
        UUID soldAt = listed == ListingService.Status.BACK ? listing.get().getOfferId() : null;
        Optional<CardOffer> latest = offers.latest(cardId).filter(o -> o.getExpiresAt().isAfter(Instant.now()))
                .filter(o -> voids.findByOfferId(o.getId()).isEmpty()).filter(o -> !o.getId().equals(soldAt));
        if (latest.isPresent()) {
            return new CardStatus(cardId, Stage.OFFERED, identity, "You have an offer: sell at it, or let it expire.", false)
                    .withDeadline(latest.orElseThrow().getExpiresAt());
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
                    "We don't have enough sold prices for this card yet. We add them by hand, so it may take a while, "
                            + "and some cards won't get an offer. You're free to sell it elsewhere." + NO_NOTIFICATIONS, false);
        }
        if (quote.refusal() != null) {
            return new CardStatus(cardId, Stage.NO_OFFER, identity, quote.refusal() + nextStep(quote.refusal()), false);
        }
        return new CardStatus(cardId, Stage.READY, identity, "Ready for an instant offer.", true);
    }

    private CardStatus afterTheSale(UUID cardId, String identity, ListingService.Progress deal, UUID listingId) {
        return switch (deal.stage()) {
            case SHIPPED -> new CardStatus(cardId, Stage.SHIPPED, identity,
                    "On its way to the buyer. They record it when it arrives, and then have 7 days to pay you." + NO_NOTIFICATIONS, false);
            case ARRIVED -> new CardStatus(cardId, Stage.ARRIVED, identity,
                    "It arrived. The buyer pays you directly: tell us here when they have, or if they haven't by the date on this page.", false);
            case PAID -> new CardStatus(cardId, Stage.PAID, identity, deal.confirmedAt() != null ? "Paid. Nothing more to do."
                    : "The buyer says they paid you. Tell us here whether you got it.", false);
            case RETURNED -> new CardStatus(cardId, Stage.RETURNED, identity,
                    "The buyer is sending it back. Tell us here once you have it.", false);
            default -> new CardStatus(cardId, Stage.SOLD, identity, "A buyer took it: ship it with the prepaid label once it shows on this card's page.", false)
                    .withDeadline(shipping.label(listingId).map(l -> l.getCreatedAt().plus(ListingService.SHIP_WITHIN)).orElse(null));
        };
    }

    private static String nextStep(String refusal) {
        if (refusal.contains("sales data")) {
            return " We add sold prices by hand, and it may qualify then." + NO_NOTIFICATIONS;
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

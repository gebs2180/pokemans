package com.pokemans.app.offers;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.catalog.IdentifyWaits;
import com.pokemans.app.condition.Assessments;
import com.pokemans.app.condition.PreGradeFailures;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.valuation.CardInputs;
import com.pokemans.app.valuation.UnvaluedReason;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
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
    enum Stage { PHOTOS, LOT, IDENTIFYING, ASSESSING, PRICING, NO_OFFER, OVER_LIMIT, READY, OFFERED, LISTED, SOLD, SHIPPED, ARRIVED, PAID, RETURNED }

    /**
     * @param identity  the card as we identified it, e.g. "Charizard · Base Set 4/102 · EN · holo", or null
     * @param nextStep  what happens next, and who does it, in words for the seller
     * @param canOffer  true when asking for an offer can work right now
     * @param condition the grades the card likely is, in our view, once we've assessed it, and our centering
     *                  measurement if there is one; else null
     * @param deadline  when the stage runs out: the seller's last day to ship, the buyer's last day to pay, or when the
     *                  offer or posting ends; else null
     * @param sellersWord true while the card waits on the seller saying something: after a sale, that they were paid or
     *                    the card's back; while identifying, which card it is
     * @param waitingSince while the card waits on us (identifying, assessing, pricing), since when; else null
     */
    record CardStatus(UUID cardId, Stage stage, String identity, String nextStep, boolean canOffer, Assessments.Summary condition,
                      Instant deadline, boolean sellersWord, Instant waitingSince) {

        CardStatus(UUID cardId, Stage stage, String identity, String nextStep, boolean canOffer) {
            this(cardId, stage, identity, nextStep, canOffer, null, null, false, null);
        }

        CardStatus withCondition(Assessments.Summary summary) {
            return new CardStatus(cardId, stage, identity, nextStep, canOffer, summary, deadline, sellersWord, waitingSince);
        }

        CardStatus withDeadline(Instant at) {
            return new CardStatus(cardId, stage, identity, nextStep, canOffer, condition, at, sellersWord, waitingSince);
        }

        CardStatus withSellersWord(boolean waiting) {
            return new CardStatus(cardId, stage, identity, nextStep, canOffer, condition, deadline, waiting, waitingSince);
        }

        CardStatus withWaitingSince(Instant since) {
            return new CardStatus(cardId, stage, identity, nextStep, canOffer, condition, deadline, sellersWord, since);
        }
    }

    /**
     * What's waiting on the seller, for the home page. Everything else on the way to a sale is waiting on us or a buyer,
     * and the one exception, {@link #CHECKING}, is only counted there: a card we're identifying, assessing or pricing.
     */
    enum Need { LABEL_READY, PAYMENT_DUE, PAYMENT_CHECK, CARD_RETURNING, OFFER_EXPIRING, OFFER_READY, GET_OFFER, RETAKE, CONFIRM_CARD, CHECKING }

    /** Stages where the card waits on us. */
    static final Set<Stage> OUR_REVIEW = EnumSet.of(Stage.IDENTIFYING, Stage.ASSESSING, Stage.PRICING);

    /** Only a collector is nudged to sell. */
    private static final Set<Need> OFFER_NUDGES = EnumSet.of(Need.OFFER_EXPIRING, Need.OFFER_READY, Need.GET_OFFER);

    /**
     * @param due   when it runs out, or null
     * @param sides for a retake, the sides to take again
     */
    record NeedsYou(UUID cardId, String label, Need need, Instant due, List<String> sides) {
    }

    /** An offer this close to ending says so, on top of saying it's ready. */
    static final Duration EXPIRING_SOON = Duration.ofHours(48);

    /** We email the collector once per card when its offer is ready (Zach, 2026-11-23). */
    static final String OFFER_EMAIL = " We'll email you when its offer is ready.";

    /**
     * A card waiting only on sold prices we add by hand: our target for adding them, and when it gets no offer instead
     * of waiting on (methodology Step 8; defaults to confirm; Zach, 2026-11-23).
     */
    static final Duration COMPS_TARGET = Duration.ofDays(3);
    static final Duration COMPS_GIVE_UP = Duration.ofDays(14);

    private final OfferService offers;
    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final CardItems cardItems;
    private final CardOfferVoidRepository voids;
    private final Assessments assessments;
    private final Shipping shipping;
    private final OfferPolicy policy;
    private final PreGradeFailures preGradeFailures;
    private final IdentifyWaits identifyWaits;
    private final Catalog catalog;

    CardStatusService(OfferService offers, SaleListingRepository listings, ListingEventRepository events, CardItems cardItems,
                      CardOfferVoidRepository voids, Assessments assessments, Shipping shipping, OfferPolicy policy,
                      PreGradeFailures preGradeFailures, IdentifyWaits identifyWaits, Catalog catalog) {
        this.identifyWaits = identifyWaits;
        this.catalog = catalog;
        this.preGradeFailures = preGradeFailures;
        this.policy = policy;
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
     * isn't there to sell, but still sees a sale through and retakes what failed.
     */
    @Transactional(readOnly = true)
    List<NeedsYou> needsYou(UUID orgId, boolean selling) {
        Instant now = Instant.now();
        return cardItems.inCollectionsOf(orgId).stream().map(card -> {
            CardStatus status = stage(card.id());
            List<String> sides = status.stage() == Stage.PHOTOS ? cardItems.sidesToRetake(card.id()) : List.of();
            Need need = need(status, !sides.isEmpty(), now);
            if (need == null && OUR_REVIEW.contains(status.stage())) {
                need = Need.CHECKING;
            }
            boolean wanted = need != null && (selling || !OFFER_NUDGES.contains(need));
            Instant due = need == Need.CHECKING ? status.waitingSince() : status.deadline();
            return wanted ? new NeedsYou(card.id(), card.label(), need, due, sides) : null;
        }).filter(Objects::nonNull).toList();
    }

    /**
     * What a card in this state needs from its seller, or null. A sold card needs them until they say it shipped, or an
     * arrival shows it did; a card being identified, while the reader's suggestion or the search waits on their answer.
     */
    static Need need(CardStatus status, boolean retake, Instant now) {
        return switch (status.stage()) {
            case SOLD -> status.deadline() == null ? null : Need.LABEL_READY;
            case ARRIVED -> status.sellersWord() ? Need.PAYMENT_DUE : null;
            case PAID -> status.sellersWord() ? Need.PAYMENT_CHECK : null;
            case RETURNED -> status.sellersWord() ? Need.CARD_RETURNING : null;
            case OFFERED -> status.deadline().isBefore(now.plus(EXPIRING_SOON)) ? Need.OFFER_EXPIRING : Need.OFFER_READY;
            case READY -> Need.GET_OFFER;
            case PHOTOS -> retake ? Need.RETAKE : null;
            case IDENTIFYING -> status.sellersWord() ? Need.CONFIRM_CARD : null;
            default -> null;
        };
    }

    private CardStatus stage(UUID cardId) {
        return stage(cardId, Instant.now());
    }

    private CardStatus stage(UUID cardId, Instant now) {
        CardItems.CardItemRef card = cardItems.get(cardId);
        if (card.lot()) {
            return new CardStatus(cardId, Stage.LOT, null, "Lots don't get offers: add the cards worth " + policy.minFmvText() + " or more one by one.", false);
        }
        Optional<SaleListing> listing = listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardId);
        List<ListingEvent> history = listing.map(l -> events.findByListingIdInOrderByCreatedAtAsc(List.of(l.getId()))).orElse(List.of());
        ListingStateMachine.Status listed = listing.map(l -> ListingStateMachine.status(l, history, Instant.now())).orElse(null);
        // Once the card is posted or has an offer, the stage comes from the listing or the offer, and so does the identity
        // (the card as it was offered): valuing it again would cost a valuation per card on every list (review finding 9).
        ListingStateMachine.Progress deal = ListingStateMachine.progress(history);
        if (listed == ListingStateMachine.Status.ACCEPTED) {
            return afterTheSale(cardId, offeredAs(listing.orElseThrow()), deal, listing.orElseThrow().getId());
        }
        if (listed == ListingStateMachine.Status.BACK && deal.held()) {
            return new CardStatus(cardId, Stage.RETURNED, offeredAs(listing.orElseThrow()), ListingStateMachine.HELD, false);
        }
        if (listed == ListingStateMachine.Status.OPEN) {
            return new CardStatus(cardId, Stage.LISTED, offeredAs(listing.orElseThrow()),
                    "Posted to buyers: the first to accept buys it at the price.", false)
                    .withDeadline(listing.orElseThrow().getExpiresAt());
        }
        // A card that came back can't go out again on the offer it sold at.
        UUID soldAt = listed == ListingStateMachine.Status.BACK ? listing.get().getOfferId() : null;
        Optional<CardOffer> latest = offers.latest(cardId).filter(o -> o.getExpiresAt().isAfter(Instant.now()))
                .filter(o -> voids.findByOfferId(o.getId()).isEmpty()).filter(o -> !o.getId().equals(soldAt));
        if (latest.isPresent()) {
            return new CardStatus(cardId, Stage.OFFERED, identity(offers.identity(latest.orElseThrow().getId())),
                    "You have an offer: sell at it, or let it expire.", false)
                    .withDeadline(latest.orElseThrow().getExpiresAt());
        }
        OfferService.Quote quote = offers.quote(cardId);
        CardInputs in = quote.value().inputs();
        String identity = identity(in.identity());
        List<CardItems.PhotoRef> photos = cardItems.passedPhotos(cardId);
        boolean captured = photos.stream().anyMatch(p -> p.side().equals("FRONT")) && photos.stream().anyMatch(p -> p.side().equals("BACK"));
        if (!captured) {
            return new CardStatus(cardId, Stage.PHOTOS, identity, "Take the front and back photos. We review the card from them.", false);
        }
        UnvaluedReason reason = UnvaluedReason.of(in.unvaluedReason());
        Instant photographed = photos.stream().map(CardItems.PhotoRef::uploadedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        if (in.identity() == null) {
            if (identifyWaits.onCollector(cardId)) {
                return new CardStatus(cardId, Stage.IDENTIFYING, null,
                        "Tell us which card it is under “Which card is it?”. Then we check your answer against the photos, "
                                + "and then its condition." + OFFER_EMAIL, false).withSellersWord(true);
            }
            return new CardStatus(cardId, Stage.IDENTIFYING, null,
                    "We're checking which card it is from your photos. It usually takes a few minutes, or up to a day if a "
                            + "person needs to look." + OFFER_EMAIL, false).withWaitingSince(photographed);
        }
        Instant identified = latest(photographed, catalog.current(cardId).map(Catalog.IdentifiedCard::identifiedSince).orElse(null));
        if (reason == UnvaluedReason.NO_ASSESSMENT) {
            if (preGradeFailures.failed(cardId)) {
                // No provider name, error or credit status: the collector only needs to know a person will look.
                return new CardStatus(cardId, Stage.ASSESSING, identity,
                        "The automatic condition check didn't work for this card, so we'll check it by hand, usually within a "
                                + "day or two." + OFFER_EMAIL, false).withWaitingSince(identified);
            }
            return new CardStatus(cardId, Stage.ASSESSING, identity,
                    "We're reviewing its condition from the photos. It usually takes a few minutes, or a day or two if a "
                            + "person needs to look." + OFFER_EMAIL, false).withWaitingSince(identified);
        }
        if (reason == UnvaluedReason.PROVIDER_RETIRED) {
            return new CardStatus(cardId, Stage.ASSESSING, identity,
                    "We changed how we check condition, so we're checking this card again." + OFFER_EMAIL, false)
                    .withWaitingSince(identified);
        }
        if (reason == UnvaluedReason.PHOTOS_CHANGED) {
            return new CardStatus(cardId, Stage.ASSESSING, identity,
                    "Your photos changed, so we're checking its condition again." + OFFER_EMAIL, false).withWaitingSince(identified);
        }
        Instant pricingSince = latest(identified, assessments.assessedAt(cardId).orElse(null));
        if (reason == UnvaluedReason.NO_PRICE_TABLE) {
            if (gaveUpOnComps(pricingSince, now)) {
                return new CardStatus(cardId, Stage.NO_OFFER, identity, GAVE_UP, false);
            }
            return new CardStatus(cardId, Stage.PRICING, identity, "We don't have enough sold prices for this card yet. " + compsWait()
                    + OFFER_EMAIL, false).withWaitingSince(pricingSince);
        }
        if (reason != null) {
            return new CardStatus(cardId, Stage.PRICING, identity,
                    "We can't price this card yet, and we're looking into why. Some cards won't get an offer. "
                            + "You're free to sell it elsewhere." + OFFER_EMAIL, false).withWaitingSince(pricingSince);
        }
        if (quote.refused() == OfferRefusal.OVER_MAXIMUM) {
            // Not "no offer yet": no instant offer comes later for a card worth more than the limit.
            return new CardStatus(cardId, Stage.OVER_LIMIT, identity, quote.refusal() + " You're free to sell it elsewhere.", false);
        }
        if (quote.refused() == OfferRefusal.THIN_SALES) {
            return gaveUpOnComps(pricingSince, now) ? new CardStatus(cardId, Stage.NO_OFFER, identity, GAVE_UP, false)
                    : new CardStatus(cardId, Stage.NO_OFFER, identity, quote.refusal() + " " + compsWait()
                            + OFFER_EMAIL, false).withWaitingSince(pricingSince);
        }
        if (quote.refused() != null) {
            return new CardStatus(cardId, Stage.NO_OFFER, identity, quote.refusal() + nextStep(quote.refused()), false);
        }
        return new CardStatus(cardId, Stage.READY, identity, "Ready for an instant offer.", true);
    }

    private static final String GAVE_UP = "We couldn't find enough sold prices for this card within " + COMPS_GIVE_UP.toDays()
            + " days, so it gets no offer. You're free to sell it elsewhere.";

    /** The wait for sold prices we add by hand, in words: our target, and the point where we stop. */
    private static String compsWait() {
        return "We're adding more by hand, which we aim to do within " + COMPS_TARGET.toDays() + " days. If we can't "
                + "find enough within " + COMPS_GIVE_UP.toDays() + " days, it gets no offer. You're free to sell it elsewhere meanwhile.";
    }

    /** True once a card has waited on sold prices added by hand for as long as we wait. An unknown start never gives up. */
    static boolean gaveUpOnComps(Instant since, Instant now) {
        return since != null && !now.isBefore(since.plus(COMPS_GIVE_UP));
    }

    private static Instant latest(Instant... times) {
        return Stream.of(times).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    /** The card as identified when the listing's offer was made. */
    private String offeredAs(SaleListing listing) {
        return identity(offers.identity(listing.getOfferId()));
    }

    private CardStatus afterTheSale(UUID cardId, String identity, ListingStateMachine.Progress deal, UUID listingId) {
        return switch (deal.stage()) {
            case SHIPPED -> new CardStatus(cardId, Stage.SHIPPED, identity,
                    "On its way to the buyer. They record it when it arrives, and then have " + policy.terms().payWithinDays() + " days to pay you. We email you when it arrives.", false);
            case ARRIVED -> new CardStatus(cardId, Stage.ARRIVED, identity,
                    "It arrived. The buyer pays you directly: tell us here when they have, or if they haven't by the date on this page.", false)
                    .withDeadline(deal.payBy()).withSellersWord(deal.disputedAt() == null);
            case PAID -> new CardStatus(cardId, Stage.PAID, identity, deal.confirmedAt() != null ? "Paid. Nothing more to do."
                    : "The buyer says they paid you. Tell us here whether you got it.", false)
                    .withSellersWord(deal.confirmedAt() == null && deal.disputedAt() == null);
            case RETURNED -> new CardStatus(cardId, Stage.RETURNED, identity,
                    "The buyer is sending it back. Tell us here once you have it.", false).withSellersWord(true);
            default -> new CardStatus(cardId, Stage.SOLD, identity, "A buyer took it: ship it with the prepaid label once it shows on this card's page.", false)
                    .withDeadline(shipping.label(listingId).map(l -> l.getCreatedAt().plus(ListingStateMachine.SHIP_WITHIN)).orElse(null));
        };
    }

    private String nextStep(OfferRefusal refused) {
        return switch (refused) {
            case UNDER_MINIMUM -> " Instant offers are for cards worth " + policy.minFmvText() + " or more.";
            default -> "";
        };
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

package com.pokemans.app.offers;

import static com.pokemans.app.offers.ListingStateMachine.HELD;
import static com.pokemans.app.offers.ListingStateMachine.progress;
import static com.pokemans.app.offers.ListingStateMachine.status;

import static com.pokemans.app.offers.ListingViews.card;
import static com.pokemans.app.offers.ListingViews.current;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.identity.OrgAccess;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.offers.ListingStateMachine.Progress;
import com.pokemans.app.offers.ListingStateMachine.Status;
import com.pokemans.app.offers.ListingViews.MarketItem;
import com.pokemans.app.offers.ListingViews.SaleView;
import com.pokemans.app.valuation.CardInputs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Posting firm offers to buyers, until one takes the card: the seller posts and may withdraw, and the
 * first buyer to accept takes it at the offer price. What follows the sale is {@link DealService}'s.
 * No access checks: the controllers have done them.
 */
@Service
class ListingService {

    private final CardOfferRepository offers;
    private final CardOfferVoidRepository voids;
    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final OffersProperties properties;
    private final Shipping shipping;
    private final CurrentUser currentUser;
    private final CardItems cardItems;
    private final ListingLock lock;
    private final OrgAccess access;
    private final ListingViews views;
    private final OfferService pricing;

    /**
     * 409 because the offer's price no longer holds on today's prices. Thrown after the void row is saved, and
     * the transaction commits it anyway ({@code noRollbackFor}): the offer stays withdrawn whatever the caller does.
     */
    static final class PriceChanged extends ResponseStatusException {
        PriceChanged() {
            super(HttpStatus.CONFLICT, OfferService.PRICE_CHANGED);
        }
    }

    ListingService(CardOfferRepository offers, CardOfferVoidRepository voids, SaleListingRepository listings, ListingEventRepository events,
                   OffersProperties properties, Shipping shipping, CurrentUser currentUser, CardItems cardItems, ListingLock lock,
                   OrgAccess access, ListingViews views, OfferService pricing) {
        this.offers = offers;
        this.voids = voids;
        this.listings = listings;
        this.events = events;
        this.properties = properties;
        this.shipping = shipping;
        this.currentUser = currentUser;
        this.cardItems = cardItems;
        this.lock = lock;
        this.access = access;
        this.views = views;
        this.pricing = pricing;
    }

    /** The seller's view: the latest offer on the card and its latest listing. */
    @Transactional(readOnly = true)
    SaleView sale(UUID cardItemId) {
        Optional<CardOffer> offer = offers.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId);
        Optional<SaleListing> listing = listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId);
        Map<UUID, List<ListingEvent>> history = events.history(listing.stream().toList());
        return new SaleView(offer.map(views::offerView).orElse(null),
                listing.map(l -> views.listingView(l, history.getOrDefault(l.getId(), List.of()))).orElse(null));
    }

    /**
     * Posts the offer to every buyer. The seller needs an address on file, since the label is bought
     * the moment a buyer accepts. One open or taken listing per card at a time. An offer whose price has moved
     * more than {@code pokemans.offers.reprice-threshold} on today's prices is voided (a new row) and refused.
     */
    @Transactional(noRollbackFor = PriceChanged.class)
    SaleListing post(UUID orgId, UUID cardItemId, UUID offerId) {
        // Serialize posting per card, so two clicks can't open two listings.
        lock.lock(cardItemId);
        CardOffer offer = offers.findById(offerId).filter(o -> o.getCardItemId().equals(cardItemId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer not found"));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (voids.findByOfferId(offerId).isPresent()) {
            throw conflict("We withdrew this offer. Get a new one first.");
        }
        if (!offer.getExpiresAt().isAfter(now)) {
            throw conflict("This offer has expired. Get a new one first.");
        }
        Optional<ShipAddress> shipFrom = shipping.address(orgId);
        if (shipFrom.isEmpty()) {
            throw conflict("Add the address you'll ship from first.");
        }
        if (shipFrom.get().payment() == null) {
            throw conflict("Add how you'd like to be paid first.");
        }
        // Buyers see the photos the offer was priced on, and from here on they can't change: hold the lock
        // photo confirms take, and refuse an offer whose photos were retaken since.
        cardItems.lockForCapture(cardItemId);
        Set<UUID> priced = new HashSet<>(OfferService.inputs(offer).photos().stream().map(CardInputs.Photo::photoId).toList());
        if (cardItems.passedPhotos(cardItemId).stream().anyMatch(p -> !priced.contains(p.id()))) {
            throw conflict("Your photos changed after this offer. Get a new offer, so buyers see the photos it's based on.");
        }
        // Photos that passed before exact copies were kept get theirs now, before any buyer can open them.
        current(OfferService.inputs(offer).photos()).forEach(p -> cardItems.ensureExactCopy(p.storageKey(), p.contentType()));
        Optional<SaleListing> latest = listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId);
        if (latest.isPresent()) {
            Status status = status(latest.get(), events.history(List.of(latest.get())).getOrDefault(latest.get().getId(), List.of()), now);
            if (status == Status.OPEN) {
                throw conflict("This card is already posted to buyers.");
            }
            if (status == Status.ACCEPTED) {
                throw conflict("A buyer has already taken this card.");
            }
            if (status == Status.BACK && latest.get().getOfferId().equals(offerId)) {
                throw conflict("This offer has already been sold once. Get a new one first.");
            }
            requireNotHeld(cardItemId);
        }
        // Last, so the reasons above (photos changed, already posted) win over a moved price.
        if (pricing.reprice(offer).moved()) {
            voids.saveAndFlush(new CardOfferVoid(offerId, OfferService.PRICE_CHANGED, currentUser.id()));
            throw new PriceChanged();
        }
        return listings.save(new SaleListing(offerId, orgId, cardItemId, currentUser.id(), now, now.plus(properties.listingOpenFor())));
    }

    @Transactional
    void withdraw(UUID orgId, UUID listingId) {
        SaleListing listing = listings.findByIdAndOrgId(listingId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
        lock.lock(listing.getCardItemId());
        requireOpen(listing, Map.of(Status.ACCEPTED, "A buyer has already taken this card, so it can't be withdrawn.",
                Status.WITHDRAWN, "This listing is already withdrawn.", Status.EXPIRED, "This listing has already expired.",
                Status.VOIDED, "We've already taken this listing down.", Status.CANCELLED, "This deal was cancelled.",
                Status.BACK, "A buyer has already taken this card, so it can't be withdrawn."));
        close(ListingEvent.withdrawn(listingId, orgId, currentUser.id()), "A buyer took this card before it was withdrawn.");
    }

    @Transactional(readOnly = true)
    List<MarketItem> market() {
        List<SaleListing> open = listings.findOpen(Instant.now());
        Map<UUID, CardOffer> byId = offers.findAllById(open.stream().map(SaleListing::getOfferId).toList()).stream()
                .collect(Collectors.toMap(CardOffer::getId, o -> o));
        List<MarketItem> items = new ArrayList<>();
        for (SaleListing l : open) {
            CardOffer offer = byId.get(l.getOfferId());
            CardInputs in = OfferService.inputs(offer);
            items.add(new MarketItem(l.getId(), card(in).forBuyer(), views.offerView(offer, null).forBuyer(), views.photos(in), l.getCreatedAt(), l.getExpiresAt()));
        }
        return items;
    }

    /**
     * What the label for an open listing would cost this buyer, before they accept. Buys nothing. Outside a transaction,
     * since the quote is a call out to the provider.
     */
    Shipping.Estimate postage(UUID buyerOrgId, UUID listingId) {
        SaleListing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
        if (status(listing, events.history(List.of(listing)).getOrDefault(listingId, List.of()), Instant.now()) != Status.OPEN) {
            throw conflict("This card is no longer for sale.");
        }
        return shipping.estimate(listing.getOrgId(), buyerOrgId, offers.findById(listing.getOfferId()).orElseThrow().getPrice());
    }

    /**
     * First come, first served: the first buyer to accept takes the card at the offer price, everyone
     * after gets 409. The buyer needs an address on file for the label. Accepting again is how a double
     * click or a retry after a lost answer looks, so the buyer who holds the card just gets it back. An offer
     * whose price has moved more than {@code pokemans.offers.reprice-threshold} on today's prices comes off the
     * market (a VOIDED listing event and an offer void, both new rows) and the buyer is refused.
     *
     * @return the listing taken, for the label purchase that follows the commit
     */
    @Transactional(noRollbackFor = PriceChanged.class)
    SaleListing accept(UUID buyerOrgId, UUID listingId) {
        SaleListing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
        lock.lock(listing.getCardItemId());
        List<ListingEvent> history = events.history(List.of(listing)).getOrDefault(listingId, List.of());
        if (status(listing, history, Instant.now()) == Status.ACCEPTED
                && history.stream().anyMatch(e -> e.getType() == ListingEvent.Type.ACCEPTED && e.getActorOrgId().equals(buyerOrgId))) {
            return listing;
        }
        requireOpen(listing, Map.of(Status.ACCEPTED, "Another buyer took this card first.",
                Status.WITHDRAWN, "The seller withdrew this card.", Status.EXPIRED, "This listing has expired.",
                Status.VOIDED, "This card is no longer for sale.", Status.CANCELLED, "Another buyer took this card first.",
                Status.BACK, "Another buyer took this card first."));
        if (access.shareMember(listing.getOrgId(), buyerOrgId)) {
            throw conflict("You can't buy your own card.");
        }
        if (shipping.address(buyerOrgId).isEmpty()) {
            throw conflict("Add the address cards should ship to first.");
        }
        CardOffer offer = offers.findById(listing.getOfferId()).orElseThrow();
        if (pricing.reprice(offer).moved()) {
            close(ListingEvent.voided(listingId, currentUser.id(), OfferService.PRICE_CHANGED), "Another buyer took this card first.");
            if (voids.findByOfferId(offer.getId()).isEmpty()) {
                voids.saveAndFlush(new CardOfferVoid(offer.getId(), OfferService.PRICE_CHANGED, currentUser.id()));
            }
            throw new PriceChanged();
        }
        close(ListingEvent.accepted(listingId, buyerOrgId, currentUser.id()), "Another buyer took this card first.");
        return listing;
    }

    /** 409 while the latest deal on the card is a "not genuine" return we haven't reviewed: no new offer until we have. */
    @Transactional(readOnly = true)
    void requireNotHeld(UUID cardItemId) {
        listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId).ifPresent(l -> {
            Progress progress = progress(events.history(List.of(l)).getOrDefault(l.getId(), List.of()));
            if (progress != null && progress.held()) {
                throw conflict(HELD);
            }
        });
    }

    /** 409 with the reason for the listing's state, unless it is open. */
    private void requireOpen(SaleListing listing, Map<Status, String> reasons) {
        Status status = status(listing, events.history(List.of(listing)).getOrDefault(listing.getId(), List.of()), Instant.now());
        if (status != Status.OPEN) {
            throw conflict(reasons.get(status));
        }
    }

    /** Saves a closing event; the database lets only one close a listing, so a lost race is a 409. */
    private void close(ListingEvent event, String lostRace) {
        try {
            events.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            throw conflict(lostRace);
        }
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}

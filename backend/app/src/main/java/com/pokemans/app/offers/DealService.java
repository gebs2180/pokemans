package com.pokemans.app.offers;

import static com.pokemans.app.offers.ListingStateMachine.progress;
import static com.pokemans.app.offers.ListingStateMachine.status;
import static com.pokemans.app.offers.ListingViews.card;
import static com.pokemans.app.offers.ListingViews.outcome;
import static com.pokemans.app.offers.ListingViews.undone;
import static com.pokemans.app.offers.ListingViews.withoutPrintable;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.identity.Directory;
import com.pokemans.app.offers.ListingStateMachine.DealStage;
import com.pokemans.app.offers.ListingStateMachine.Progress;
import com.pokemans.app.offers.ListingStateMachine.Status;
import com.pokemans.app.offers.ListingViews.AwaitingLabel;
import com.pokemans.app.offers.ListingViews.DealView;
import com.pokemans.app.offers.ListingViews.Overdue;
import com.pokemans.app.offers.ListingViews.Party;
import com.pokemans.app.valuation.CardInputs;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A deal, once a buyer took the card: the prepaid label, then what each side says. The seller ships
 * and confirms payment, the buyer records the arrival and payment, or returns it. Rawmark never touches
 * the money: these rows record what each side says. No access checks: the controllers have done them.
 */
@Service
class DealService {

    private final CardOfferRepository offers;
    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final Directory directory;
    private final Shipping shipping;
    private final CurrentUser currentUser;
    private final ListingLock lock;
    private final ListingViews views;

    DealService(CardOfferRepository offers, SaleListingRepository listings, ListingEventRepository events, Directory directory,
                Shipping shipping, CurrentUser currentUser, ListingLock lock, ListingViews views) {
        this.offers = offers;
        this.listings = listings;
        this.events = events;
        this.directory = directory;
        this.shipping = shipping;
        this.currentUser = currentUser;
        this.lock = lock;
        this.views = views;
    }

    /**
     * Buys the label for a deal (again, if the first try failed), unless we cancelled it or it's over: a card
     * recorded as arrived or returned without a label shipped another way, and needs none. Holds the card's
     * listing lock throughout, so a second request waits and gets the label the first bought instead of
     * buying another on our account, and a cancel can't land between the check and the purchase.
     */
    @Transactional
    ShippingLabel buyLabel(SaleListing listing, UUID buyerOrgId) {
        lock.lock(listing.getCardItemId());
        requireNotCancelled(listing.getId());
        if (shipping.label(listing.getId()).isEmpty() && outcome(events.history(List.of(listing)).getOrDefault(listing.getId(), List.of())) != null) {
            throw conflict("This card's arrival or return is already recorded, so it needs no label.");
        }
        CardOffer offer = offers.findById(listing.getOfferId()).orElseThrow();
        return shipping.buy(listing.getId(), listing.getOrgId(), buyerOrgId, offer.getPrice());
    }

    /** A deal of this buyer org, or 404. */
    @Transactional(readOnly = true)
    SaleListing requireDeal(UUID buyerOrgId, UUID listingId) {
        SaleListing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deal not found"));
        boolean ours = events.history(List.of(listing)).getOrDefault(listingId, List.of()).stream()
                .anyMatch(e -> e.getType() == ListingEvent.Type.ACCEPTED && e.getActorOrgId().equals(buyerOrgId));
        if (!ours) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Deal not found");
        }
        return listing;
    }

    @Transactional(readOnly = true)
    List<DealView> deals(UUID buyerOrgId) {
        List<SaleListing> accepted = listings.findAcceptedBy(buyerOrgId);
        Map<UUID, List<ListingEvent>> history = events.history(accepted);
        List<DealView> deals = new ArrayList<>();
        for (SaleListing l : accepted) {
            CardOffer offer = offers.findById(l.getOfferId()).orElseThrow();
            CardInputs in = OfferService.inputs(offer);
            List<ListingEvent> h = history.getOrDefault(l.getId(), List.of());
            ListingEvent acceptedEvent = h.stream().filter(e -> e.getType() == ListingEvent.Type.ACCEPTED).findFirst().orElseThrow();
            Directory.Contact seller = directory.contact(l.getOrgId(), l.getListedBy());
            Progress progress = progress(h);
            boolean owed = progress != null && progress.stage() != DealStage.RETURNED && progress.stage() != DealStage.BACK;
            deals.add(new DealView(l.getId(), card(in).forBuyer(), views.offerView(offer).forBuyer(), views.photos(in), new Party(seller.orgName(), seller.email()),
                    acceptedEvent.getCreatedAt(), withoutPrintable(views.label(l.getId())), outcome(h), undone(h, ListingEvent.Type.CANCELLED),
                    owed ? shipping.address(l.getOrgId()).map(ShipAddress::payment).orElse(null) : null,
                    progress == null ? null : progress.forBuyer()));
        }
        return deals;
    }

    /**
     * The card arrived genuine and as shown, which starts the 7 days to pay. Once per deal, and only
     * once a label was bought, since nothing can have shipped before.
     *
     * @param byAdmin the admin records it for the buyer, e.g. when the card shipped without our label;
     *                it then needs a note saying so
     */
    @Transactional
    void received(UUID buyerOrgId, UUID listingId, String note, boolean byAdmin) {
        lockDeal(requireDeal(buyerOrgId, listingId));
        requireNotCancelled(listingId);
        requireShippable(listingId, note, byAdmin);
        recordOutcome(ListingEvent.received(listingId, buyerOrgId, currentUser.id(), adminNote(note, byAdmin)));
    }

    /** The buyer says they paid the seller, once the card arrived. A price other than the offer needs a note. */
    @Transactional
    void paid(UUID buyerOrgId, UUID listingId, BigDecimal pricePaid, String note) {
        SaleListing listing = lockDeal(requireDeal(buyerOrgId, listingId));
        Progress progress = progress(events.history(List.of(listing)).getOrDefault(listingId, List.of()));
        if (progress == null) {
            throw conflict("We cancelled this deal, so there's nothing more to do on it.");
        }
        if (progress.stage() == DealStage.RETURNED || progress.stage() == DealStage.BACK) {
            throw conflict("This card went back, so nothing is owed on it.");
        }
        if (progress.arrivedAt() == null) {
            throw conflict("Record that the card arrived first.");
        }
        if (progress.paidAt() != null) {
            throw conflict("You've already recorded paying for this card.");
        }
        BigDecimal price = offers.findById(listing.getOfferId()).orElseThrow().getPrice();
        if (pricePaid.compareTo(price) != 0 && blankToNull(note) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say in the note why the amount paid differs from the $" + price.toPlainString() + " offer.");
        }
        close(ListingEvent.paid(listingId, buyerOrgId, currentUser.id(), pricePaid, blankToNull(note)),
                "You've already recorded paying for this card.");
    }

    /**
     * The card goes back: not genuine, not the listed card, or not matching its photos. Once per deal,
     * once it could have shipped. "Not genuine" accuses the seller, so it has to say why.
     */
    @Transactional
    void returned(UUID buyerOrgId, UUID listingId, ListingEvent.DeclinedReason reason, String note, boolean byAdmin) {
        lockDeal(requireDeal(buyerOrgId, listingId));
        requireNotCancelled(listingId);
        if (reason == ListingEvent.DeclinedReason.NOT_GENUINE && blankToNull(note) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say in the note why you think the card isn't genuine.");
        }
        requireShippable(listingId, note, byAdmin);
        recordOutcome(ListingEvent.returned(listingId, buyerOrgId, currentUser.id(), reason, adminNote(note, byAdmin)));
    }

    /** Nothing ships before the label is bought; the admin may record it anyway, saying why. */
    private void requireShippable(UUID listingId, String note, boolean byAdmin) {
        if (byAdmin) {
            if (blankToNull(note) == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say in the note how the card was shipped, or why it's recorded by hand.");
            }
            return;
        }
        if (shipping.label(listingId).isEmpty()) {
            throw conflict("There's no label for this card yet, so it can't have arrived. Record it once the label is bought and the card ships.");
        }
    }

    private static String adminNote(String note, boolean byAdmin) {
        String n = blankToNull(note);
        return byAdmin ? "Recorded by the admin: " + n : n;
    }

    /** Deals with no label and nothing recorded yet, oldest accept first. */
    @Transactional(readOnly = true)
    List<AwaitingLabel> awaitingLabels() {
        List<SaleListing> accepted = listings.findAllAccepted();
        Map<UUID, List<ListingEvent>> history = events.history(accepted);
        List<AwaitingLabel> waiting = new ArrayList<>();
        for (SaleListing l : accepted) {
            List<ListingEvent> h = history.getOrDefault(l.getId(), List.of());
            if (status(l, h, Instant.now()) != Status.ACCEPTED || outcome(h) != null || shipping.label(l.getId()).isPresent()) {
                continue;
            }
            ListingEvent acceptedEvent = h.stream().filter(e -> e.getType() == ListingEvent.Type.ACCEPTED).findFirst().orElseThrow();
            CardOffer offer = offers.findById(l.getOfferId()).orElseThrow();
            CardInputs in = OfferService.inputs(offer);
            Directory.Contact buyer = directory.contact(acceptedEvent.getActorOrgId(), acceptedEvent.getActorUserId());
            Directory.Contact seller = directory.contact(l.getOrgId(), l.getListedBy());
            waiting.add(new AwaitingLabel(l.getId(), acceptedEvent.getActorOrgId(), new Party(buyer.orgName(), buyer.email()),
                    new Party(seller.orgName(), seller.email()), card(in), views.offerView(offer), views.photos(in), acceptedEvent.getCreatedAt()));
        }
        return waiting;
    }

    @Transactional(readOnly = true)
    Overdue overdue() {
        Instant now = Instant.now();
        List<SaleListing> accepted = listings.findAllAccepted();
        Map<UUID, List<ListingEvent>> history = events.history(accepted);
        int overdue = 0;
        for (SaleListing l : accepted) {
            List<ListingEvent> h = history.getOrDefault(l.getId(), List.of());
            if (status(l, h, now) == Status.ACCEPTED
                    && shipping.label(l.getId()).filter(x -> ListingStateMachine.overdue(x.getCreatedAt(), progress(h).stage(), now)).isPresent()) {
                overdue++;
            }
        }
        return new Overdue(overdue);
    }


    private void recordOutcome(ListingEvent event) {
        try {
            events.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            throw conflict("What happened to this card is already recorded, or the deal was cancelled.");
        }
    }

    /** The listings among these whose deal we cancelled. */
    @Transactional(readOnly = true)
    Set<UUID> cancelledAmong(Collection<UUID> listingIds) {
        if (listingIds.isEmpty()) {
            return Set.of();
        }
        return events.findByListingIdInOrderByCreatedAtAsc(listingIds).stream()
                .filter(e -> e.getType() == ListingEvent.Type.CANCELLED).map(ListingEvent::getListingId).collect(Collectors.toSet());
    }

    // What the seller says after the sale. Each is one more row, for the record: none of it moves money.

    /** The card is with the carrier, so its label is used. */
    @Transactional
    void shipped(UUID sellerOrgId, UUID listingId) {
        Progress progress = sellerDeal(sellerOrgId, listingId);
        if (shipping.label(listingId).isEmpty()) {
            throw conflict("Don't ship the card until its label shows here.");
        }
        if (progress.stage() != DealStage.SOLD) {
            throw conflict("This card has already shipped.");
        }
        close(ListingEvent.shipped(listingId, sellerOrgId, currentUser.id()), "This card has already shipped.");
    }

    /** The seller got paid. That ends the deal, whatever the buyer recorded. */
    @Transactional
    void paymentConfirmed(UUID sellerOrgId, UUID listingId) {
        Progress progress = sellerDeal(sellerOrgId, listingId);
        requireAwaitingPayment(progress);
        close(ListingEvent.paymentConfirmed(listingId, sellerOrgId, currentUser.id()), "You've already said you got paid.");
    }

    /** The seller hasn't been paid, or disagrees with a return: it goes to the admin's list. */
    @Transactional
    void disputed(UUID sellerOrgId, UUID listingId, String note) {
        Progress progress = sellerDeal(sellerOrgId, listingId);
        if (progress.stage() == DealStage.RETURNED) {
            if (blankToNull(note) == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why you disagree with the return.");
            }
        } else {
            requireAwaitingPayment(progress);
        }
        if (progress.disputedAt() != null) {
            throw conflict("We already have this from you, and we'll be in touch.");
        }
        events.saveAndFlush(ListingEvent.disputed(listingId, sellerOrgId, currentUser.id(), blankToNull(note)));
    }

    /** A returned card is back with the seller, so it can get a new offer and its photos can change. */
    @Transactional
    void cardBack(UUID sellerOrgId, UUID listingId) {
        Progress progress = sellerDeal(sellerOrgId, listingId);
        if (progress.stage() != DealStage.RETURNED) {
            throw conflict("Only a card the buyer is sending back can come back.");
        }
        close(ListingEvent.cardBack(listingId, sellerOrgId, currentUser.id()), "You've already said this card is back.");
    }

    /** Where a live deal of this seller is, under the card's lock so what's checked can't change before the row lands. */
    private Progress sellerDeal(UUID sellerOrgId, UUID listingId) {
        SaleListing listing = lockDeal(listings.findByIdAndOrgId(listingId, sellerOrgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found")));
        List<ListingEvent> h = events.history(List.of(listing)).getOrDefault(listingId, List.of());
        Status status = status(listing, h, Instant.now());
        if (status == Status.CANCELLED) {
            throw conflict("We cancelled this deal, so there's nothing more to do on it.");
        }
        if (status != Status.ACCEPTED && status != Status.BACK) {
            throw conflict("No buyer has taken this card.");
        }
        return progress(h);
    }

    private SaleListing lockDeal(SaleListing listing) {
        lock.lock(listing.getCardItemId());
        return listing;
    }

    private static void requireAwaitingPayment(Progress progress) {
        if (progress.confirmedAt() != null) {
            throw conflict("You've already said you got paid.");
        }
        if (progress.stage() == DealStage.SOLD) {
            throw conflict("Payment comes once the card has shipped and arrived.");
        }
        if (progress.stage() == DealStage.RETURNED || progress.stage() == DealStage.BACK) {
            throw conflict("This card went back, so nothing is owed on it.");
        }
    }

    private void requireNotCancelled(UUID listingId) {
        if (events.existsByListingIdAndType(listingId, ListingEvent.Type.CANCELLED)) {
            throw conflict("We cancelled this deal, so there's nothing more to do on it.");
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

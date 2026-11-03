package com.pokemans.app.offers;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.identity.Directory;
import com.pokemans.app.identity.OrgAccess;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import com.pokemans.app.valuation.CardInputs;
import com.pokemans.valuation.GradePrice;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Posting firm offers to buyers and what happens next: the first buyer to accept takes the card at
 * the offer price, the seller may withdraw until then, a prepaid label follows, and on arrival the
 * buyer records that the card came as shown, then that they paid, or returns it. Rawmark never touches
 * the money: from the sale on, these rows record what each side says. No access checks: the
 * controllers have done them.
 */
@Service
class ListingService {

    /** The buyer terms' deadline: payment within 7 days of the card arriving. */
    static final Duration PAY_WITHIN = Duration.ofDays(7);

    static final String HELD = "A buyer said this card isn't genuine, so we review that before it can get a new offer.";

    /**
     * VOIDED: we took it down with its offer. CANCELLED: a buyer took it and we undid the deal (Zach,
     * 2026-10-21). BACK: a buyer took it and sent it back, and the seller has it again, so it's free for
     * a new offer.
     */
    enum Status { OPEN, ACCEPTED, WITHDRAWN, EXPIRED, VOIDED, CANCELLED, BACK }

    /** Where a deal is, after Sold (UX review, 2026-11-01). Each has its own next step. */
    enum DealStage { SOLD, SHIPPED, ARRIVED, PAID, RETURNED, BACK }

    /**
     * What each side has said about a deal, for both of them to see.
     *
     * @param payBy       when payment is due: 7 days from the recorded arrival; null until then
     * @param paidAt      when the buyer says they paid
     * @param confirmedAt when the seller said they got paid
     * @param disputedAt  when the seller raised something that's still open, or null
     * @param disputeNote what they said; the seller's and the admin's to read
     * @param review      how the admin last closed something on this deal, written for both sides
     * @param held        the buyer returned it as not genuine and we haven't reviewed that: no new offer yet
     */
    record Progress(DealStage stage, Instant shippedAt, Instant arrivedAt, Instant payBy, Instant paidAt, BigDecimal pricePaid,
                    String paidNote, Instant confirmedAt, Instant disputedAt, String disputeNote, UndoView review, boolean held,
                    Instant cardBackAt) {

        private Progress forBuyer() {
            return new Progress(stage, shippedAt, arrivedAt, payBy, paidAt, pricePaid, paidNote, confirmedAt, disputedAt, null, review,
                    held, cardBackAt);
        }
    }

    record RungView(String grade, BigDecimal probability, BigDecimal price) {
    }

    /** Something we undid, and why (Zach, 2026-10-21). */
    record UndoView(String reason, Instant at) {
    }

    /**
     * @param price       the firm price
     * @param buyerMargin the margin left to the buyer, set by the confidence
     * @param ladder      how it was reached: what a buyer could pay at each grade, and how likely each is
     * @param expired     true once the offer is past its validity: price it again before posting
     * @param voided      set once we voided the offer: it can't be posted, get a new one
     */
    /**
     * @param sold            what each grade recently sold for, or null when showing sold prices is switched off
     * @param lessSureBecause why the confidence is below A ({@link LessSure}), empty at A
     */
    record OfferView(UUID id, BigDecimal price, BigDecimal fmv, String confidence, BigDecimal buyerMargin, boolean graded,
                     boolean subjectToAuthentication, List<RungView> ladder, Instant createdAt, Instant expiresAt, boolean expired,
                     UndoView voided, List<SoldView> sold, List<String> lessSureBecause) {
    }

    /**
     * What a grade of the card recently sold for: the recency-weighted median of its own sold comps (before grading costs),
     * as the offer's valuation used them (UX review finding 11). Grades priced from other grades have none.
     *
     * @param sales      sales in the window
     * @param windowDays the window the sales are from, 90 days or 180 when there were fewer than 2
     */
    record SoldView(String gradingCompany, String grade, BigDecimal price, int sales, Integer windowDays) {
    }

    /**
     * Why an offer's confidence is below A, for the seller in words (UX review finding 11), read from the offer's stored
     * trace: PHOTOS (the photos left the condition less certain), FEW_SALES (few sold comps), OLD_SALES (the price table
     * hasn't been refreshed lately), ESTIMATED_GRADES (a grade the card is likely to be has no sales of its own).
     */
    enum LessSure { PHOTOS, FEW_SALES, OLD_SALES, ESTIMATED_GRADES }

    record Party(String orgName, String email) {
    }

    /** @param labelUrl the printable label; shown to the seller, who ships the card */
    record LabelView(String carrier, String service, String trackingCode, String labelUrl, BigDecimal insuredValue, Instant boughtAt) {
    }

    /**
     * What happened once the card arrived.
     *
     * @param type           RECEIVED (as shown), RETURNED (with the reason), or INSPECTED (a grade
     *                       recorded under the per-grade offers that came before)
     * @param pricePaid      only on a RECEIVED from before payment became a step of its own
     * @param inspectedGrade only for INSPECTED
     */
    record OutcomeView(String type, BigDecimal pricePaid, String returnedReason, BigDecimal inspectedGrade, String note,
                       Instant recordedAt) {
    }

    /**
     * @param buyer    who took it, once accepted (shown to the seller)
     * @param label    the prepaid label, once bought; null while it follows. Its printable link goes once it's used
     * @param undone   why we took it down (VOIDED) or cancelled the deal (CANCELLED)
     * @param progress where the deal is, once a buyer took it; null otherwise or once cancelled
     */
    record ListingView(UUID id, UUID offerId, Status status, Instant listedAt, Instant expiresAt, Instant closedAt, Party buyer,
                       LabelView label, OutcomeView outcome, UndoView undone, Progress progress) {
    }

    record SaleView(OfferView offer, ListingView listing) {
    }

    record CardView(String name, String setName, String number, String language, String edition, String variant, String label,
                    Map<String, BigDecimal> distribution, String gradedAs) {

        /** The card without the seller's own label: free text a stranger shouldn't be shown, and it could carry a name or a phone number. */
        CardView forBuyer() {
            return new CardView(name, setName, number, language, edition, variant, null, distribution, gradedAs);
        }
    }

    /**
     * @param side    FRONT, BACK or DETAIL (the close-up)
     * @param url     the card crop, for thumbnails
     * @param fullUrl the photo exactly as taken, without its metadata: what the card must match on arrival
     */
    record PhotoLink(String side, String url, String fullUrl) {
    }

    record MarketItem(UUID listingId, CardView card, OfferView offer, List<PhotoLink> photos, Instant listedAt, Instant expiresAt) {
    }

    /**
     * @param payTo     how the seller gets paid, only ever shown once a buyer has accepted; null once
     *                  nothing is owed (cancelled or returned), or if the seller gave none
     * @param cancelled set once we cancelled the deal: nothing more to record, and the label isn't used
     */
    record DealView(UUID listingId, CardView card, OfferView offer, List<PhotoLink> photos, Party seller, Instant acceptedAt,
                    LabelView label, OutcomeView outcome, UndoView cancelled, PaymentHandle payTo, Progress progress) {
    }

    /** A deal whose label hasn't been bought, for the admin: the label to retry, or the arrival to record by hand. */
    record AwaitingLabel(UUID listingId, UUID buyerOrgId, Party buyer, Party seller, CardView card, OfferView offer,
                         List<PhotoLink> photos, Instant acceptedAt) {
    }

    /** The seller terms' deadline, from the label showing. */
    static final Duration SHIP_WITHIN = Duration.ofDays(3);

    /** @param labels sold cards past their ship-by date that nobody has said shipped */
    record Overdue(int labels) {
    }

    private final CardOfferRepository offers;
    private final CardOfferVoidRepository voids;
    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final OffersProperties properties;
    private final Directory directory;
    private final Shipping shipping;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final CurrentUser currentUser;
    private final CardItems cardItems;
    private final JdbcTemplate jdbc;
    private final OrgAccess access;

    ListingService(CardOfferRepository offers, CardOfferVoidRepository voids, SaleListingRepository listings, ListingEventRepository events,
                   OffersProperties properties, Directory directory, Shipping shipping, BlobStore blobs, StorageProperties storage,
                   CurrentUser currentUser, CardItems cardItems, JdbcTemplate jdbc, OrgAccess access) {
        this.offers = offers;
        this.voids = voids;
        this.listings = listings;
        this.events = events;
        this.properties = properties;
        this.directory = directory;
        this.shipping = shipping;
        this.blobs = blobs;
        this.storage = storage;
        this.currentUser = currentUser;
        this.cardItems = cardItems;
        this.jdbc = jdbc;
        this.access = access;
    }

    /** The seller's view: the latest offer on the card and its latest listing. */
    @Transactional(readOnly = true)
    SaleView sale(UUID cardItemId) {
        Optional<CardOffer> offer = offers.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId);
        Optional<SaleListing> listing = listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId);
        Map<UUID, List<ListingEvent>> history = history(listing.stream().toList());
        return new SaleView(offer.map(this::offerView).orElse(null),
                listing.map(l -> listingView(l, history.getOrDefault(l.getId(), List.of()))).orElse(null));
    }

    /**
     * Posts the offer to every buyer. The seller needs an address on file, since the label is bought
     * the moment a buyer accepts. One open or taken listing per card at a time.
     */
    @Transactional
    SaleListing post(UUID orgId, UUID cardItemId, UUID offerId) {
        // Serialize posting per card, so two clicks can't open two listings.
        lockListings(cardItemId);
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
            Status status = status(latest.get(), history(List.of(latest.get())).getOrDefault(latest.get().getId(), List.of()), now);
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
        return listings.save(new SaleListing(offerId, orgId, cardItemId, currentUser.id(), now, now.plus(properties.listingOpenFor())));
    }

    @Transactional
    void withdraw(UUID orgId, UUID listingId) {
        SaleListing listing = listings.findByIdAndOrgId(listingId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
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
            items.add(new MarketItem(l.getId(), card(in).forBuyer(), offerView(offer, null), photos(in), l.getCreatedAt(), l.getExpiresAt()));
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
        if (status(listing, history(List.of(listing)).getOrDefault(listingId, List.of()), Instant.now()) != Status.OPEN) {
            throw conflict("This card is no longer for sale.");
        }
        return shipping.estimate(listing.getOrgId(), buyerOrgId, offers.findById(listing.getOfferId()).orElseThrow().getPrice());
    }

    /**
     * First come, first served: the first buyer to accept takes the card at the offer price, everyone
     * after gets 409. The buyer needs an address on file for the label. Accepting again is how a double
     * click or a retry after a lost answer looks, so the buyer who holds the card just gets it back.
     *
     * @return the listing taken, for the label purchase that follows the commit
     */
    @Transactional
    SaleListing accept(UUID buyerOrgId, UUID listingId) {
        SaleListing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
        lockListings(listing.getCardItemId());
        List<ListingEvent> history = history(List.of(listing)).getOrDefault(listingId, List.of());
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
        close(ListingEvent.accepted(listingId, buyerOrgId, currentUser.id()), "Another buyer took this card first.");
        return listing;
    }

    /**
     * Buys the label for a deal (again, if the first try failed), unless we cancelled it or it's over: a card
     * recorded as arrived or returned without a label shipped another way, and needs none. Holds the card's
     * listing lock throughout, so a second request waits and gets the label the first bought instead of
     * buying another on our account, and a cancel can't land between the check and the purchase.
     */
    @Transactional
    ShippingLabel buyLabel(SaleListing listing, UUID buyerOrgId) {
        lockListings(listing.getCardItemId());
        requireNotCancelled(listing.getId());
        if (shipping.label(listing.getId()).isEmpty() && outcome(history(List.of(listing)).getOrDefault(listing.getId(), List.of())) != null) {
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
        boolean ours = history(List.of(listing)).getOrDefault(listingId, List.of()).stream()
                .anyMatch(e -> e.getType() == ListingEvent.Type.ACCEPTED && e.getActorOrgId().equals(buyerOrgId));
        if (!ours) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Deal not found");
        }
        return listing;
    }

    @Transactional(readOnly = true)
    List<DealView> deals(UUID buyerOrgId) {
        List<SaleListing> accepted = listings.findAcceptedBy(buyerOrgId);
        Map<UUID, List<ListingEvent>> history = history(accepted);
        List<DealView> deals = new ArrayList<>();
        for (SaleListing l : accepted) {
            CardOffer offer = offers.findById(l.getOfferId()).orElseThrow();
            CardInputs in = OfferService.inputs(offer);
            List<ListingEvent> h = history.getOrDefault(l.getId(), List.of());
            ListingEvent acceptedEvent = h.stream().filter(e -> e.getType() == ListingEvent.Type.ACCEPTED).findFirst().orElseThrow();
            Directory.Contact seller = directory.contact(l.getOrgId(), l.getListedBy());
            Progress progress = progress(h);
            boolean owed = progress != null && progress.stage() != DealStage.RETURNED && progress.stage() != DealStage.BACK;
            deals.add(new DealView(l.getId(), card(in).forBuyer(), offerView(offer), photos(in), new Party(seller.orgName(), seller.email()),
                    acceptedEvent.getCreatedAt(), withoutPrintable(label(l.getId())), outcome(h), undone(h, ListingEvent.Type.CANCELLED),
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
        requireDeal(buyerOrgId, listingId);
        requireNotCancelled(listingId);
        requireShippable(listingId, note, byAdmin);
        recordOutcome(ListingEvent.received(listingId, buyerOrgId, currentUser.id(), adminNote(note, byAdmin)));
    }

    /** The buyer says they paid the seller, once the card arrived. A price other than the offer needs a note. */
    @Transactional
    void paid(UUID buyerOrgId, UUID listingId, BigDecimal pricePaid, String note) {
        SaleListing listing = lockDeal(requireDeal(buyerOrgId, listingId));
        Progress progress = progress(history(List.of(listing)).getOrDefault(listingId, List.of()));
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
        requireDeal(buyerOrgId, listingId);
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
        Map<UUID, List<ListingEvent>> history = history(accepted);
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
                    new Party(seller.orgName(), seller.email()), card(in), offerView(offer), photos(in), acceptedEvent.getCreatedAt()));
        }
        return waiting;
    }

    @Transactional(readOnly = true)
    Overdue overdue() {
        Instant now = Instant.now();
        List<SaleListing> accepted = listings.findAllAccepted();
        Map<UUID, List<ListingEvent>> history = history(accepted);
        int overdue = 0;
        for (SaleListing l : accepted) {
            List<ListingEvent> h = history.getOrDefault(l.getId(), List.of());
            if (status(l, h, now) == Status.ACCEPTED
                    && shipping.label(l.getId()).filter(x -> overdue(x.getCreatedAt(), progress(h).stage(), now)).isPresent()) {
                overdue++;
            }
        }
        return new Overdue(overdue);
    }

    /** A label is overdue past its ship-by date, until the seller says it shipped or the card is shown to have. */
    static boolean overdue(Instant labelledAt, DealStage stage, Instant now) {
        return stage == DealStage.SOLD && labelledAt.plus(SHIP_WITHIN).isBefore(now);
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
        List<ListingEvent> h = history(List.of(listing)).getOrDefault(listingId, List.of());
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
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, "listing:" + listing.getCardItemId());
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

    /** 409 while the latest deal on the card is a "not genuine" return we haven't reviewed: no new offer until we have. */
    @Transactional(readOnly = true)
    void requireNotHeld(UUID cardItemId) {
        listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId).ifPresent(l -> {
            Progress progress = progress(history(List.of(l)).getOrDefault(l.getId(), List.of()));
            if (progress != null && progress.held()) {
                throw conflict(HELD);
            }
        });
    }

    private void requireNotCancelled(UUID listingId) {
        if (events.existsByListingIdAndType(listingId, ListingEvent.Type.CANCELLED)) {
            throw conflict("We cancelled this deal, so there's nothing more to do on it.");
        }
    }

    private void lockListings(UUID cardItemId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, "listing:" + cardItemId);
    }

    /** 409 with the reason for the listing's state, unless it is open. */
    private void requireOpen(SaleListing listing, Map<Status, String> reasons) {
        Status status = status(listing, history(List.of(listing)).getOrDefault(listing.getId(), List.of()), Instant.now());
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

    Map<UUID, List<ListingEvent>> history(List<SaleListing> ls) {
        if (ls.isEmpty()) {
            return Map.of();
        }
        return events.findByListingIdInOrderByCreatedAtAsc(ls.stream().map(SaleListing::getId).toList()).stream()
                .collect(Collectors.groupingBy(ListingEvent::getListingId, LinkedHashMap::new, Collectors.toList()));
    }

    static Status status(SaleListing listing, List<ListingEvent> history, Instant now) {
        Set<ListingEvent.Type> types = history.stream().map(ListingEvent::getType)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ListingEvent.Type.class)));
        if (types.contains(ListingEvent.Type.CANCELLED)) {
            return Status.CANCELLED;
        }
        if (types.contains(ListingEvent.Type.CARD_BACK)) {
            return Status.BACK;
        }
        if (types.contains(ListingEvent.Type.ACCEPTED)) {
            return Status.ACCEPTED;
        }
        if (types.contains(ListingEvent.Type.WITHDRAWN)) {
            return Status.WITHDRAWN;
        }
        if (types.contains(ListingEvent.Type.VOIDED)) {
            return Status.VOIDED;
        }
        return listing.getExpiresAt().isAfter(now) ? Status.OPEN : Status.EXPIRED;
    }

    ListingView listingView(SaleListing l, List<ListingEvent> history) {
        Status status = status(l, history, Instant.now());
        ListingEvent closed = history.stream()
                .filter(e -> e.getType() == ListingEvent.Type.ACCEPTED || e.getType() == ListingEvent.Type.WITHDRAWN
                        || e.getType() == ListingEvent.Type.VOIDED)
                .findFirst().orElse(null);
        Party buyer = null;
        if (closed != null && closed.getType() == ListingEvent.Type.ACCEPTED) {
            Directory.Contact c = directory.contact(closed.getActorOrgId(), closed.getActorUserId());
            buyer = new Party(c.orgName(), c.email());
        }
        UndoView undone = status == Status.CANCELLED ? undone(history, ListingEvent.Type.CANCELLED)
                : status == Status.VOIDED ? undone(history, ListingEvent.Type.VOIDED) : null;
        Progress progress = progress(history);
        // Once it's used, nobody needs the printable label, and printing it again could only send a second parcel.
        // Once we cancelled, it's ours to refund: it isn't offered to the seller to print, nor is the buyer's address on it.
        boolean labelUsed = progress != null && progress.stage() != DealStage.SOLD;
        LabelView label = labelUsed || status == Status.CANCELLED ? withoutPrintable(label(l.getId())) : label(l.getId());
        return new ListingView(l.getId(), l.getOfferId(), status, l.getCreatedAt(), l.getExpiresAt(),
                closed == null ? null : closed.getCreatedAt(), buyer, label, outcome(history), undone, progress);
    }

    /** Where a deal is, from what's been recorded; null with no buyer yet, or once cancelled. */
    static Progress progress(List<ListingEvent> history) {
        boolean live = history.stream().anyMatch(e -> e.getType() == ListingEvent.Type.ACCEPTED)
                && history.stream().noneMatch(e -> e.getType() == ListingEvent.Type.CANCELLED);
        if (!live) {
            return null;
        }
        Instant shippedAt = null;
        Instant arrivedAt = null;
        Instant paidAt = null;
        BigDecimal pricePaid = null;
        String paidNote = null;
        Instant confirmedAt = null;
        ListingEvent dispute = null;
        ListingEvent resolved = null;
        boolean returned = false;
        boolean held = false;
        Instant cardBackAt = null;
        for (ListingEvent e : history) {
            switch (e.getType()) {
                case SHIPPED -> shippedAt = e.getCreatedAt();
                case INSPECTED -> arrivedAt = e.getCreatedAt();
                case RECEIVED -> {
                    arrivedAt = e.getCreatedAt();
                    if (e.getPricePaid() != null) { // from before payment was a step of its own
                        paidAt = e.getCreatedAt();
                        pricePaid = e.getPricePaid();
                        paidNote = e.getNote();
                    }
                }
                case PAID -> {
                    paidAt = e.getCreatedAt();
                    pricePaid = e.getPricePaid();
                    paidNote = e.getNote();
                }
                case PAYMENT_CONFIRMED -> {
                    confirmedAt = e.getCreatedAt();
                    dispute = null;
                }
                case RETURNED -> {
                    returned = true;
                    held = e.getDeclinedReason() == ListingEvent.DeclinedReason.NOT_GENUINE;
                }
                case DISPUTED -> dispute = e;
                case RESOLVED -> {
                    resolved = e;
                    dispute = null;
                    held = false;
                }
                case CARD_BACK -> cardBackAt = e.getCreatedAt();
                default -> { }
            }
        }
        DealStage stage = cardBackAt != null ? DealStage.BACK
                : returned ? DealStage.RETURNED
                : paidAt != null || confirmedAt != null ? DealStage.PAID
                : arrivedAt != null ? DealStage.ARRIVED
                : shippedAt != null ? DealStage.SHIPPED
                : DealStage.SOLD;
        return new Progress(stage, shippedAt, arrivedAt, arrivedAt == null ? null : arrivedAt.plus(PAY_WITHIN), paidAt, pricePaid, paidNote,
                confirmedAt, dispute == null ? null : dispute.getCreatedAt(), dispute == null ? null : dispute.getNote(),
                resolved == null ? null : new UndoView(resolved.getNote(), resolved.getCreatedAt()), held, cardBackAt);
    }

    /** What on this deal is waiting for the admin: an open DISPUTED, or a RETURNED nobody has reviewed. Null when nothing is. */
    static ListingEvent awaitingAdmin(List<ListingEvent> history) {
        ListingEvent waiting = null;
        for (ListingEvent e : history) {
            switch (e.getType()) {
                case RETURNED, DISPUTED -> waiting = e;
                case PAYMENT_CONFIRMED, RESOLVED, CANCELLED -> waiting = null;
                default -> { }
            }
        }
        return waiting;
    }

    private static UndoView undone(List<ListingEvent> history, ListingEvent.Type type) {
        return history.stream().filter(e -> e.getType() == type).findFirst()
                .map(e -> new UndoView(e.getNote(), e.getCreatedAt())).orElse(null);
    }

    /**
     * The label as a buyer sees it: carrier and tracking, but not the printable label, which shows the
     * seller's address. Buyers never learn where a seller is, so they can't meet up and deal around us
     * (Zach, 2026-10-21).
     */
    private static LabelView withoutPrintable(LabelView label) {
        return label == null ? null
                : new LabelView(label.carrier(), label.service(), label.trackingCode(), null, label.insuredValue(), label.boughtAt());
    }

    LabelView label(UUID listingId) {
        return shipping.label(listingId)
                .map(x -> new LabelView(x.getCarrier(), x.getService(), x.getTrackingCode(), x.getLabelUrl(), x.getInsuredValue(),
                        x.getCreatedAt()))
                .orElse(null);
    }

    static OutcomeView outcome(List<ListingEvent> history) {
        return history.stream()
                .filter(e -> e.getType() == ListingEvent.Type.RECEIVED || e.getType() == ListingEvent.Type.RETURNED
                        || e.getType() == ListingEvent.Type.INSPECTED)
                .findFirst()
                .map(e -> new OutcomeView(e.getType().name(), e.getPricePaid(),
                        e.getDeclinedReason() == null ? null : e.getDeclinedReason().name(), e.getInspectedGrade(), e.getNote(),
                        e.getCreatedAt()))
                .orElse(null);
    }

    /** The offer, with whether we voided it. */
    OfferView offerView(CardOffer o) {
        return offerView(o, voids.findByOfferId(o.getId()).orElse(null));
    }

    OfferView offerView(CardOffer o, CardOfferVoid voided) {
        List<RungView> ladder = OfferService.ladder(o).stream()
                .map(r -> new RungView(r.grade().toString(), r.probability(), r.price())).toList();
        return new OfferView(o.getId(), o.getPrice(), o.getFmv(), o.getConfidence(), o.getBuyerMargin(), o.isGraded(),
                o.isSubjectToAuthentication(), ladder, o.getCreatedAt(), o.getExpiresAt(), !o.getExpiresAt().isAfter(Instant.now()),
                voided == null ? null : new UndoView(voided.getReason(), voided.getCreatedAt()),
                properties.showSoldPrices() ? sold(OfferService.inputs(o)) : null, lessSureBecause(o));
    }

    /** The grades with sold comps of their own, from the prices stored with the offer: never a fresh query. */
    static List<SoldView> sold(CardInputs in) {
        if (in.prices() == null || in.prices().grades() == null) {
            return List.of();
        }
        return in.prices().grades().stream()
                .filter(r -> r.method() == GradePrice.Method.OBSERVED && r.n() > 0 && r.gross() != null)
                .map(r -> new SoldView(in.prices().gradingCompany(), r.grade().toString(), r.gross(), r.n(), r.windowDays()))
                .toList();
    }

    /** CardValuator's confidence_base detail, e.g. "photo B, comps C (weighted sales 3.20)". */
    private static final Pattern BASE_DETAIL = Pattern.compile("photo ([A-D]), comps ([A-D])");

    /** Why the offer's confidence is below A, from the steps its stored trace took to reach it. */
    static List<String> lessSureBecause(CardOffer o) {
        List<String> reasons = new ArrayList<>();
        for (JsonNode step : OfferService.JSON.readTree(o.getTrace())) {
            String subject = step.path("subject").asString("");
            String rule = step.path("rule").asString("");
            if (subject.equals("confidence_base")) {
                Matcher m = BASE_DETAIL.matcher(step.path("detail").asString(""));
                if (m.find()) {
                    if (!m.group(1).equals("A")) {
                        reasons.add(LessSure.PHOTOS.name());
                    }
                    if (!m.group(2).equals("A")) {
                        reasons.add(LessSure.FEW_SALES.name());
                    }
                }
            } else if (subject.equals("offer_confidence_penalty") && rule.startsWith("price table")) {
                reasons.add(LessSure.OLD_SALES.name());
            } else if (subject.equals("offer_confidence") && rule.startsWith("derived price")) {
                reasons.add(LessSure.ESTIMATED_GRADES.name());
            }
        }
        return reasons;
    }

    static CardView card(CardInputs in) {
        Map<String, BigDecimal> distribution = new LinkedHashMap<>();
        if (in.assessment() != null) {
            in.assessment().distribution().forEach((g, p) -> distribution.put(g.toString(), p));
        }
        CardInputs.Identity id = in.identity();
        return new CardView(id.name(), id.setName(), id.number(), id.language(), id.edition(), id.variant(), in.label(), distribution,
                in.graded() == null ? null : in.graded().gradingCompany() + " " + in.graded().grade().stripTrailingZeros().toPlainString());
    }

    /**
     * The photos the offer was priced on: each side's newest, front, back then close-up, never superseded
     * attempts. Never the uploads themselves: their EXIF can say where the seller is.
     */
    private List<PhotoLink> photos(CardInputs in) {
        return current(in.photos()).stream()
                .map(p -> new PhotoLink(p.side(), blobs.presignDownload(storage.photoBucket(), CardItems.shownKey(p.storageKey(), p.cropKey()))
                        .toString(), blobs.presignDownload(storage.photoBucket(), CardItems.exactKey(p.storageKey())).toString()))
                .toList();
    }

    /** Each side's newest photo (the list is oldest first), in the order front, back, close-up. */
    static List<CardInputs.Photo> current(List<CardInputs.Photo> oldestFirst) {
        Map<String, CardInputs.Photo> latest = new LinkedHashMap<>();
        for (String side : List.of("FRONT", "BACK", "DETAIL")) {
            oldestFirst.stream().filter(p -> side.equals(p.side())).reduce((a, b) -> b).ifPresent(p -> latest.put(side, p));
        }
        return List.copyOf(latest.values());
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

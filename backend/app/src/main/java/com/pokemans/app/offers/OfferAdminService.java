package com.pokemans.app.offers;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.identity.Directory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The admin's undo for instant offers (Zach, 2026-10-21), by appending, never editing: void an offer
 * (taking it off the market if it's posted), cancel a deal a buyer took, and mark a cancelled deal's
 * label to be refunded. Each is a new row with who, when and why. Also what's waiting for the admin
 * after a sale: a seller who says they weren't paid, and returns to review. No access checks:
 * /api/admin/** needs the admin role after an MFA login (SecurityConfig).
 */
@Service
class OfferAdminService {

    /** @param byUserId the admin who did it; by is their email, when the identity provider shared one */
    record Undo(String reason, Instant at, UUID byUserId, String by) {
    }

    /**
     * @param providerStatus what the label's provider answered, or null if it couldn't be asked
     * @param providerError  why it couldn't be asked
     */
    record Refund(String reason, Instant at, UUID byUserId, String by, String providerStatus, String providerError) {
    }

    /** The offer's latest listing, as the admin sees it. */
    record Listing(UUID id, ListingService.Status status, Instant listedAt, Instant closedAt, UUID buyerOrgId,
                   ListingService.Party buyer, ListingService.LabelView label, Refund labelRefund, String outcome, Undo undone) {
    }

    record Offer(UUID offerId, UUID orgId, String sellerName, UUID cardItemId, ListingService.CardView card, BigDecimal price,
                 BigDecimal fmv, String confidence, Instant createdAt, Instant expiresAt, boolean expired, Undo voided, Listing listing) {
    }

    /** NOT_PAID: the seller says they weren't paid. RETURNED: a return to review. RETURN_DISPUTED: the seller disagrees with one. */
    enum AttentionKind { NOT_PAID, RETURNED, RETURN_DISPUTED }

    /**
     * Something on a deal the admin has to act on.
     *
     * @param note     what the seller or buyer said, if anything
     * @param outcome  the return, or the arrival, if recorded
     * @param progress what each side has said so far
     */
    record Attention(UUID listingId, AttentionKind kind, Instant since, String note, UUID sellerOrgId, UUID cardItemId,
                     ListingService.CardView card, BigDecimal price, ListingService.Party buyer, ListingService.Party seller,
                     String trackingCode, ListingService.OutcomeView outcome, ListingService.Progress progress) {
    }

    private final CardOfferRepository offers;
    private final CardOfferVoidRepository voids;
    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final ListingService listingService;
    private final Shipping shipping;
    private final Directory directory;
    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;

    OfferAdminService(CardOfferRepository offers, CardOfferVoidRepository voids, SaleListingRepository listings,
                      ListingEventRepository events, ListingService listingService, Shipping shipping, Directory directory,
                      CurrentUser currentUser, JdbcTemplate jdbc) {
        this.offers = offers;
        this.voids = voids;
        this.listings = listings;
        this.events = events;
        this.listingService = listingService;
        this.shipping = shipping;
        this.directory = directory;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    /** The newest offers, newest first, with their latest listing. */
    @Transactional(readOnly = true)
    List<Offer> recent() {
        return views(offers.findTop200ByOrderByCreatedAtDesc());
    }

    /** Deals waiting for the admin, longest-waiting first. */
    @Transactional(readOnly = true)
    List<Attention> attention() {
        List<SaleListing> taken = listings.findAllAccepted();
        Map<UUID, List<ListingEvent>> history = listingService.history(taken);
        List<Attention> waiting = new ArrayList<>();
        for (SaleListing l : taken) {
            List<ListingEvent> h = history.getOrDefault(l.getId(), List.of());
            ListingEvent raised = ListingService.awaitingAdmin(h);
            if (raised == null) {
                continue;
            }
            ListingService.OutcomeView outcome = ListingService.outcome(h);
            boolean returned = outcome != null && outcome.type().equals("RETURNED");
            AttentionKind kind = raised.getType() == ListingEvent.Type.RETURNED ? AttentionKind.RETURNED
                    : returned ? AttentionKind.RETURN_DISPUTED : AttentionKind.NOT_PAID;
            ListingEvent accepted = h.stream().filter(e -> e.getType() == ListingEvent.Type.ACCEPTED).findFirst().orElseThrow();
            CardOffer offer = offers.findById(l.getOfferId()).orElseThrow();
            var buyer = directory.contact(accepted.getActorOrgId(), accepted.getActorUserId());
            var seller = directory.contact(l.getOrgId(), l.getListedBy());
            waiting.add(new Attention(l.getId(), kind, raised.getCreatedAt(), raised.getNote(), l.getOrgId(), l.getCardItemId(),
                    ListingService.card(OfferService.inputs(offer)), offer.getPrice(),
                    new ListingService.Party(buyer.orgName(), buyer.email()), new ListingService.Party(seller.orgName(), seller.email()),
                    shipping.label(l.getId()).map(ShippingLabel::getTrackingCode).orElse(null), outcome, ListingService.progress(h)));
        }
        waiting.sort(Comparator.comparing(Attention::since));
        return waiting;
    }

    /**
     * Closes what's waiting on a deal, saying how: both sides see the note. For a "not genuine" return
     * it also lifts the hold on a new offer, so a card that looks fake gets its counterfeit finding first.
     */
    @Transactional
    void resolve(UUID listingId, String how) {
        SaleListing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deal not found"));
        lockCard(listing.getCardItemId());
        if (ListingService.awaitingAdmin(listingService.history(List.of(listing)).getOrDefault(listingId, List.of())) == null) {
            throw conflict("Nothing is waiting on this deal.");
        }
        events.saveAndFlush(ListingEvent.resolved(listingId, currentUser.id(), how));
    }

    /**
     * Voids an offer so it can't be posted. If it is on the market, the listing comes down with it. One a
     * buyer has taken is undone by cancelling the deal instead.
     */
    @Transactional
    Offer voidOffer(UUID offerId, String reason) {
        CardOffer offer = offers.findById(offerId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer not found"));
        lockCard(offer.getCardItemId());
        if (voids.findByOfferId(offerId).isPresent()) {
            throw conflict("This offer is already void.");
        }
        List<SaleListing> posted = listings.findByOfferIdIn(List.of(offerId));
        Map<UUID, List<ListingEvent>> history = listingService.history(posted);
        Instant now = Instant.now();
        for (SaleListing l : posted) {
            ListingService.Status status = ListingService.status(l, history.getOrDefault(l.getId(), List.of()), now);
            if (status == ListingService.Status.ACCEPTED) {
                throw conflict("A buyer has taken this card: cancel the deal instead.");
            }
            if (status == ListingService.Status.OPEN) {
                save(ListingEvent.voided(l.getId(), currentUser.id(), reason), "A buyer took this card just now: cancel the deal instead.");
            }
        }
        voids.saveAndFlush(new CardOfferVoid(offerId, reason, currentUser.id()));
        return views(List.of(offer)).getFirst();
    }

    /**
     * Cancels a deal a buyer took, before an arrival or return is recorded. Its offer is voided too, so
     * the card is free for a new offer at today's price.
     */
    @Transactional
    Offer cancelDeal(UUID listingId, String reason) {
        SaleListing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deal not found"));
        lockCard(listing.getCardItemId());
        List<ListingEvent> history = listingService.history(List.of(listing)).getOrDefault(listingId, List.of());
        switch (ListingService.status(listing, history, Instant.now())) {
            case ACCEPTED -> { }
            case CANCELLED -> throw conflict("This deal is already cancelled.");
            case BACK -> throw conflict("This card already came back to the seller.");
            default -> throw conflict("No buyer has taken this card, so there's no deal to cancel: void the offer instead.");
        }
        ListingService.OutcomeView outcome = ListingService.outcome(history);
        if (outcome != null) {
            throw conflict("The buyer already recorded this card as " + outcome.type().toLowerCase() + ", so the deal can't be cancelled.");
        }
        save(ListingEvent.cancelled(listingId, currentUser.id(), reason), "The buyer recorded the card's arrival just now.");
        if (voids.findByOfferId(listing.getOfferId()).isEmpty()) {
            voids.saveAndFlush(new CardOfferVoid(listing.getOfferId(), "Deal cancelled: " + reason, currentUser.id()));
        }
        return views(List.of(offers.findById(listing.getOfferId()).orElseThrow())).getFirst();
    }

    /**
     * Marks a cancelled deal's label to be refunded, and asks the provider that sold it. A live deal's
     * label is the one the seller ships with, so the deal has to be cancelled first.
     */
    @Transactional
    Offer refundLabel(UUID listingId, String reason) {
        SaleListing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deal not found"));
        ShippingLabel label = shipping.label(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No label was bought for this deal."));
        if (!events.existsByListingIdAndType(listingId, ListingEvent.Type.CANCELLED)) {
            throw conflict("Cancel the deal first: until then this is the label the seller ships with.");
        }
        shipping.refund(label, reason);
        return views(List.of(offers.findById(listing.getOfferId()).orElseThrow())).getFirst();
    }

    private List<Offer> views(List<CardOffer> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = page.stream().map(CardOffer::getId).toList();
        Map<UUID, CardOfferVoid> voided = voids.findByOfferIdIn(ids).stream()
                .collect(Collectors.toMap(CardOfferVoid::getOfferId, Function.identity()));
        Map<UUID, SaleListing> latest = listings.findByOfferIdIn(ids).stream()
                .collect(Collectors.toMap(SaleListing::getOfferId, Function.identity(),
                        (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b));
        Map<UUID, List<ListingEvent>> history = listingService.history(new ArrayList<>(latest.values()));
        Instant now = Instant.now();
        List<Offer> views = new ArrayList<>();
        for (CardOffer o : page) {
            CardOfferVoid v = voided.get(o.getId());
            SaleListing l = latest.get(o.getId());
            views.add(new Offer(o.getId(), o.getOrgId(), directory.orgName(o.getOrgId()), o.getCardItemId(),
                    ListingService.card(OfferService.inputs(o)), o.getPrice(), o.getFmv(), o.getConfidence(), o.getCreatedAt(),
                    o.getExpiresAt(), !o.getExpiresAt().isAfter(now),
                    v == null ? null : new Undo(v.getReason(), v.getCreatedAt(), v.getVoidedBy(), directory.email(v.getVoidedBy())),
                    l == null ? null : listing(l, history.getOrDefault(l.getId(), List.of()), now)));
        }
        return views;
    }

    private Listing listing(SaleListing l, List<ListingEvent> history, Instant now) {
        ListingService.Status status = ListingService.status(l, history, now);
        ListingEvent closed = history.stream().filter(e -> e.getType() == ListingEvent.Type.ACCEPTED
                || e.getType() == ListingEvent.Type.WITHDRAWN || e.getType() == ListingEvent.Type.VOIDED).findFirst().orElse(null);
        ListingEvent accepted = history.stream().filter(e -> e.getType() == ListingEvent.Type.ACCEPTED).findFirst().orElse(null);
        ListingService.Party buyer = null;
        if (accepted != null) {
            var c = directory.contact(accepted.getActorOrgId(), accepted.getActorUserId());
            buyer = new ListingService.Party(c.orgName(), c.email());
        }
        Undo undone = history.stream()
                .filter(e -> e.getType() == ListingEvent.Type.CANCELLED || e.getType() == ListingEvent.Type.VOIDED)
                .max(Comparator.comparing(ListingEvent::getCreatedAt))
                .map(e -> new Undo(e.getNote(), e.getCreatedAt(), e.getActorUserId(), directory.email(e.getActorUserId())))
                .orElse(null);
        Optional<ShippingLabel> label = shipping.label(l.getId());
        Refund refund = label.map(x -> shipping.refunds(List.of(x.getId())).get(x.getId()))
                .map(r -> new Refund(r.getReason(), r.getCreatedAt(), r.getRequestedBy(), directory.email(r.getRequestedBy()), r.getProviderStatus(),
                        r.getProviderError()))
                .orElse(null);
        ListingService.OutcomeView outcome = ListingService.outcome(history);
        return new Listing(l.getId(), status, l.getCreatedAt(), closed == null ? null : closed.getCreatedAt(),
                accepted == null ? null : accepted.getActorOrgId(), buyer, listingService.label(l.getId()), refund,
                outcome == null ? null : outcome.type(), undone);
    }

    /** The same lock posting takes, so a void and a post (or a cancel and a repost) can't interleave. */
    private void lockCard(UUID cardItemId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, "listing:" + cardItemId);
    }

    /** Saves an admin event; the database lets a listing close once and a deal end once, so a lost race is a 409. */
    private void save(ListingEvent event, String lostRace) {
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

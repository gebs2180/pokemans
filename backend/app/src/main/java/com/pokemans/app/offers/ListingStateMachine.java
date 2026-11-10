package com.pokemans.app.offers;

import com.pokemans.app.offers.ListingViews.UndoView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where a listing and its deal are, read from the listing's history alone: pure functions over the
 * appended rows, with no repositories (review finding 23). The flow services decide what may happen
 * next from these, and the views show them.
 */
final class ListingStateMachine {

    /** The buyer terms' deadline: payment within 7 days of the card arriving. */
    static final Duration PAY_WITHIN = Duration.ofDays(7);

    static final String HELD = "A buyer said this card isn't genuine, so we review that before it can get a new offer.";

    /** The seller terms' deadline, from the label showing. */
    static final Duration SHIP_WITHIN = Duration.ofDays(3);

    /**
     * VOIDED: we took it down with its offer. CANCELLED: a buyer took it and we undid the deal (Zach,
     * 2026-10-21). BACK: a buyer took it and sent it back, and the seller has it again, so it's free for
     * a new offer.
     */
    enum Status {
        OPEN, ACCEPTED, WITHDRAWN, EXPIRED, VOIDED, CANCELLED, BACK;

        /** A buyer took the card, whatever happened after: the track record counts these as accepted. */
        boolean taken() {
            return this == ACCEPTED || this == BACK;
        }
    }

    /**
     * The one definition of a listing's status (architecture review #26): the first of these events in its
     * history decides it, in this order; with none of them it is OPEN until it expires, then EXPIRED.
     * {@code SaleListingRepository.findOpen} takes its event types from here, and {@code ListingStatusParityTest}
     * holds the {@code v_offer_outcomes} view and the {@code listing_event_closed_once} index to it.
     */
    static final Map<ListingEvent.Type, Status> DECIDED_BY = decidedBy();

    /** Events that end an open listing. Each can happen once per listing ({@code listing_event_closed_once}). */
    static final Set<ListingEvent.Type> CLOSES = Collections.unmodifiableSet(
            EnumSet.of(ListingEvent.Type.ACCEPTED, ListingEvent.Type.WITHDRAWN, ListingEvent.Type.VOIDED));

    private static Map<ListingEvent.Type, Status> decidedBy() {
        Map<ListingEvent.Type, Status> m = new LinkedHashMap<>();
        m.put(ListingEvent.Type.CANCELLED, Status.CANCELLED);
        m.put(ListingEvent.Type.CARD_BACK, Status.BACK);
        m.put(ListingEvent.Type.ACCEPTED, Status.ACCEPTED);
        m.put(ListingEvent.Type.WITHDRAWN, Status.WITHDRAWN);
        m.put(ListingEvent.Type.VOIDED, Status.VOIDED);
        return Collections.unmodifiableMap(m);
    }

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

        Progress forBuyer() {
            return new Progress(stage, shippedAt, arrivedAt, payBy, paidAt, pricePaid, paidNote, confirmedAt, disputedAt, null, review,
                    held, cardBackAt);
        }
    }

    private ListingStateMachine() {
    }

    static Status status(SaleListing listing, List<ListingEvent> history, Instant now) {
        Set<ListingEvent.Type> types = history.stream().map(ListingEvent::getType)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ListingEvent.Type.class)));
        for (Map.Entry<ListingEvent.Type, Status> rule : DECIDED_BY.entrySet()) {
            if (types.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        return listing.getExpiresAt().isAfter(now) ? Status.OPEN : Status.EXPIRED;
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

    /** A label is overdue past its ship-by date, until the seller says it shipped or the card is shown to have. */
    static boolean overdue(Instant labelledAt, DealStage stage, Instant now) {
        return stage == DealStage.SOLD && labelledAt.plus(SHIP_WITHIN).isBefore(now);
    }
}

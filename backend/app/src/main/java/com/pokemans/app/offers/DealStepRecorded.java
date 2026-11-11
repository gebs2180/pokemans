package com.pokemans.app.offers;

import java.time.Instant;
import java.util.UUID;

/**
 * A step in a sale was recorded, published inside the transaction that recorded it. Listeners that act
 * after commit see only committed steps; look the deal up with {@link Deals#deal}.
 *
 * @param listingId the listing the step belongs to
 * @param step      what happened
 * @param at        when it was recorded
 */
public record DealStepRecorded(UUID listingId, Step step, Instant at) {

    /**
     * TAKEN: a buyer accepted. ARRIVED: the card arrived genuine and as shown. PAID: the buyer says they
     * paid. PAYMENT_CONFIRMED: the seller says they got paid. RETURNED: sent back. CANCELLED: the admin
     * undid the deal.
     */
    public enum Step { TAKEN, ARRIVED, PAID, PAYMENT_CONFIRMED, RETURNED, CANCELLED }

    /** The step a listing event type is, or null for one other modules don't hear about. */
    static Step step(ListingEvent.Type type) {
        return switch (type) {
            case ACCEPTED -> Step.TAKEN;
            case RECEIVED, INSPECTED -> Step.ARRIVED;
            case PAID -> Step.PAID;
            case PAYMENT_CONFIRMED -> Step.PAYMENT_CONFIRMED;
            case RETURNED -> Step.RETURNED;
            case CANCELLED -> Step.CANCELLED;
            default -> null;
        };
    }
}

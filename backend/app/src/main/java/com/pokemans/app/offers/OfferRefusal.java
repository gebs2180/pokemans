package com.pokemans.app.offers;

/**
 * Why the offers flow says no, as a code (architecture review #27). It goes out as the problem detail's
 * {@code code} and the preview's {@code refusalCode}; code here and in the web app branches on it, never
 * on the words, which are free to change.
 */
enum OfferRefusal {

    // A card that gets no offer.
    LOT,
    NOT_IDENTIFIED,
    NOT_ASSESSED,
    PHOTOS_CHANGED,
    /** Its condition is being assessed again: it rested on a grading provider we no longer use. */
    PROVIDER_RETIRED,
    NO_PRICE_TABLE,
    /** Unvalued for a reason the valuation module didn't code. */
    UNPRICED,
    DOUBTED,
    /** Confidence D: too little sales data for a firm offer. */
    THIN_SALES,
    UNDER_MINIMUM,
    OVER_MAXIMUM,

    // Posting and buying.
    /** The offer's price moved more than the reprice threshold on today's prices. */
    PRICE_CHANGED,
    /** The buyer has no address for cards to ship to. */
    NO_BUYER_ADDRESS
}

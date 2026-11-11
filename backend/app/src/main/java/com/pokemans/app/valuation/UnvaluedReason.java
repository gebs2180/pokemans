package com.pokemans.app.valuation;

/**
 * Why a card couldn't be valued, as a code other modules can branch on (architecture review #27).
 * The words stored in {@link CardInputs#unvaluedReason()} and on a run's card lines come from here, so
 * rewording one can't change what another module does with it. Stored inputs keep the text, which
 * their hash covers; {@link #of(String)} reads it back.
 */
public enum UnvaluedReason {

    /** A photographed lot: lots are never valued. */
    LOT,
    NOT_IDENTIFIED,
    /** No current condition assessment. */
    NO_ASSESSMENT,
    /** The front or back was retaken since the card's condition was assessed: it waits to be assessed again. */
    PHOTOS_CHANGED,
    /** Its condition was built partly on a grading provider we no longer use (Ximilar, 2026-11-10): it waits to be assessed again. */
    PROVIDER_RETIRED,
    /** No price table from the card's grader (PSA for a raw card). */
    NO_PRICE_TABLE,
    /** Text this enum didn't write: kept so an unknown reason is never mistaken for a known one. */
    OTHER;

    private static final String NO_TABLE_PREFIX = "no ";
    private static final String NO_TABLE_SUFFIX = " price table";

    /** The stored words for this reason; {@link #NO_PRICE_TABLE} needs the grader, so use {@link #noPriceTable(String)}. */
    public String text() {
        return switch (this) {
            case LOT -> "photographed lot: lots aren't valued";
            case NOT_IDENTIFIED -> "not identified";
            case NO_ASSESSMENT -> "no condition assessment";
            case PHOTOS_CHANGED -> "photos retaken since its condition was assessed";
            case PROVIDER_RETIRED -> "condition assessed with a grading provider we no longer use";
            case NO_PRICE_TABLE, OTHER -> throw new IllegalStateException(name() + " has no fixed text");
        };
    }

    /** The stored words for a missing price table from this grading company. */
    public static String noPriceTable(String gradingCompany) {
        return NO_TABLE_PREFIX + gradingCompany + NO_TABLE_SUFFIX;
    }

    /** The code for stored words, or null for none. */
    public static UnvaluedReason of(String text) {
        if (text == null) {
            return null;
        }
        for (UnvaluedReason r : values()) {
            if (r != NO_PRICE_TABLE && r != OTHER && r.text().equals(text)) {
                return r;
            }
        }
        if (text.startsWith(NO_TABLE_PREFIX) && text.endsWith(NO_TABLE_SUFFIX)
                && text.length() > NO_TABLE_PREFIX.length() + NO_TABLE_SUFFIX.length()) {
            return NO_PRICE_TABLE;
        }
        return OTHER;
    }
}

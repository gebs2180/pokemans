package com.pokemans.app.catalog;

import java.util.Map;

/**
 * Reads what is printed on the front of a card from a photo: the raw material for an identity
 * suggestion, which {@link SuggestionMatcher} then looks up in the catalog. Never decides an
 * identity on its own; the reviewer confirms.
 */
interface CardReader {

    /** Stable name stored with every reading, e.g. "CLAUDE". */
    String name();

    /** Reads one image (the straightened card crop). */
    Reading read(byte[] image, String contentType);

    /**
     * What could be read. Any field is null when it couldn't be read: readers must not guess.
     *
     * @param number       collector number as printed before the slash ("11" from "11/113"), or a promo code ("SWSH050")
     * @param setTotal     the number after the slash (113), or null if none is printed
     * @param language     ISO 639-1 code of the card's text
     * @param finish       NORMAL, HOLO, REVERSE_HOLO or null if unsure
     * @param copyrightYear the latest year in the copyright line, which dates the print
     * @param model        model/version that produced the reading
     * @param raw          the reader's response as received, kept for the record
     */
    record Reading(String name, String number, Integer setTotal, String language, Boolean firstEditionStamp,
                   Variant finish, Integer copyrightYear, String setName, String notes, String model,
                   Map<String, Object> raw) {
    }
}

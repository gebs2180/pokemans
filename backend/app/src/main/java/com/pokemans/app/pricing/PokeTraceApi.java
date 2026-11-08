package com.pokemans.app.pricing;

import java.util.List;
import java.util.Map;

/**
 * The parts of the PokeTrace API we use (US market). Responses are returned as received, so they can
 * be stored verbatim on the comps. Tests fake this; {@link HttpPokeTraceApi} is the real one.
 */
interface PokeTraceApi {

    /** {@code GET /cards?search=}: single cards matching the text, at most one page. */
    List<Map<String, Object>> search(String text);

    /** {@code GET /cards/{id}}: the card with its current prices, {@code gradedOptions} and {@code conditionOptions}. */
    Map<String, Object> card(String poketraceId);

    /** {@code GET /cards/{id}/prices/{tier}/history}: every page of daily rows for the period (7d, 30d, 90d, 1y, all). */
    List<Map<String, Object>> history(String poketraceId, String tier, String period);
}

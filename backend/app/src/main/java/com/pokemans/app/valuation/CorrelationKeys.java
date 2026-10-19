package com.pokemans.app.valuation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The set and character a card's value moves with, for the correlation haircut (step 5). A set is
 * its language plus name. A character is the card name without the owner prefix ("Dark", "Light",
 * "Team Rocket's", "Misty's") or the mechanic suffix ("ex", "GX", "VMAX", "LV.X", "δ", "☆"), so
 * Charizard ex and Dark Charizard both count as Charizard.
 */
final class CorrelationKeys {

    private static final Pattern OWNER = Pattern.compile("^(?:dark|light|shining|radiant|[\\p{L}.' ]+?'s)\\s+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MECHANIC = Pattern.compile(
            "(?:\\s+|-)(?:ex|gx|v|vmax|vstar|v-union|break|lv\\.?\\s?x|prime|legend|star|δ|☆|★|tag team|prism star)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private CorrelationKeys() {
    }

    static String set(String language, String setName) {
        return (language == null ? "?" : language.toUpperCase(Locale.ROOT)) + ":" + setName;
    }

    static String character(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).replaceAll("\\s+", " ");
        String before;
        do {
            before = key;
            key = MECHANIC.matcher(key).replaceAll("").trim();
        } while (!key.equals(before) && !key.isEmpty());
        String stripped = OWNER.matcher(key).replaceFirst("").trim();
        key = stripped.isEmpty() ? key : stripped;
        return key.toLowerCase(Locale.ROOT);
    }
}

package com.pokemans.app.valuation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The set and character a card's value moves with, for the correlation haircut (step 5).
 *
 * <p>A set is its language plus name. A character is a National Pokedex species, named in English, so
 * every card of one Pokemon shares a slice whatever its language, form or owner (Zach, 2026-10-19):
 * Charizard ex, Dark Charizard, Mega Charizard X and リザードン are all "charizard". The species comes
 * from the catalog's Pokedex numbers when the provider gave them, else from the name: the longest
 * English (whole words) or Japanese species name in it. A tag team counts toward its most popular
 * partner ({@code valuation/character-popularity.txt}). A card with no species in it (a trainer, say)
 * is keyed by its own name.
 */
final class CorrelationKeys {

    private record Species(int dex, String en, String ja) {
    }

    private static final Map<Integer, Species> BY_DEX = new LinkedHashMap<>();
    private static final Map<Integer, Integer> POPULARITY = new HashMap<>();
    private static final Pattern MECHANIC = Pattern.compile(
            "(?:\\s+|-)(?:ex|gx|v|vmax|vstar|v-union|break|lv\\.?\\s?x|prime|legend|star|δ|☆|★|tag team|prism star)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static {
        lines("valuation/species.csv", line -> {
            String[] f = line.split(",", -1);
            BY_DEX.put(Integer.parseInt(f[0]), new Species(Integer.parseInt(f[0]), normalize(f[1]), normalize(f[2])));
        });
        lines("valuation/character-popularity.txt", line -> {
            int dex = Integer.parseInt(line.split("\\s+")[0]);
            POPULARITY.putIfAbsent(dex, POPULARITY.size());
        });
    }

    private CorrelationKeys() {
    }

    static String set(String language, String setName) {
        return (language == null ? "?" : language.toUpperCase(Locale.ROOT)) + ":" + setName;
    }

    /**
     * @param name   the card's name in its own language
     * @param dexIds the catalog's Pokedex numbers for the card, or empty
     */
    static String character(String name, List<Integer> dexIds) {
        List<Species> found = new ArrayList<>();
        for (Integer dex : dexIds == null ? List.<Integer>of() : dexIds) {
            Species s = BY_DEX.get(dex);
            if (s != null) {
                found.add(s);
            }
        }
        if (found.isEmpty() && name != null) {
            // Tag teams name each partner: "Reshiram & Charizard GX", "ピカチュウ&ゼクロムGX".
            for (String part : normalize(name).split("&")) {
                Species s = species(part);
                if (s != null) {
                    found.add(s);
                }
            }
        }
        if (found.isEmpty()) {
            return byName(name);
        }
        Species pick = found.getFirst();
        for (Species s : found) {
            if (rank(s) < rank(pick)) {
                pick = s;
            }
        }
        return pick.en();
    }

    /** The longest species name in one part of a card name: whole words in English, anywhere in Japanese. */
    private static Species species(String part) {
        Species best = null;
        int bestLength = 0;
        for (Species s : BY_DEX.values()) {
            int length = Math.max(wholeWord(part, s.en()) ? s.en().length() : 0, !s.ja().isEmpty() && part.contains(s.ja()) ? s.ja().length() : 0);
            if (length > bestLength) {
                best = s;
                bestLength = length;
            }
        }
        return best;
    }

    private static boolean wholeWord(String text, String word) {
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
            int end = i + word.length();
            boolean before = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            boolean after = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (before && after) {
                return true;
            }
        }
        return false;
    }

    private static int rank(Species s) {
        return POPULARITY.getOrDefault(s.dex(), Integer.MAX_VALUE);
    }

    /** No species: the name itself, without a mechanic suffix. */
    private static String byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = normalize(name).replaceAll("\\s+", " ").trim();
        String before;
        do {
            before = key;
            key = MECHANIC.matcher(key).replaceAll("").trim();
        } while (!key.equals(before) && !key.isEmpty());
        return key.isEmpty() ? before : key;
    }

    /** Full-width forms to plain (＆ to &), one apostrophe, lower case. */
    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replace('’', '\'').toLowerCase(Locale.ROOT);
    }

    private static void lines(String resource, Consumer<String> each) {
        try (InputStream in = CorrelationKeys.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + resource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (!line.isBlank() && !line.startsWith("#")) {
                        each.accept(line.strip());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

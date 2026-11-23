package com.pokemans.app.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns a reading into ranked catalog candidates. The collector number and set total do most of the
 * work: "11/113" plus the name narrows a search to one set nearly always. Confidence is a simple
 * score, not a probability; stored suggestions and the reviewer's choices let it be calibrated.
 */
class SuggestionMatcher {

    /** At most this many provider cards are fetched in detail per reading. */
    static final int MAX_CANDIDATES = 8;

    /**
     * @param why what matched, for the reviewer
     */
    record Match(CatalogProvider.ProviderCard card, double confidence, Edition edition, Variant variant, String why) {
    }

    private final CatalogProvider provider;

    SuggestionMatcher(CatalogProvider provider) {
        this.provider = provider;
    }

    /** Up to three candidates, best first; empty if nothing plausible was found. */
    List<Match> match(CardReader.Reading reading) {
        if (reading.name() == null) {
            return List.of();
        }
        String language = reading.language() == null ? "en" : reading.language();
        List<CatalogProvider.Hit> hits = List.of();
        // Readers often include subtitles the catalog leaves out ("Metagross δ Delta Species"), so
        // try the name as read, then without known subtitles, then its first word. With the number
        // first; then without it, in case the number was misread (scored lower).
        List<String> names = nameVariants(reading.name());
        // With the set known (printed, or recognised from its symbol) search within it first: the
        // provider filters on the set's name, so the right card can't fall outside the fetched
        // candidates. A set that finds nothing (misread, or named differently) falls back to name only.
        String setName = reading.setName() == null || reading.setName().isBlank() ? null : reading.setName().trim();
        search:
        for (String set : setName == null ? Collections.<String>singletonList(null) : Arrays.asList(setName, null)) {
            for (String number : reading.number() == null ? Collections.<String>singletonList(null)
                    : Arrays.asList(reading.number(), null)) {
                for (String name : names) {
                    hits = provider.search(set == null ? name : name + " " + set, number, language);
                    if (!hits.isEmpty()) {
                        break search;
                    }
                }
            }
        }
        // Anniversary reprints keep the original's printed number (11/113) but the catalog numbers
        // them within their collection (30th-c 003), so a number search can't find them. The stamp
        // the reader noticed points at the collection; search it by name.
        String reprintSet = reprintSet(reading.notes());
        List<CatalogProvider.Hit> candidates = new ArrayList<>();
        if (reprintSet != null) {
            provider.search(names.getLast(), null, language).stream()
                    .filter(h -> reprintSet.equals(h.setId()))
                    .forEach(candidates::add);
        }
        candidates.addAll(hits.stream()
                .sorted(Comparator.comparing((CatalogProvider.Hit h) -> !sameSet(reading.setName(), h.setName()))
                        .thenComparing(h -> reading.setTotal() == null || !reading.setTotal().equals(h.setTotal())))
                .toList());
        List<Match> matches = new ArrayList<>();
        for (CatalogProvider.Hit hit : candidates.stream().limit(MAX_CANDIDATES).toList()) {
            provider.card(hit.externalId(), language).map(card -> score(reading, card, reprintSet)).ifPresent(matches::add);
        }
        matches.sort(Comparator.comparingDouble(Match::confidence).reversed());
        // Two candidates scoring the same can't both be likely: lower the confidence of a tie.
        if (matches.size() > 1 && matches.get(0).confidence() == matches.get(1).confidence()) {
            matches.replaceAll(m -> new Match(m.card(), m.confidence() * 0.6, m.edition(), m.variant(), m.why() + ", tied"));
        }
        return matches.stream().filter(m -> m.confidence() >= 0.2).limit(3).toList();
    }

    /** Printed subtitles that aren't part of the card's name in the catalog. */
    private static final List<String> SUBTITLES = List.of("delta species", "δ delta species", "pokémon", "pokemon");

    /** The name as read, then without subtitles, then its first word; no repeats. */
    static List<String> nameVariants(String name) {
        String trimmed = name.trim().replaceAll("\\s+", " ");
        String withoutSubtitles = trimmed;
        for (String subtitle : SUBTITLES) {
            withoutSubtitles = withoutSubtitles.replaceAll("(?i)\\s*" + Pattern.quote(subtitle) + "\\b", "");
        }
        if (trimmed.toLowerCase(Locale.ROOT).contains("delta species") && !withoutSubtitles.contains("δ")) {
            withoutSubtitles = withoutSubtitles + " δ";
        }
        String firstWord = trimmed.split(" ")[0];
        return Stream.of(trimmed, withoutSubtitles.trim(), firstWord).distinct().toList();
    }

    /**
     * The reprint collection a stamp in the reader's notes points at, or null. The 30th anniversary
     * stamp (Pikachu with "30") is checked first: readers also call it "Celebrations-style".
     */
    static String reprintSet(String notes) {
        if (notes == null) {
            return null;
        }
        if (THIRTIETH.matcher(notes).find()) {
            return "30th-c";
        }
        if (TWENTY_FIFTH.matcher(notes).find()) {
            return "cel25c";
        }
        return null;
    }

    private static final Pattern THIRTIETH = Pattern.compile("(?i)30th|\\b3 0\\b|\"30\"");
    private static final Pattern TWENTY_FIFTH = Pattern.compile("(?i)25th|celebrations");

    /**
     * A set match is worth enough that name + set + year clears the threshold on its own, but less
     * than a collector number: without a number the best possible is still well under certain.
     */
    static final double SET_NAME_SCORE = 0.4;

    /** Whether two set names are the same set, ignoring case and punctuation ("Skyridge" vs "skyridge."). */
    static boolean sameSet(String read, String catalog) {
        if (read == null || catalog == null) {
            return false;
        }
        return setKey(read).equals(setKey(catalog)) && !setKey(read).isEmpty();
    }

    private static String setKey(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static Match score(CardReader.Reading reading, CatalogProvider.ProviderCard card, String reprintSet) {
        double score = 0;
        List<String> why = new ArrayList<>();
        if (reprintSet != null) {
            if (reprintSet.equals(card.setId())) {
                score += 0.75;
                why.add("anniversary stamp");
            } else {
                score -= 0.3;
                why.add("stamp says reprint");
            }
        }
        if (reading.number() != null && sameNumber(reading.number(), card.number())) {
            score += 0.4;
            why.add("number");
        }
        if (reading.setTotal() != null && Objects.equals(reading.setTotal(), card.setOfficialCount())) {
            score += 0.35;
            why.add("set size");
        }
        // A subset numbered apart from the main set (Skyridge's H09/H32 holos): the letter the read number or total
        // carries tells it from the plain card of the same name, which otherwise scores the same.
        String readPrefix = subsetPrefix(reading);
        String cardPrefix = numberPrefix(card.number());
        if (readPrefix != null && readPrefix.equals(cardPrefix)) {
            score += 0.1;
            why.add("subset number");
        }
        boolean holoRead = saysHolo(reading);
        if (holoRead && card.variants().contains(Variant.HOLO)) {
            score += 0.05;
            why.add("holo");
        }
        if (sameSet(reading.setName(), card.setName())) {
            score += SET_NAME_SCORE;
            why.add("set name");
        }
        if (nameVariants(reading.name()).stream().anyMatch(n -> normalize(n).equals(normalize(card.name())))) {
            score += 0.15;
            why.add("exact name");
        } else {
            score += 0.05;
            why.add("similar name");
        }
        if (reading.copyrightYear() != null && card.setReleaseDate() != null) {
            int released = card.setReleaseDate().getYear();
            if (reading.copyrightYear() == released || reading.copyrightYear() == released - 1) {
                score += 0.05;
                why.add("year");
            }
        }
        Edition edition = Boolean.TRUE.equals(reading.firstEditionStamp()) && card.editions().contains(Edition.FIRST_EDITION)
                ? Edition.FIRST_EDITION : Edition.UNLIMITED;
        // Some catalog entries are sparse (no rarity, only "normal" listed); there the reader's eye wins.
        boolean sparse = card.rarity() == null || card.rarity().equalsIgnoreCase("none");
        Variant variant = Optional.ofNullable(reading.finish()).or(() -> Optional.ofNullable(holoRead ? Variant.HOLO : null))
                .filter(f -> sparse || card.variants().contains(f))
                .orElse(card.variants().isEmpty() ? Variant.NORMAL : card.variants().getFirst());
        return new Match(card, Math.max(0, Math.min(0.95, score)), edition, variant, String.join(", ", why));
    }

    private static final Pattern PREFIXED = Pattern.compile("^\\s*([A-Za-z]+)\\s*\\d");
    private static final Pattern PREFIXED_TOTAL = Pattern.compile("/\\s*([A-Za-z]+)\\s*\\d");
    private static final Pattern HOLO = Pattern.compile("(?i)(?<!non[- ]?)(?<!reverse[- ]?)\\bholo");

    /**
     * The letter prefix of a subset number the reader saw, upper case ("H" for H09 or /H32), or null. Taken from the
     * number, else from a total the reader gave as text (which can't fill {@code setTotal}), else from its notes.
     */
    static String subsetPrefix(CardReader.Reading reading) {
        String fromNumber = numberPrefix(reading.number());
        if (fromNumber != null) {
            return fromNumber;
        }
        if (reading.raw() != null && reading.raw().get("input") instanceof java.util.Map<?, ?> input
                && input.get("set_total") instanceof String total) {
            String fromTotal = numberPrefix(total);
            if (fromTotal != null) {
                return fromTotal;
            }
        }
        if (reading.notes() != null) {
            java.util.regex.Matcher m = PREFIXED_TOTAL.matcher(reading.notes());
            if (m.find()) {
                return m.group(1).toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** The letters before a collector number's digits, upper case ("H" for "H09", "SV" for "SV65"), or null. */
    static String numberPrefix(String number) {
        if (number == null) {
            return null;
        }
        java.util.regex.Matcher m = PREFIXED.matcher(number);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** Whether the reader saw a holo: its finish, or "holo" in its notes (not "non-holo" or "reverse holo"). */
    static boolean saysHolo(CardReader.Reading reading) {
        if (reading.finish() != null) {
            return reading.finish() == Variant.HOLO;
        }
        return reading.notes() != null && HOLO.matcher(reading.notes()).find();
    }

    private static boolean sameNumber(String read, String catalog) {
        return Objects.equals(TcgdexCatalogProvider.normalizeNumber(read), TcgdexCatalogProvider.normalizeNumber(catalog));
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace("delta", "δ").replaceAll("\\s+", " ").trim();
    }
}

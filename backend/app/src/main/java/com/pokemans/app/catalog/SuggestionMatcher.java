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
        for (String number : reading.number() == null ? Collections.<String>singletonList(null)
                : Arrays.asList(reading.number(), null)) {
            for (String name : names) {
                hits = provider.search(name, number, language);
                if (!hits.isEmpty()) {
                    break;
                }
            }
            if (!hits.isEmpty()) {
                break;
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
        candidates.addAll(hits);
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
        Variant variant = Optional.ofNullable(reading.finish()).filter(f -> sparse || card.variants().contains(f))
                .orElse(card.variants().isEmpty() ? Variant.NORMAL : card.variants().getFirst());
        return new Match(card, Math.max(0, Math.min(0.95, score)), edition, variant, String.join(", ", why));
    }

    private static boolean sameNumber(String read, String catalog) {
        return Objects.equals(TcgdexCatalogProvider.normalizeNumber(read), TcgdexCatalogProvider.normalizeNumber(catalog));
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace("delta", "δ").replaceAll("\\s+", " ").trim();
    }
}

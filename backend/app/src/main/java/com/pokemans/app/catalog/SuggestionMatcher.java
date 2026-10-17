package com.pokemans.app.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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
        List<CatalogProvider.Hit> hits = provider.search(reading.name(), reading.number(), language);
        if (hits.isEmpty() && reading.number() != null) {
            // The number may be misread; fall back to the name alone, scored lower.
            hits = provider.search(reading.name(), null, language);
        }
        List<Match> matches = new ArrayList<>();
        for (CatalogProvider.Hit hit : hits.stream().limit(MAX_CANDIDATES).toList()) {
            provider.card(hit.externalId(), language).map(card -> score(reading, card)).ifPresent(matches::add);
        }
        matches.sort(Comparator.comparingDouble(Match::confidence).reversed());
        // Two candidates scoring the same can't both be likely: lower the confidence of a tie.
        if (matches.size() > 1 && matches.get(0).confidence() == matches.get(1).confidence()) {
            matches.replaceAll(m -> new Match(m.card(), m.confidence() * 0.6, m.edition(), m.variant(), m.why() + ", tied"));
        }
        return matches.stream().filter(m -> m.confidence() >= 0.2).limit(3).toList();
    }

    static Match score(CardReader.Reading reading, CatalogProvider.ProviderCard card) {
        double score = 0;
        List<String> why = new ArrayList<>();
        if (reading.number() != null && sameNumber(reading.number(), card.number())) {
            score += 0.4;
            why.add("number");
        }
        if (reading.setTotal() != null && Objects.equals(reading.setTotal(), card.setOfficialCount())) {
            score += 0.35;
            why.add("set size");
        }
        if (normalize(reading.name()).equals(normalize(card.name()))) {
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
        Variant variant = Optional.ofNullable(reading.finish()).filter(card.variants()::contains)
                .orElse(card.variants().isEmpty() ? Variant.NORMAL : card.variants().getFirst());
        return new Match(card, Math.min(0.95, score), edition, variant, String.join(", ", why));
    }

    private static boolean sameNumber(String read, String catalog) {
        return Objects.equals(TcgdexCatalogProvider.normalizeNumber(read), TcgdexCatalogProvider.normalizeNumber(catalog));
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace("delta", "δ").replaceAll("\\s+", " ").trim();
    }
}

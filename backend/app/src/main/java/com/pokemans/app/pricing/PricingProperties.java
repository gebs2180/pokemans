package com.pokemans.app.pricing;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Comp ingestion and per-grade price snapshots. Pricing rules and their constants (windows,
 * half-life, trimming, confidence thresholds) come from the valuation parameter set ({@code pokemans.parameter-set}),
 * not from here.
 *
 * @param currency             the only currency accepted (USD in v1); other comps are rejected
 * @param minCardsPerRatio     cards with both adjacent grades observed before an era step-down ratio is used
 * @param volatilityMonths     30-day changes looked back over for σ
 * @param eras                 era boundaries for step-down ratios, by set release date. Starting values.
 */
@ConfigurationProperties("pokemans.pricing")
record PricingProperties(
        @DefaultValue("USD") String currency,
        @DefaultValue("3") int minCardsPerRatio,
        @DefaultValue("12") int volatilityMonths,
        List<Era> eras,
        @DefaultValue Ingestion ingestion,
        @DefaultValue PokeTrace poketrace) {

    /** WotC, then the EX to Black & White years, XY to Sun & Moon, and Sword & Shield on. */
    static final List<Era> DEFAULT_ERAS = List.of(
            new Era("WOTC", LocalDate.of(1996, 1, 1)),
            new Era("EX_TO_BW", LocalDate.of(2003, 6, 1)),
            new Era("XY_TO_SM", LocalDate.of(2014, 2, 1)),
            new Era("SWSH_ON", LocalDate.of(2020, 2, 1)));

    PricingProperties {
        eras = eras == null || eras.isEmpty() ? DEFAULT_ERAS : eras.stream().sorted(Comparator.comparing(Era::from)).toList();
    }

    /** An era starts on {@code from} and runs until the next era starts. */
    record Era(String name, LocalDate from) {
    }

    /** Era key for a card: language plus era, since Japanese and English prices move apart. */
    String eraKey(String language, LocalDate setReleaseDate) {
        String era = "UNKNOWN";
        if (setReleaseDate != null) {
            for (Era e : eras) {
                if (!setReleaseDate.isBefore(e.from())) {
                    era = e.name();
                }
            }
        }
        return (language == null ? "?" : language) + ":" + era;
    }

    /**
     * Comp ingestion and the nightly snapshot refresh. Off by default: no price source is licensed yet.
     *
     * @param enabled  run the nightly job
     * @param cron     when, in UTC
     * @param lookback how far back each run asks the sources for sales; duplicates are skipped
     */
    record Ingestion(@DefaultValue("false") boolean enabled,
                     @DefaultValue("0 30 6 * * *") String cron,
                     @DefaultValue("400d") Duration lookback) {
    }

    /**
     * PokeTrace Pro as a comp source (written permission 2026-11-07, docs/price-data-sources.md).
     *
     * @param enabled  ingest from PokeTrace in the nightly run, and let admins map cards to it
     * @param apiKey   from the PokeTrace dashboard; in the server's env, never in the repo
     * @param backfill history asked for a card's first pull; later pulls ask for {@code recent}
     * @param recent   history asked for once a card has PokeTrace comps; duplicates are skipped
     */
    record PokeTrace(@DefaultValue("false") boolean enabled,
                     String apiKey,
                     @DefaultValue("https://api.poketrace.com/v1") java.net.URI baseUrl,
                     @DefaultValue("20s") Duration timeout,
                     @DefaultValue("1y") String backfill,
                     @DefaultValue("30d") String recent,
                     @DefaultValue("2") int pullGapDays) {
    }
}

package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The nightly pricing refresh: comps from every configured price source for the identified cards,
 * then fresh snapshots. The only production {@link PriceSource} is {@link PokeTraceSource}, when
 * enabled; without it a run only re-snapshots the comps already stored.
 */
@Service
class PricingRefresh {

    private final List<PriceSource> sources;
    private final CompIngestionService ingestion;
    private final PriceSnapshotService snapshots;
    private final Catalog catalog;
    private final PricingProperties properties;
    private final PricingLock lock;
    private final PricingHealthcheck healthcheck;

    PricingRefresh(ObjectProvider<PriceSource> sources, CompIngestionService ingestion, PriceSnapshotService snapshots, Catalog catalog,
                   PricingProperties properties, PricingLock lock, PricingHealthcheck healthcheck) {
        this.lock = lock;
        this.healthcheck = healthcheck;
        this.sources = sources.orderedStream().toList();
        this.ingestion = ingestion;
        this.snapshots = snapshots;
        this.catalog = catalog;
        this.properties = properties;
    }

    /**
     * Ingests, then snapshots, holding the pricing lock. Then pings the pricing healthcheck, when one is
     * set: success with the rows inserted, or fail when the run threw or an enabled source inserted
     * nothing (a dead feed otherwise only shows as tables going stale). A busy skip sends nothing, so the
     * check's missed-ping alert covers it.
     *
     * @throws PricingLock.Busy if another refresh is running
     */
    PriceSnapshotService.Result runOnce(LocalDate asOf) {
        List<CompIngestRun> runs = new ArrayList<>();
        PriceSnapshotService.Result result;
        try {
            result = lock.run(() -> {
                List<Catalog.CardSummary> cards = catalog.identifiedCards();
                LocalDate from = asOf.minusDays(properties.ingestion().lookback().toDays());
                for (PriceSource source : sources) {
                    runs.add(ingestion.ingest(source, cards, from, asOf));
                }
                return snapshots.snapshotAll(asOf);
            });
        } catch (PricingLock.Busy e) {
            throw e;
        } catch (RuntimeException | Error e) {
            healthcheck.fail("Pricing run " + asOf + " failed: " + e);
            throw e;
        }
        reportHealth(asOf, runs, result);
        return result;
    }

    private void reportHealth(LocalDate asOf, List<CompIngestRun> runs, PriceSnapshotService.Result result) {
        StringBuilder summary = new StringBuilder("Pricing run ").append(asOf).append(": ");
        int inserted = runs.stream().mapToInt(CompIngestRun::getInserted).sum();
        summary.append(inserted).append(" rows inserted");
        List<String> empty = new ArrayList<>();
        for (CompIngestRun run : runs) {
            summary.append("; ").append(run.getSource()).append(" fetched ").append(run.getFetched())
                    .append(", inserted ").append(run.getInserted()).append(", duplicates ").append(run.getDuplicates())
                    .append(", rejected ").append(run.getRejected()).append(", errors ").append(run.getErrors().size());
            if (run.getInserted() == 0) {
                empty.add(run.getSource());
            }
        }
        if (runs.isEmpty()) {
            summary.append(" (no price source enabled: re-snapshot only)");
        }
        summary.append("; ").append(result.snapshotSets().size()).append(" cards priced, ")
                .append(result.skipped().size()).append(" skipped");
        if (empty.isEmpty()) {
            healthcheck.success(summary.toString());
        } else {
            healthcheck.fail(summary.append("; no rows inserted from ").append(String.join(", ", empty)).toString());
        }
    }

    /**
     * One card's sales from one source now, over the nightly run's window, then a re-snapshot if no
     * other refresh is running. The ingestion runs outside the lock (a sale stored twice is skipped as
     * a duplicate), so an admin's pull can't make the nightly run skip a night.
     *
     * @return the ingestion run, and whether the prices were re-snapshotted
     */
    Pull pullOne(PriceSource source, Catalog.CardSummary card, LocalDate asOf) {
        LocalDate from = asOf.minusDays(properties.ingestion().lookback().toDays());
        CompIngestRun run = ingestion.ingest(source, List.of(card), from, asOf);
        try {
            snapshotOnly(asOf);
            return new Pull(run, true);
        } catch (PricingLock.Busy e) {
            return new Pull(run, false);
        }
    }

    record Pull(CompIngestRun run, boolean repriced) {
    }

    /**
     * Re-snapshots the stored comps (no ingestion), holding the pricing lock: the admin's refresh.
     *
     * @throws PricingLock.Busy if another refresh is running
     */
    PriceSnapshotService.Result snapshotOnly(LocalDate asOf) {
        return lock.run(() -> snapshots.snapshotAll(asOf));
    }
}

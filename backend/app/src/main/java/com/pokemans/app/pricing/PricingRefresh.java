package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.time.LocalDate;
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

    PricingRefresh(ObjectProvider<PriceSource> sources, CompIngestionService ingestion, PriceSnapshotService snapshots, Catalog catalog,
                   PricingProperties properties, PricingLock lock) {
        this.lock = lock;
        this.sources = sources.orderedStream().toList();
        this.ingestion = ingestion;
        this.snapshots = snapshots;
        this.catalog = catalog;
        this.properties = properties;
    }

    /**
     * Ingests, then snapshots, holding the pricing lock.
     *
     * @throws PricingLock.Busy if another refresh is running
     */
    PriceSnapshotService.Result runOnce(LocalDate asOf) {
        return lock.run(() -> {
            List<Catalog.CardSummary> cards = catalog.identifiedCards();
            LocalDate from = asOf.minusDays(properties.ingestion().lookback().toDays());
            for (PriceSource source : sources) {
                ingestion.ingest(source, cards, from, asOf);
            }
            return snapshots.snapshotAll(asOf);
        });
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

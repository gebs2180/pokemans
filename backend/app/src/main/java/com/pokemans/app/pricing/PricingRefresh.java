package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The nightly pricing refresh: comps from every configured price source for the identified cards,
 * then fresh snapshots. There are no {@link PriceSource} beans in production until one is licensed,
 * so for now a run only re-snapshots the comps already stored.
 */
@Service
class PricingRefresh {

    private final List<PriceSource> sources;
    private final CompIngestionService ingestion;
    private final PriceSnapshotService snapshots;
    private final Catalog catalog;
    private final PricingProperties properties;

    PricingRefresh(ObjectProvider<PriceSource> sources, CompIngestionService ingestion, PriceSnapshotService snapshots, Catalog catalog,
                   PricingProperties properties) {
        this.sources = sources.orderedStream().toList();
        this.ingestion = ingestion;
        this.snapshots = snapshots;
        this.catalog = catalog;
        this.properties = properties;
    }

    PriceSnapshotService.Result runOnce(LocalDate asOf) {
        List<Catalog.CardSummary> cards = catalog.identifiedCards();
        LocalDate from = asOf.minusDays(properties.ingestion().lookback().toDays());
        for (PriceSource source : sources) {
            ingestion.ingest(source, cards, from, asOf);
        }
        return snapshots.snapshotAll(asOf);
    }
}

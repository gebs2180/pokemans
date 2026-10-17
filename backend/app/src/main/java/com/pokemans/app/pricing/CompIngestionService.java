package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pulls sold sales from a price source and stores the new ones as comps, with provenance. Rejects
 * anything that isn't a usable sold comp rather than storing it with a caveat.
 */
@Service
@EnableConfigurationProperties(PricingProperties.class)
class CompIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CompIngestionService.class);

    private final SoldCompRepository comps;
    private final CompIngestRunRepository runs;
    private final PricingProperties properties;

    CompIngestionService(SoldCompRepository comps, CompIngestRunRepository runs, PricingProperties properties) {
        this.comps = comps;
        this.runs = runs;
        this.properties = properties;
    }

    /** Asks {@code source} for each card's sales sold in {@code [from, to]} and stores the new ones. */
    @Transactional
    CompIngestRun ingest(PriceSource source, List<Catalog.CardSummary> cards, LocalDate from, LocalDate to) {
        CompIngestRun run = runs.save(new CompIngestRun(source.name(), from, to, cards.size()));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Set<String> seen = new HashSet<>();
        for (Catalog.CardSummary card : cards) {
            List<PriceSource.Sale> sales;
            try {
                sales = source.soldSales(card, from, to);
            } catch (RuntimeException e) {
                // One card failing (timeout, unknown card at the vendor) doesn't stop the run.
                log.warn("Price source {} failed for catalog card {}", source.name(), card.catalogCardId(), e);
                run.failed(card.catalogCardId() + ": " + e.getMessage());
                continue;
            }
            run.fetched(sales.size());
            Instant retrievedAt = Instant.now();
            for (PriceSource.Sale sale : sales) {
                String problem = problem(source, card, sale, today);
                if (problem != null) {
                    run.rejected(sale.externalSaleId() + ": " + problem);
                    continue;
                }
                if (!seen.add(sale.externalSaleId()) || comps.existsBySourceAndExternalSaleId(sale.source(), sale.externalSaleId())) {
                    run.duplicate();
                    continue;
                }
                comps.save(new SoldComp(sale, source.licence(), retrievedAt, run.getId()));
                run.inserted();
            }
        }
        run.finish();
        log.info("Comp ingestion from {}: {} fetched, {} new, {} duplicates, {} rejected", source.name(), run.getFetched(),
                run.getInserted(), run.getDuplicates(), run.getRejected());
        return runs.save(run);
    }

    /** Source name of comps a reviewer entered by hand from a sold listing they looked at. */
    static final String MANUAL = "MANUAL";

    /**
     * Stores one sold sale a reviewer entered by hand. The same checks as ingestion apply; a
     * listing URL entered twice is refused as a duplicate.
     *
     * @throws IllegalArgumentException with the reason, if the sale isn't a usable comp
     */
    @Transactional
    SoldComp recordManual(PriceSource.Sale sale) {
        String problem = problem(sale, LocalDate.now(ZoneOffset.UTC));
        if (problem == null && !MANUAL.equals(sale.source())) {
            problem = "source " + sale.source() + " is not " + MANUAL;
        }
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        if (comps.existsBySourceAndExternalSaleId(sale.source(), sale.externalSaleId())) {
            throw new IllegalArgumentException("This sale is already recorded");
        }
        return comps.save(new SoldComp(sale, "manual entry", Instant.now(), null));
    }

    /** Why a sale can't be stored as a comp, or null if it can. */
    private String problem(PriceSource source, Catalog.CardSummary card, PriceSource.Sale sale, LocalDate today) {
        if (!source.name().equals(sale.source())) {
            return "source " + sale.source() + " is not " + source.name();
        }
        if (!card.catalogCardId().equals(sale.catalogCardId())) {
            return "sale is for catalog card " + sale.catalogCardId() + ", asked for " + card.catalogCardId();
        }
        return problem(sale, today);
    }

    private String problem(PriceSource.Sale sale, LocalDate today) {
        if (!properties.currency().equals(sale.currency())) {
            return "currency " + sale.currency() + " (only " + properties.currency() + " in v1)";
        }
        if (sale.soldDate().isAfter(today)) {
            return "sold date " + sale.soldDate() + " is in the future";
        }
        return null;
    }
}

package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

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
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transactions;

    CompIngestionService(SoldCompRepository comps, CompIngestRunRepository runs, PricingProperties properties, JdbcClient jdbc,
                         JsonMapper json, TransactionTemplate transactions) {
        this.comps = comps;
        this.runs = runs;
        this.properties = properties;
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
    }

    /**
     * Asks {@code source} for each card's sales sold in {@code [from, to]} and stores the new ones.
     * Not one transaction: the source is called with none open, and each card's sales are stored in
     * their own, so one card's failure can't roll back the others. A sale already stored (or stored
     * by a concurrent run) is skipped as a duplicate. The run's audit row is saved first and always
     * updated at the end, whatever happened in between.
     */
    CompIngestRun ingest(PriceSource source, List<Catalog.CardSummary> cards, LocalDate from, LocalDate to) {
        CompIngestRun run = runs.save(new CompIngestRun(source.name(), from, to, cards.size()));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
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
                try {
                    CardTally tally = transactions.execute(status -> store(source, card, sales, today, retrievedAt, run.getId()));
                    tally.addTo(run);
                } catch (RuntimeException e) {
                    log.warn("Storing comps from {} for catalog card {} failed", source.name(), card.catalogCardId(), e);
                    run.failed(card.catalogCardId() + ": storing failed: " + e.getMessage());
                }
            }
        } finally {
            run.finish();
            runs.save(run);
        }
        log.info("Comp ingestion from {}: {} fetched, {} new, {} duplicates, {} rejected", source.name(), run.getFetched(),
                run.getInserted(), run.getDuplicates(), run.getRejected());
        return run;
    }

    /** What storing one card's sales did; added to the run only once its transaction committed. */
    private static final class CardTally {
        private int inserted;
        private int duplicates;
        private final List<String> rejected = new ArrayList<>();

        void addTo(CompIngestRun run) {
            for (int i = 0; i < inserted; i++) {
                run.inserted();
            }
            for (int i = 0; i < duplicates; i++) {
                run.duplicate();
            }
            rejected.forEach(run::rejected);
        }
    }

    private CardTally store(PriceSource source, Catalog.CardSummary card, List<PriceSource.Sale> sales, LocalDate today,
                            Instant retrievedAt, UUID runId) {
        CardTally tally = new CardTally();
        for (PriceSource.Sale sale : sales) {
            String problem = problem(source, card, sale, today);
            if (problem != null) {
                tally.rejected.add(sale.externalSaleId() + ": " + problem);
            } else if (insertIfNew(new SoldComp(sale, source.licence(), retrievedAt, runId))) {
                tally.inserted++;
            } else {
                tally.duplicates++;
            }
        }
        return tally;
    }

    /** Inserts the comp unless its (source, external sale id) is already stored; true if inserted. */
    private boolean insertIfNew(SoldComp c) {
        return jdbc.sql("""
                        insert into sold_comp (id, source, external_sale_id, catalog_card_id, grading_company, grade, raw_condition,
                                               price, currency, sold_on, listing_type, url, licence, retrieved_at, ingest_run_id,
                                               raw, created_at)
                        values (:id, :source, :externalSaleId, :catalogCardId, :gradingCompany, :grade, :rawCondition,
                                :price, :currency, :soldOn, :listingType, :url, :licence, :retrievedAt, :ingestRunId,
                                cast(:raw as jsonb), :createdAt)
                        on conflict (source, external_sale_id) do nothing""")
                .param("id", c.getId())
                .param("source", c.getSource())
                .param("externalSaleId", c.getExternalSaleId())
                .param("catalogCardId", c.getCatalogCardId())
                .param("gradingCompany", c.getGradingCompany() == null ? null : c.getGradingCompany().name(), Types.VARCHAR)
                .param("grade", c.getGrade(), Types.NUMERIC)
                .param("rawCondition", c.getRawCondition() == null ? null : c.getRawCondition().name(), Types.VARCHAR)
                .param("price", c.getPrice())
                .param("currency", c.getCurrency())
                .param("soldOn", c.getSoldOn())
                .param("listingType", c.getListingType().name())
                .param("url", c.getUrl(), Types.VARCHAR)
                .param("licence", c.getLicence())
                .param("retrievedAt", OffsetDateTime.ofInstant(c.getRetrievedAt(), ZoneOffset.UTC))
                .param("ingestRunId", c.getIngestRunId())
                .param("raw", c.getRaw() == null ? null : json.writeValueAsString(c.getRaw()), Types.VARCHAR)
                .param("createdAt", OffsetDateTime.ofInstant(c.getCreatedAt(), ZoneOffset.UTC))
                .update() == 1;
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

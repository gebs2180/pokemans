package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;

/**
 * Ingests a PokeTrace pilot folder through {@link CompIngestionService}, the path a licensed source
 * will use, for tests outside this package ({@code OfferSanityRunner}). Test scope only.
 */
public final class PokeTraceImport {

    /** What one ingestion run did. */
    public record Result(int fetched, int inserted, int duplicates, int rejected, List<String> errors) {
    }

    private PokeTraceImport() {
    }

    /**
     * @param poketraceIds PokeTrace card id per catalog card; cards not in it are skipped
     */
    public static Result ingest(ApplicationContext context, Path root, Map<UUID, String> poketraceIds, LocalDate from, LocalDate to) {
        List<Catalog.CardSummary> cards = context.getBean(Catalog.class).cards(poketraceIds.keySet());
        CompIngestRun run = context.getBean(CompIngestionService.class)
                .ingest(new PokeTraceDailySource(root, poketraceIds), cards, from, to);
        return new Result(run.getFetched(), run.getInserted(), run.getDuplicates(), run.getRejected(), run.getErrors());
    }
}

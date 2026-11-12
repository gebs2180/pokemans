package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.valuation.Comp;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradePrice;
import com.pokemans.valuation.GradePriceTable;
import com.pokemans.valuation.GradePricer;
import com.pokemans.valuation.GradingCost;
import com.pokemans.valuation.ParameterSet;
import com.pokemans.valuation.RawFloors;
import com.pokemans.valuation.StepDownRatios;
import com.pokemans.valuation.Trace;
import com.pokemans.valuation.Volatility;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds per-grade price snapshots from stored comps (CLAUDE.md step 3), using {@link GradePricer}
 * for every rule. Two passes: observed prices of every card with comps build the era step-down
 * ratio tables, then each card is priced against its era's table.
 *
 * <p>Each grading company is priced separately, with its own era ratio tables. Sigma comes from
 * graded sales only (never raw), pooled across companies and grades; the sales rate counts every sale
 * of the card, since liquidity belongs to the card, not to a grade.
 *
 * <p>Snapshots hold gross prices. Netting grading costs needs a grading cost and turnaround, which
 * belong to the valuation run (M4), so the pricer runs here with no grading cost and no raw-condition
 * override.
 *
 * <p>Not one transaction (review A3): pass one only reads, one card at a time, and keeps just the
 * observed prices; pass two prices and commits each card on its own, its price tables, stats, guard
 * exclusions and a row saying the run is done with it. One card failing is recorded and the rest go on.
 * Each card's tables are written together, so a reader never sees half a table. A run that didn't
 * complete (the process died, or something outside a card failed) is resumed by the next run for the
 * same date and parameter set, which skips the cards it had finished. Its era ratios are recomputed,
 * from the same comps unless new ones arrived in between.
 *
 * <p>PokeTrace days go through {@link PokeTraceDayGuard} first (parameter set 1.5.0 on): a day it
 * leaves out prices nothing, and is stored in {@code comp_exclusion} with its reason and in the
 * trace of the grade it would have priced. Before them, from 1.7.0, a PokeTrace day PokeTrace revised is
 * priced at its newest revision, and each older row of the day is stored the same way under
 * {@link PokeTraceRevisions#SUPERSEDED} ({@link PokeTraceRevisions}).
 */
@Service
class PriceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(PriceSnapshotService.class);
    private static final GradingCost NO_GRADING_COST =
            new GradingCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    private static final int SALES_RATE_DAYS = 90;

    /**
     * What one refresh did: per card this call priced, a snapshot set per grading company; and for each
     * card or company left unpriced, why. A resumed run leaves out the cards it skipped as already done.
     */
    record Result(LocalDate asOf, Map<UUID, Map<PriceSource.GradingCompany, UUID>> snapshotSets,
                  Map<UUID, List<String>> skipped) {
    }

    private final SoldCompRepository comps;
    private final PriceSnapshotRepository snapshots;
    private final PriceStatsRepository stats;
    private final Catalog catalog;
    private final PricingProperties properties;
    private final ParameterSet params;
    private final PokeTraceMappings poketraceMappings;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    PriceSnapshotService(SoldCompRepository comps, PriceSnapshotRepository snapshots, PriceStatsRepository stats,
                         Catalog catalog, PricingProperties properties, ParameterSet params, PokeTraceMappings poketraceMappings,
                         JdbcClient jdbc, TransactionTemplate transactions) {
        this.poketraceMappings = poketraceMappings;
        this.comps = comps;
        this.snapshots = snapshots;
        this.stats = stats;
        this.catalog = catalog;
        this.properties = properties;
        this.params = params;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** {@link #snapshotAll(LocalDate, boolean)}, resuming an unfinished run for the date. */
    Result snapshotAll(LocalDate asOf) {
        return snapshotAll(asOf, true);
    }

    /**
     * Snapshots every identified card and every card with comps, as of {@code asOf}. Each grading
     * company's comps price that company's grades on their own: a PSA 9 and a CGC 9 are different
     * markets, so they are never pooled or converted.
     *
     * @param resume skip the cards an unfinished run for the same date and parameter set already did;
     *               false re-prices every card (an admin's pull, which must re-price its card)
     */
    Result snapshotAll(LocalDate asOf, boolean resume) {
        Instant started = Instant.now();
        GradePricer pricer = new GradePricer(params);
        LocalDate from = asOf.minusDays(lookbackDays(params));

        Set<UUID> cardIds = new LinkedHashSet<>();
        catalog.identifiedCards().forEach(c -> cardIds.add(c.catalogCardId()));
        cardIds.addAll(comps.findCatalogCardIdsSoldBetween(from, asOf));
        List<Catalog.CardSummary> cards = catalog.cards(cardIds);
        Map<UUID, String> poketrace = poketraceMappings.all();

        Run run = start(asOf, cards.size(), resume);
        try {
            // Pass 1, read only: observed prices per card and company, grouped by era and company, build the ratio
            // tables. Only the observed prices are kept, not the comps, so memory grows with cards, not comps.
            Map<String, List<Map<Grade, BigDecimal>>> observedByEra = new HashMap<>();
            for (Catalog.CardSummary card : cards) {
                CardComps cc = load(guarded(card.catalogCardId(), from, asOf, poketrace).kept());
                cc.graded().forEach((company, graded) -> {
                    Map<Grade, BigDecimal> observed = pricer.observedPrices(asOf, graded);
                    if (!observed.isEmpty()) {
                        observedByEra.computeIfAbsent(era(card, company), k -> new ArrayList<>()).add(observed);
                    }
                });
            }
            Map<String, StepDownRatios.Table> ratiosByEra = new HashMap<>();
            observedByEra.forEach((era, observed) -> ratiosByEra.put(era, StepDownRatios.compute(observed, properties.minCardsPerRatio())));

            // Pass 2: price each card, once per grading company it has sales for, and commit it.
            Map<UUID, Map<PriceSource.GradingCompany, UUID>> sets = new LinkedHashMap<>();
            Map<UUID, List<String>> skipped = new LinkedHashMap<>();
            int compsRead = 0;
            int excluded = 0;
            int failed = 0;
            for (Catalog.CardSummary card : cards) {
                if (run.done().contains(card.catalogCardId())) {
                    continue;
                }
                try {
                    CardOutcome outcome = transactions.execute(status -> priceCard(run.id(), card, asOf, from, pricer,
                            ratiosByEra, poketrace));
                    compsRead += outcome.comps();
                    excluded += outcome.excluded();
                    if (!outcome.sets().isEmpty()) {
                        sets.put(card.catalogCardId(), outcome.sets());
                    }
                    if (!outcome.skipped().isEmpty()) {
                        skipped.put(card.catalogCardId(), outcome.skipped());
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Price snapshot of catalog card {} failed", card.catalogCardId(), e);
                    skipped.computeIfAbsent(card.catalogCardId(), k -> new ArrayList<>()).add("Failed: " + e.getMessage());
                    error(run.id(), card.catalogCardId() + ": " + e.getMessage());
                }
            }
            finish(run.id(), "COMPLETE");
            log.info("Price snapshots as of {} (run {}{}): {} cards, {} already done, {} priced, {} with something skipped, "
                            + "{} failed; {} comps read, {} left out by the day guards or as superseded revisions; {} s",
                    asOf, run.id(), run.resumedFrom() == null ? "" : ", resuming " + run.resumedFrom(), cards.size(),
                    run.done().size(), sets.size(), skipped.size(), failed, compsRead, excluded,
                    Duration.between(started, Instant.now()).toSeconds());
            return new Result(asOf, sets, skipped);
        } catch (RuntimeException | Error e) {
            error(run.id(), "aborted: " + e);
            finish(run.id(), "ABORTED");
            throw e;
        }
    }

    /** One card's prices, all its grading companies, with what the run records about it. Runs in its own transaction. */
    private CardOutcome priceCard(UUID runId, Catalog.CardSummary card, LocalDate asOf, LocalDate from, GradePricer pricer,
                                  Map<String, StepDownRatios.Table> ratiosByEra, Map<UUID, String> poketrace) {
        UUID cardId = card.catalogCardId();
        PokeTraceDayGuard.Result guarded = guarded(cardId, from, asOf, poketrace);
        for (PokeTraceDayGuard.Excluded e : guarded.excluded()) {
            jdbc.sql("""
                            insert into comp_exclusion (id, snapshot_run_id, catalog_card_id, sold_comp_id, rule, reason, parameter_set,
                                                        created_at)
                            values (:id, :run, :card, :comp, :rule, :reason, :params, :now)
                            on conflict (snapshot_run_id, sold_comp_id) do nothing""")
                    .param("id", UUID.randomUUID()).param("run", runId).param("card", cardId).param("comp", e.comp().getId())
                    .param("rule", e.rule()).param("reason", e.reason()).param("params", params.version()).param("now", now())
                    .update();
        }
        CardComps cc = load(guarded.kept());
        Map<PriceSource.GradingCompany, UUID> sets = new EnumMap<>(PriceSource.GradingCompany.class);
        List<String> skipped = new ArrayList<>();
        if (cc.graded().isEmpty()) {
            skipped.add("No graded sales");
        } else {
            // Sigma and the sales rate describe the card's market, so every company's set gets the same values.
            Optional<Volatility.Estimate> sigma = Volatility.fromSeries(cc.graded().values().stream()
                    .flatMap(byGrade -> byGrade.values().stream()).toList(), asOf, properties.volatilityMonths());
            BigDecimal salesPerMonth = salesPerMonth(cc.all(), asOf);
            // Raw-sale floors (1.8.0 on) are the card's raw market, the same for every company's set.
            Trace floorTrace = new Trace();
            Map<Grade, RawFloors.Floor> floors = RawFloors.compute(params, asOf, cc.raw(), floorTrace);
            for (Map.Entry<PriceSource.GradingCompany, Map<Grade, List<Comp>>> entry : cc.graded().entrySet()) {
                PriceSource.GradingCompany company = entry.getKey();
                Map<Grade, BigDecimal> ratios = Optional.ofNullable(ratiosByEra.get(era(card, company)))
                        .map(StepDownRatios.Table::ratios).orElse(Map.of());
                GradePriceTable table;
                try {
                    table = pricer.price(new GradePricer.Input(asOf, entry.getValue(), cc.damaged(), ratios,
                            NO_GRADING_COST, Map.of()));
                } catch (IllegalArgumentException e) {
                    // e.g. no era step-down ratio yet: leave this company's prices out rather than guess.
                    skipped.add(company + ": " + e.getMessage());
                    continue;
                }
                UUID setId = UUID.randomUUID();
                for (GradePrice price : table.all()) {
                    List<String> trace = new ArrayList<>(guardTrace(guarded.excluded(), company, price.grade()));
                    table.trace().stepsFor("price[" + price.grade() + "]").stream().map(Trace.Step::toString).forEach(trace::add);
                    RawFloors.Floor floor = floors.get(price.grade());
                    if (floor != null) {
                        trace.addAll(rawGuardTrace(guarded.excluded(), floor.condition()));
                        floorTrace.stepsFor("raw_floor[" + floor.condition() + "]").stream().map(Trace.Step::toString)
                                .forEach(trace::add);
                    }
                    snapshots.save(new PriceSnapshot(setId, cardId, asOf, company,
                            properties.currency(), price, price.stepDownRatio(), params.version(), trace).withRawFloor(floor));
                }
                stats.save(new PriceStats(setId, cardId, asOf, sigma.map(Volatility.Estimate::sigma).orElse(null),
                        sigma.map(Volatility.Estimate::changes).orElse(0), salesPerMonth));
                sets.put(company, setId);
            }
        }
        snapshots.flush();
        String outcome = sets.isEmpty() ? "SKIPPED" : "PRICED";
        jdbc.sql("""
                        insert into price_snapshot_run_card (snapshot_run_id, catalog_card_id, outcome, created_at)
                        values (:run, :card, :outcome, :now)""")
                .param("run", runId).param("card", cardId).param("outcome", outcome).param("now", now()).update();
        jdbc.sql("""
                        update price_snapshot_run set priced = priced + :priced, skipped = skipped + :skipped,
                                                      comps = comps + :comps, excluded = excluded + :excluded
                        where id = :run""")
                .param("priced", sets.isEmpty() ? 0 : 1).param("skipped", sets.isEmpty() ? 1 : 0)
                .param("comps", guarded.kept().size() + guarded.excluded().size()).param("excluded", guarded.excluded().size())
                .param("run", runId).update();
        return new CardOutcome(sets, skipped, guarded.kept().size() + guarded.excluded().size(), guarded.excluded().size());
    }

    private record CardOutcome(Map<PriceSource.GradingCompany, UUID> sets, List<String> skipped, int comps, int excluded) {
    }

    /**
     * The card's comps in the window, PokeTrace ones only while the card is still mapped to the PokeTrace
     * card they came from, each PokeTrace day at its revision for the parameter set, through the day guards.
     * Superseded rows join the guards' exclusions.
     */
    private PokeTraceDayGuard.Result guarded(UUID cardId, LocalDate from, LocalDate asOf, Map<UUID, String> poketrace) {
        String prefix = poketrace.getOrDefault(cardId, "\0") + "|";
        List<SoldComp> sold = comps.findByCatalogCardIdAndSoldOnBetween(cardId, from, asOf).stream()
                .filter(c -> properties.currency().equals(c.getCurrency()))
                .filter(c -> !PokeTraceSource.NAME.equals(c.getSource()) || c.getExternalSaleId().startsWith(prefix))
                .toList();
        PokeTraceRevisions.Result revisions = PokeTraceRevisions.apply(sold, params);
        PokeTraceDayGuard.Result guarded = PokeTraceDayGuard.apply(revisions.current(), params);
        if (revisions.superseded().isEmpty()) {
            return guarded;
        }
        List<PokeTraceDayGuard.Excluded> excluded = new ArrayList<>(revisions.superseded());
        excluded.addAll(guarded.excluded());
        return new PokeTraceDayGuard.Result(guarded.kept(), excluded);
    }

    /** Trace lines for the days the guards left out, or a newer revision superseded, of one grade of one company's table (raw damaged ones go to U). */
    private static List<String> guardTrace(List<PokeTraceDayGuard.Excluded> excluded, PriceSource.GradingCompany company, Grade grade) {
        return excluded.stream().filter(e -> {
            SoldComp c = e.comp();
            if (grade == Grade.U) {
                return c.getRawCondition() == PriceSource.RawCondition.DAMAGED;
            }
            return c.getGradingCompany() == company && Grade.foldHalf(c.getGrade()) == grade;
        }).map(e -> new Trace.Step("price[" + grade + "]", PokeTraceRevisions.SUPERSEDED.equals(e.rule())
                        ? "superseded by PokeTrace's revision of the day" : "left out by the PokeTrace day guard " + e.rule(),
                e.comp().getId().toString(), e.comp().getSoldOn() + ": " + e.reason()).toString()).toList();
    }

    /** Trace lines for the raw sale days of one condition that the guards left out, or a newer revision superseded. */
    private static List<String> rawGuardTrace(List<PokeTraceDayGuard.Excluded> excluded, RawFloors.Condition condition) {
        return excluded.stream().filter(e -> e.comp().getGrade() == null && e.comp().getRawCondition() != null
                        && e.comp().getRawCondition().name().equals(condition.name()))
                .map(e -> new Trace.Step("raw_floor[" + condition + "]", PokeTraceRevisions.SUPERSEDED.equals(e.rule())
                        ? "superseded by PokeTrace's revision of the day" : "left out by the PokeTrace day guard " + e.rule(),
                        e.comp().getId().toString(), e.comp().getSoldOn() + ": " + e.reason()).toString()).toList();
    }

    /** Far enough back for the extended comp window and for σ's buckets. */
    private int lookbackDays(ParameterSet params) {
        return Math.max(params.extendedWindowDays(), (properties.volatilityMonths() + 1) * Volatility.PERIOD_DAYS);
    }

    /** Era key for ratios: grading company, language and era, since each grader's price ladder differs. */
    private String era(Catalog.CardSummary card, PriceSource.GradingCompany company) {
        return company + ":" + properties.eraKey(card.language(), card.setReleaseDate());
    }

    /**
     * @param graded  graded comps per grading company and grade, half grades folded down
     * @param damaged raw damaged or altered sales, for U
     * @param raw     raw sales per condition that can floor a grade (near mint to heavily played)
     * @param all     every comp of the card, raw included, for its sales rate
     */
    private record CardComps(Map<PriceSource.GradingCompany, Map<Grade, List<Comp>>> graded, List<Comp> damaged,
                             Map<RawFloors.Condition, List<Comp>> raw, List<SoldComp> all) {
    }

    /** @param all the card's usable comps: our currency, after the day guards */
    private static CardComps load(List<SoldComp> all) {
        Map<PriceSource.GradingCompany, Map<Grade, List<Comp>>> graded = new EnumMap<>(PriceSource.GradingCompany.class);
        List<Comp> damaged = new ArrayList<>();
        Map<RawFloors.Condition, List<Comp>> raw = new EnumMap<>(RawFloors.Condition.class);
        for (SoldComp c : all) {
            Comp comp = new Comp(c.getId().toString(), c.getSoldOn(), c.getPrice());
            if (c.getGrade() != null) {
                // Half grades fold down (their own sales history isn't modelled yet).
                graded.computeIfAbsent(c.getGradingCompany(), k -> new EnumMap<>(Grade.class))
                        .computeIfAbsent(Grade.foldHalf(c.getGrade()), g -> new ArrayList<>()).add(comp);
            } else if (c.getRawCondition() == PriceSource.RawCondition.DAMAGED
                    || c.getRawCondition() == PriceSource.RawCondition.ALTERED) {
                damaged.add(comp);
            } else if (c.getRawCondition() != null) {
                raw.computeIfAbsent(RawFloors.Condition.valueOf(c.getRawCondition().name()), k -> new ArrayList<>()).add(comp);
            }
        }
        return new CardComps(graded, damaged, raw, all);
    }

    private static BigDecimal salesPerMonth(List<SoldComp> all, LocalDate asOf) {
        LocalDate from = asOf.minusDays(SALES_RATE_DAYS);
        long recent = all.stream().filter(c -> c.getSoldOn().isAfter(from)).count();
        return BigDecimal.valueOf(recent).multiply(BigDecimal.valueOf(Volatility.PERIOD_DAYS))
                .divide(BigDecimal.valueOf(SALES_RATE_DAYS), 2, RoundingMode.HALF_EVEN);
    }

    // --- the run row ---

    /** @param done cards this run needn't price again: those a resumed run had finished */
    private record Run(UUID id, UUID resumedFrom, Set<UUID> done) {
    }

    /**
     * A new run. When {@code resume} and the latest run for this date and parameter set didn't complete,
     * the new one takes over its finished cards and the old one is marked ABORTED.
     */
    private Run start(LocalDate asOf, int cards, boolean resume) {
        return transactions.execute(status -> {
            UUID id = UUID.randomUUID();
            Optional<UUID> previous = resume ? jdbc.sql("""
                            select id from price_snapshot_run where as_of = :asOf and parameter_set = :params and status <> 'COMPLETE'
                              and started_at = (select max(started_at) from price_snapshot_run
                                                where as_of = :asOf and parameter_set = :params)""")
                    .param("asOf", asOf).param("params", params.version()).query(UUID.class).optional() : Optional.empty();
            jdbc.sql("""
                            insert into price_snapshot_run (id, as_of, parameter_set, status, resumed_from, cards, started_at)
                            values (:id, :asOf, :params, 'RUNNING', :from, :cards, :now)""")
                    .param("id", id).param("asOf", asOf).param("params", params.version())
                    .param("from", previous.orElse(null), java.sql.Types.OTHER).param("cards", cards).param("now", now()).update();
            Set<UUID> done = new HashSet<>();
            if (previous.isPresent()) {
                UUID old = previous.get();
                jdbc.sql("""
                                insert into price_snapshot_run_card (snapshot_run_id, catalog_card_id, outcome, created_at)
                                select :id, catalog_card_id, outcome, created_at from price_snapshot_run_card where snapshot_run_id = :old""")
                        .param("id", id).param("old", old).update();
                jdbc.sql("""
                                update price_snapshot_run n set
                                  priced = (select count(*) from price_snapshot_run_card where snapshot_run_id = n.id and outcome = 'PRICED'),
                                  skipped = (select count(*) from price_snapshot_run_card where snapshot_run_id = n.id and outcome = 'SKIPPED')
                                where n.id = :id""")
                        .param("id", id).update();
                jdbc.sql("update price_snapshot_run set status = 'ABORTED', finished_at = coalesce(finished_at, :now) where id = :old")
                        .param("old", old).param("now", now()).update();
                done.addAll(jdbc.sql("select catalog_card_id from price_snapshot_run_card where snapshot_run_id = :id")
                        .param("id", id).query(UUID.class).list());
                log.info("Price snapshot run {} resumes run {} as of {}: {} cards already done", id, old, asOf, done.size());
            }
            return new Run(id, previous.orElse(null), done);
        });
    }

    private void finish(UUID runId, String status) {
        jdbc.sql("""
                        update price_snapshot_run set status = :status, finished_at = :now,
                               failed = coalesce(jsonb_array_length(errors), 0) - (case when :status = 'ABORTED' then 1 else 0 end)
                        where id = :id""")
                .param("status", status).param("now", now()).param("id", runId).update();
    }

    /** Appends an error to the run, outside any card's transaction, so it survives that card's rollback. */
    private void error(UUID runId, String message) {
        jdbc.sql("update price_snapshot_run set errors = coalesce(errors, '[]'::jsonb) || jsonb_build_array(cast(:m as text)) where id = :id")
                .param("m", message).param("id", runId).update();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}

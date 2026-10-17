package com.pokemans.app.pricing;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.valuation.Comp;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradePrice;
import com.pokemans.valuation.GradePriceTable;
import com.pokemans.valuation.GradePricer;
import com.pokemans.valuation.GradingCost;
import com.pokemans.valuation.ParameterSet;
import com.pokemans.valuation.StepDownRatios;
import com.pokemans.valuation.Trace;
import com.pokemans.valuation.Volatility;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds per-grade price snapshots from stored comps (CLAUDE.md step 3), using {@link GradePricer}
 * for every rule. Two passes: observed prices of every card with comps build the era step-down
 * ratio tables, then each card is priced against its era's table.
 *
 * <p>Snapshots hold gross prices. Netting grading costs needs a grading cost and turnaround, which
 * belong to the valuation run (M4), so the pricer runs here with no grading cost and no raw-condition
 * override.
 */
@Service
class PriceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(PriceSnapshotService.class);
    private static final GradingCost NO_GRADING_COST =
            new GradingCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    private static final int SALES_RATE_DAYS = 90;

    /** What one refresh did: snapshot set per priced card, and why the others weren't priced. */
    record Result(LocalDate asOf, Map<UUID, UUID> snapshotSets, Map<UUID, String> skipped) {
    }

    private final SoldCompRepository comps;
    private final PriceSnapshotRepository snapshots;
    private final PriceStatsRepository stats;
    private final Catalog catalog;
    private final PricingProperties properties;

    PriceSnapshotService(SoldCompRepository comps, PriceSnapshotRepository snapshots, PriceStatsRepository stats,
                         Catalog catalog, PricingProperties properties) {
        this.comps = comps;
        this.snapshots = snapshots;
        this.stats = stats;
        this.catalog = catalog;
        this.properties = properties;
    }

    /** Snapshots every identified card and every card with comps, as of {@code asOf}. */
    @Transactional
    Result snapshotAll(LocalDate asOf) {
        ParameterSet params = ParameterSet.load(properties.parameterSet());
        GradePricer pricer = new GradePricer(params);
        LocalDate from = asOf.minusDays(lookbackDays(params));

        Set<UUID> cardIds = new LinkedHashSet<>();
        catalog.identifiedCards().forEach(c -> cardIds.add(c.catalogCardId()));
        cardIds.addAll(comps.findCatalogCardIdsSoldBetween(from, asOf));
        List<Catalog.CardSummary> cards = catalog.cards(cardIds);

        // Pass 1: observed prices per card, grouped by era, build each era's ratio table.
        Map<UUID, CardComps> compsByCard = new HashMap<>();
        Map<String, List<Map<Grade, BigDecimal>>> observedByEra = new HashMap<>();
        for (Catalog.CardSummary card : cards) {
            CardComps cc = load(card.catalogCardId(), from, asOf);
            compsByCard.put(card.catalogCardId(), cc);
            Map<Grade, BigDecimal> observed = pricer.observedPrices(asOf, cc.graded());
            if (!observed.isEmpty()) {
                observedByEra.computeIfAbsent(era(card), k -> new ArrayList<>()).add(observed);
            }
        }
        Map<String, StepDownRatios.Table> ratiosByEra = new HashMap<>();
        observedByEra.forEach((era, observed) -> ratiosByEra.put(era, StepDownRatios.compute(observed, properties.minCardsPerRatio())));

        // Pass 2: price each card.
        Map<UUID, UUID> sets = new LinkedHashMap<>();
        Map<UUID, String> skipped = new LinkedHashMap<>();
        for (Catalog.CardSummary card : cards) {
            CardComps cc = compsByCard.get(card.catalogCardId());
            Map<Grade, BigDecimal> ratios = Optional.ofNullable(ratiosByEra.get(era(card)))
                    .map(StepDownRatios.Table::ratios).orElse(Map.of());
            GradePriceTable table;
            try {
                table = pricer.price(new GradePricer.Input(asOf, cc.graded(), cc.damaged(), properties.bulkFloor(), ratios,
                        NO_GRADING_COST, Map.of()));
            } catch (IllegalArgumentException e) {
                // e.g. no era step-down ratio yet: leave the card unpriced rather than guess.
                skipped.put(card.catalogCardId(), e.getMessage());
                continue;
            }
            UUID setId = UUID.randomUUID();
            for (GradePrice price : table.all()) {
                BigDecimal ratio = price.method() == GradePrice.Method.STEP_DOWN ? ratios.get(price.grade()) : null;
                List<String> trace = table.trace().stepsFor("price[" + price.grade() + "]").stream()
                        .map(Trace.Step::toString).toList();
                snapshots.save(new PriceSnapshot(setId, card.catalogCardId(), asOf, properties.gradingCompany(),
                        properties.currency(), price, ratio, params.version(), trace));
            }
            Optional<Volatility.Estimate> sigma = Volatility.fromComps(cc.graded(), asOf, properties.volatilityMonths());
            stats.save(new PriceStats(setId, card.catalogCardId(), asOf, sigma.map(Volatility.Estimate::sigma).orElse(null),
                    sigma.map(Volatility.Estimate::changes).orElse(0), salesPerMonth(cc.all(), asOf)));
            sets.put(card.catalogCardId(), setId);
        }
        log.info("Price snapshots as of {}: {} cards priced, {} skipped", asOf, sets.size(), skipped.size());
        return new Result(asOf, sets, skipped);
    }

    /** Far enough back for the extended comp window and for σ's buckets. */
    private int lookbackDays(ParameterSet params) {
        return Math.max(params.extendedWindowDays(), (properties.volatilityMonths() + 1) * Volatility.PERIOD_DAYS);
    }

    private String era(Catalog.CardSummary card) {
        return properties.eraKey(card.language(), card.setReleaseDate());
    }

    /**
     * @param graded  the configured grading company's comps, half grades folded down
     * @param damaged raw damaged or altered sales, for U
     * @param all     every comp of the card, for its sales rate
     */
    private record CardComps(Map<Grade, List<Comp>> graded, List<Comp> damaged, List<SoldComp> all) {
    }

    private CardComps load(UUID catalogCardId, LocalDate from, LocalDate asOf) {
        List<SoldComp> all = comps.findByCatalogCardIdAndSoldOnBetween(catalogCardId, from, asOf).stream()
                .filter(c -> properties.currency().equals(c.getCurrency()))
                .toList();
        Map<Grade, List<Comp>> graded = new EnumMap<>(Grade.class);
        List<Comp> damaged = new ArrayList<>();
        for (SoldComp c : all) {
            Comp comp = new Comp(c.getId().toString(), c.getSoldOn(), c.getPrice());
            if (c.getGrade() != null) {
                if (c.getGradingCompany() == properties.gradingCompany()) {
                    // Half grades fold down (their own sales history isn't modelled yet).
                    graded.computeIfAbsent(Grade.foldHalf(c.getGrade()), g -> new ArrayList<>()).add(comp);
                }
            } else if (c.getRawCondition() == PriceSource.RawCondition.DAMAGED
                    || c.getRawCondition() == PriceSource.RawCondition.ALTERED) {
                damaged.add(comp);
            }
        }
        return new CardComps(graded, damaged, all);
    }

    private static BigDecimal salesPerMonth(List<SoldComp> all, LocalDate asOf) {
        LocalDate from = asOf.minusDays(SALES_RATE_DAYS);
        long recent = all.stream().filter(c -> c.getSoldOn().isAfter(from)).count();
        return BigDecimal.valueOf(recent).multiply(BigDecimal.valueOf(Volatility.PERIOD_DAYS))
                .divide(BigDecimal.valueOf(SALES_RATE_DAYS), 2, RoundingMode.HALF_EVEN);
    }
}

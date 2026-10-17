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
 * <p>Each grading company is priced separately, with its own era ratio tables. Sigma comes from
 * graded sales only (never raw), pooled across companies and grades; the sales rate counts every sale
 * of the card, since liquidity belongs to the card, not to a grade.
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

    /**
     * What one refresh did: per priced card, a snapshot set per grading company; and for each card
     * or company left unpriced, why.
     */
    record Result(LocalDate asOf, Map<UUID, Map<PriceSource.GradingCompany, UUID>> snapshotSets,
                  Map<UUID, List<String>> skipped) {
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

    /**
     * Snapshots every identified card and every card with comps, as of {@code asOf}. Each grading
     * company's comps price that company's grades on their own: a PSA 9 and a CGC 9 are different
     * markets, so they are never pooled or converted.
     */
    @Transactional
    Result snapshotAll(LocalDate asOf) {
        ParameterSet params = ParameterSet.load(properties.parameterSet());
        GradePricer pricer = new GradePricer(params);
        LocalDate from = asOf.minusDays(lookbackDays(params));

        Set<UUID> cardIds = new LinkedHashSet<>();
        catalog.identifiedCards().forEach(c -> cardIds.add(c.catalogCardId()));
        cardIds.addAll(comps.findCatalogCardIdsSoldBetween(from, asOf));
        List<Catalog.CardSummary> cards = catalog.cards(cardIds);

        // Pass 1: observed prices per card and company, grouped by era and company, build the ratio tables.
        Map<UUID, CardComps> compsByCard = new HashMap<>();
        Map<String, List<Map<Grade, BigDecimal>>> observedByEra = new HashMap<>();
        for (Catalog.CardSummary card : cards) {
            CardComps cc = load(card.catalogCardId(), from, asOf);
            compsByCard.put(card.catalogCardId(), cc);
            cc.graded().forEach((company, graded) -> {
                Map<Grade, BigDecimal> observed = pricer.observedPrices(asOf, graded);
                if (!observed.isEmpty()) {
                    observedByEra.computeIfAbsent(era(card, company), k -> new ArrayList<>()).add(observed);
                }
            });
        }
        Map<String, StepDownRatios.Table> ratiosByEra = new HashMap<>();
        observedByEra.forEach((era, observed) -> ratiosByEra.put(era, StepDownRatios.compute(observed, properties.minCardsPerRatio())));

        // Pass 2: price each card, once per grading company it has sales for.
        Map<UUID, Map<PriceSource.GradingCompany, UUID>> sets = new LinkedHashMap<>();
        Map<UUID, List<String>> skipped = new LinkedHashMap<>();
        for (Catalog.CardSummary card : cards) {
            CardComps cc = compsByCard.get(card.catalogCardId());
            if (cc.graded().isEmpty()) {
                skipped.computeIfAbsent(card.catalogCardId(), k -> new ArrayList<>()).add("No graded sales");
                continue;
            }
            // Sigma and the sales rate describe the card's market, so every company's set gets the same values.
            Optional<Volatility.Estimate> sigma = Volatility.fromSeries(cc.graded().values().stream()
                    .flatMap(byGrade -> byGrade.values().stream()).toList(), asOf, properties.volatilityMonths());
            BigDecimal salesPerMonth = salesPerMonth(cc.all(), asOf);
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
                    skipped.computeIfAbsent(card.catalogCardId(), k -> new ArrayList<>()).add(company + ": " + e.getMessage());
                    continue;
                }
                UUID setId = UUID.randomUUID();
                for (GradePrice price : table.all()) {
                    BigDecimal ratio = price.method() == GradePrice.Method.STEP_DOWN ? ratios.get(price.grade()) : null;
                    List<String> trace = table.trace().stepsFor("price[" + price.grade() + "]").stream()
                            .map(Trace.Step::toString).toList();
                    snapshots.save(new PriceSnapshot(setId, card.catalogCardId(), asOf, company,
                            properties.currency(), price, ratio, params.version(), trace));
                }
                stats.save(new PriceStats(setId, card.catalogCardId(), asOf, sigma.map(Volatility.Estimate::sigma).orElse(null),
                        sigma.map(Volatility.Estimate::changes).orElse(0), salesPerMonth));
                sets.computeIfAbsent(card.catalogCardId(), k -> new EnumMap<>(PriceSource.GradingCompany.class)).put(company, setId);
            }
        }
        log.info("Price snapshots as of {}: {} cards priced, {} with something skipped", asOf, sets.size(), skipped.size());
        return new Result(asOf, sets, skipped);
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
     * @param all     every comp of the card, raw included, for its sales rate
     */
    private record CardComps(Map<PriceSource.GradingCompany, Map<Grade, List<Comp>>> graded, List<Comp> damaged,
                             List<SoldComp> all) {
    }

    private CardComps load(UUID catalogCardId, LocalDate from, LocalDate asOf) {
        List<SoldComp> all = comps.findByCatalogCardIdAndSoldOnBetween(catalogCardId, from, asOf).stream()
                .filter(c -> properties.currency().equals(c.getCurrency()))
                .toList();
        Map<PriceSource.GradingCompany, Map<Grade, List<Comp>>> graded = new EnumMap<>(PriceSource.GradingCompany.class);
        List<Comp> damaged = new ArrayList<>();
        for (SoldComp c : all) {
            Comp comp = new Comp(c.getId().toString(), c.getSoldOn(), c.getPrice());
            if (c.getGrade() != null) {
                // Half grades fold down (their own sales history isn't modelled yet).
                graded.computeIfAbsent(c.getGradingCompany(), k -> new EnumMap<>(Grade.class))
                        .computeIfAbsent(Grade.foldHalf(c.getGrade()), g -> new ArrayList<>()).add(comp);
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

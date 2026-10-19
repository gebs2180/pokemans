package com.pokemans.valuation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Step 3: the era step-down ratio table. For grade g, the median of price(g) / price(g + 1) across
 * comparable same-era cards where both grades were observed. Feeds {@link GradePricer.Input#stepDownRatios()}.
 *
 * <ul>
 *   <li>Only observed prices count; derived prices never feed the table.
 *   <li>A ratio needs at least {@code minCards} cards with both grades observed, else it is left out
 *       (the pricer then refuses to step down rather than guess).
 *   <li>With an even number of cards, the lower of the two middle ratios (conservative).
 *   <li>Capped at 1: stepping down never raises a price.
 *   <li>No ratio for grade 1 to 2. PSA 1s have their own collectors and often sell above grades 2
 *       to 4, so grade 1 sales would pull the era ratio towards 1 (or past it, before the cap) and
 *       price other cards' grade 1 too high. Grade 1 stands alone: a card without grade 1 sales
 *       steps grade 1 down from grade 2 with the 2-to-3 ratio ({@link GradePricer}), else its own
 *       ladder, which excludes grade 1 sales too.
 * </ul>
 */
public final class StepDownRatios {

    /**
     * @param ratios    ratio per grade g (price of g relative to g + 1), for grades 2 to 9 that had enough cards
     * @param cardCount cards behind each ratio
     */
    public record Table(Map<Grade, BigDecimal> ratios, Map<Grade, Integer> cardCount) {
        public Table {
            ratios = Map.copyOf(ratios);
            cardCount = Map.copyOf(cardCount);
        }
    }

    private StepDownRatios() {
    }

    /** @param observedPerCard observed gross price per grade, one map per comparable card */
    public static Table compute(Collection<Map<Grade, BigDecimal>> observedPerCard, int minCards) {
        if (minCards < 1) {
            throw new IllegalArgumentException("minCards must be at least 1: " + minCards);
        }
        EnumMap<Grade, BigDecimal> ratios = new EnumMap<>(Grade.class);
        EnumMap<Grade, Integer> counts = new EnumMap<>(Grade.class);
        // From grade 2: grade 1 prices never feed the table (see the class comment).
        for (int g = 2; g <= 9; g++) {
            Grade lower = Grade.of(g);
            Grade upper = Grade.of(g + 1);
            List<BigDecimal> cardRatios = new ArrayList<>();
            for (Map<Grade, BigDecimal> card : observedPerCard) {
                BigDecimal lo = card.get(lower);
                BigDecimal hi = card.get(upper);
                if (lo != null && hi != null && hi.signum() > 0) {
                    cardRatios.add(lo.divide(hi, Money.MC));
                }
            }
            if (cardRatios.size() < minCards) {
                continue;
            }
            Collections.sort(cardRatios);
            BigDecimal median = cardRatios.get((cardRatios.size() - 1) / 2);
            ratios.put(lower, Money.min(median, BigDecimal.ONE));
            counts.put(lower, cardRatios.size());
        }
        return new Table(ratios, counts);
    }
}

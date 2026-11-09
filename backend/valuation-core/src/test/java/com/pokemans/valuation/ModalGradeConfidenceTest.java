package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Review B5: comps confidence from the modal grade, and thin grades treated as derived (parameter set 1.3.0). */
class ModalGradeConfidenceTest {

    private static final ParameterSet V120 = ParameterSet.load("1.2.0");
    private static final ParameterSet V130 = ParameterSet.load("1.3.0");

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static GradePrice observed(Grade grade, int sales) {
        return GradePrice.given(grade, d("100"), GradePrice.Method.OBSERVED, sales);
    }

    /** Every gradable grade observed with 30 sales, U at zero with no sales, then the overrides. */
    private static GradePriceTable table(GradePrice... overrides) {
        Map<Grade, GradePrice> prices = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            prices.put(grade, grade == Grade.U
                    ? GradePrice.given(grade, BigDecimal.ZERO, GradePrice.Method.NO_SALES, 0)
                    : observed(grade, 30));
        }
        for (GradePrice price : overrides) {
            prices.put(price.grade(), price);
        }
        return new GradePriceTable(prices, new Trace());
    }

    private static CardValuation value(ParameterSet params, Map<Grade, BigDecimal> distribution, GradePriceTable prices) {
        return new CardValuator(params).value(new CardValuator.Input("card", GradeDistribution.of(distribution), prices,
                d("0.08"), d("10"), 1, Confidence.A));
    }

    /** The review's example: 40% on a 9 with one sale day, 30% on an 8 with 30, 30% on U. */
    @Test
    void theReviewExampleIsNoLongerConfidenceB() {
        var distribution = Map.of(Grade.G9, d("0.4"), Grade.G8, d("0.3"), Grade.U, d("0.3"));
        var prices = table(observed(Grade.G9, 1));

        CardValuation before = value(V120, distribution, prices);
        assertThat(before.confidence()).as("1.2.0: weighted 9.4 sales").isEqualTo(Confidence.B);
        assertThat(before.offerConfidence()).isEqualTo(Confidence.B);

        CardValuation after = value(V130, distribution, prices);
        // The modal grade, 9, has one sale behind it: under compsForC, so D, and D gets no offer.
        assertThat(after.confidence()).isEqualTo(Confidence.D);
        assertThat(after.offerConfidence()).isEqualTo(Confidence.D);
        assertThat(after.trace().stepsFor("confidence_base").getFirst().detail())
                .contains("modal grade 9 with 1 sales behind its price");
    }

    @Test
    void aThinGradeInPlayDropsOneLevelEvenWhenItIsNotTheModalGrade() {
        var distribution = Map.of(Grade.G8, d("0.6"), Grade.G9, d("0.4"));
        var prices = table(observed(Grade.G9, 1));

        assertThat(value(V120, distribution, prices).confidence()).as("1.2.0: weighted 18.4").isEqualTo(Confidence.A);

        CardValuation after = value(V130, distribution, prices);
        // Weighted 18 without the thin grade and 30 at the modal 8, so A; the thin 9 in play drops it to B.
        assertThat(after.confidence()).isEqualTo(Confidence.B);
        assertThat(after.offerConfidence()).isEqualTo(Confidence.B);
        assertThat(after.trace().stepsFor("confidence").getFirst().detail()).contains("(thin)");
    }

    @Test
    void aDerivedModalGradeCountsTheSalesOfTheGradesItCameFrom() {
        var distribution = Map.of(Grade.G9, d("0.45"), Grade.G10, d("0.35"), Grade.G8, d("0.20"));
        GradePrice fromTen = new GradePrice(Grade.G9, d("100"), d("100"), GradePrice.Method.STEP_DOWN, 0, List.of(), false,
                0, 0, List.of(Grade.G10), null, d("0.5"));
        GradePrice noBasis = GradePrice.given(Grade.G9, d("100"), GradePrice.Method.STEP_DOWN, 0);

        // 30 sales behind the 10 it was stepped down from: A, then the derived 9 drops it to B.
        assertThat(value(V130, distribution, table(fromTen)).offerConfidence()).isEqualTo(Confidence.B);
        // With no recorded basis, nothing is behind it.
        assertThat(value(V130, distribution, table(noBasis)).offerConfidence()).isEqualTo(Confidence.D);
    }

    @Test
    void aTieForTheModalGradeGoesToTheThinnerGrade() {
        var tie = Map.of(Grade.G8, d("0.5"), Grade.G9, d("0.5"));
        // Comps for C at 1, so a single sale is C rather than D and the tie-break shows.
        Properties p = properties("1.3.0");
        p.setProperty("confidence.compsForC", "1");
        ParameterSet params = ParameterSet.fromProperties(p);

        // Either way round, the modal grade is the one with 1 sale behind it (C), then dropped for being thin.
        assertThat(value(params, tie, table(observed(Grade.G9, 1))).trace().stepsFor("confidence_base").getFirst().detail())
                .contains("modal grade 9 with 1");
        assertThat(value(params, tie, table(observed(Grade.G8, 1))).trace().stepsFor("confidence_base").getFirst().detail())
                .contains("modal grade 8 with 1");
        assertThat(value(params, tie, table(observed(Grade.G8, 1))).confidence()).isEqualTo(Confidence.D);
    }

    @Test
    void aThinGradesOwnPriceConfidenceDropsOneLevel() {
        // One sale in the extended window: C with compsForC at 1, and D once thin grades count as derived.
        Properties p = properties("1.3.0");
        p.setProperty("confidence.compsForC", "1");
        ParameterSet thinDerived = ParameterSet.fromProperties(p);
        p.setProperty("pricing.thinGradesDerived", "false");
        ParameterSet thinObserved = ParameterSet.fromProperties(p);
        LocalDate asOf = LocalDate.of(2026, 10, 1);
        List<Comp> oneSale = new ArrayList<>(List.of(new Comp("c1", asOf.minusDays(3), d("100"))));
        GradingCost noCost = new GradingCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        // A deep 7 as well, so the card's own ladder can price the grades below.
        List<Comp> sevens = List.of(new Comp("c2", asOf.minusDays(2), d("50")), new Comp("c3", asOf.minusDays(4), d("50")),
                new Comp("c4", asOf.minusDays(6), d("50")));
        var input = new GradePricer.Input(asOf, Map.of(Grade.G9, oneSale, Grade.G7, sevens), List.of(), Map.of(), noCost, Map.of());

        GradePrice before = new GradePricer(thinObserved).price(input).get(Grade.G9);
        GradePrice after = new GradePricer(thinDerived).price(input).get(Grade.G9);

        assertThat(before.confidence()).isEqualTo(Confidence.C);
        assertThat(after.confidence()).isEqualTo(Confidence.D);
        assertThat(after.net()).as("the price itself is unchanged").isEqualByComparingTo(before.net());
    }

    private static Properties properties(String version) {
        Properties p = new Properties();
        try (var in = ParameterSet.class.getResourceAsStream("/parameter-sets/" + version + ".properties")) {
            p.load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }
}

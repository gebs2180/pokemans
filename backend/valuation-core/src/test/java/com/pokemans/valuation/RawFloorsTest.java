package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Raw-sale floors on the per-grade net price (parameter set 1.8.0, deep review B1 step 2, Zach 2026-11-11). */
class RawFloorsTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 11);
    private static final GradingCost COST = new GradingCost(d("25"), d("5"), d("2"), d("0.02"));
    private static final ParameterSet V180 = ParameterSet.load("1.8.0");
    private static final ParameterSet V170 = ParameterSet.load("1.7.0");

    private int nextId;

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private List<Comp> days(int daysAgo, String... prices) {
        List<Comp> comps = new ArrayList<>();
        for (String price : prices) {
            comps.add(new Comp("raw" + nextId++, AS_OF.minusDays(daysAgo), d(price)));
        }
        return comps;
    }

    /** A cheap card's gross ladder: the PSA 7 barely covers the $32 of grading, shipping and insurance. */
    private static List<GradePrice> sixtyDollarCard() {
        Map<Grade, String> gross = new EnumMap<>(Grade.class);
        gross.put(Grade.U, "0");
        String[] ladder = {"20", "22", "24", "26", "28", "30", "33", "40", "120", "300"};
        for (int g = 1; g <= 10; g++) {
            gross.put(Grade.of(g), ladder[g - 1]);
        }
        List<GradePrice> prices = new ArrayList<>();
        gross.forEach((grade, price) -> prices.add(GradePrice.given(grade, d(price), GradePrice.Method.OBSERVED, 20)));
        return prices;
    }

    @Test
    void aSixtyDollarCardsPsa7GetsTheNearMintFloorAndItsNineAndTenAreUntouched() {
        Map<RawFloors.Condition, List<Comp>> raw = new EnumMap<>(RawFloors.Condition.class);
        raw.put(RawFloors.Condition.NEAR_MINT, days(3, "55", "58", "60", "62", "65"));
        raw.put(RawFloors.Condition.LIGHTLY_PLAYED, days(5, "38", "40", "45"));
        Map<Grade, RawFloors.Floor> floors = RawFloors.compute(V180, AS_OF, raw, new Trace());

        GradePriceTable before = GradePriceTable.netOf(sixtyDollarCard(), COST);
        GradePriceTable after = GradePriceTable.netOf(sixtyDollarCard(), COST, floors);

        // PSA 7 grosses $33 and nets $0.34 after $32 of costs and 2% time-to-cash.
        assertThat(before.net(Grade.G7)).isEqualByComparingTo("0.34");
        // Near mint sale days 55, 58, 60, 62, 65 on one day: the 25th percentile is 58.
        assertThat(after.net(Grade.G7)).isEqualByComparingTo("58");
        assertThat(after.net(Grade.G8)).isEqualByComparingTo("58");
        assertThat(after.get(Grade.G7).rawOverride()).isTrue();
        // Lightly played floors 5 and 6 at its own low end: the 25th percentile of 38, 40, 45 is 38.
        assertThat(after.net(Grade.G5)).isEqualByComparingTo("38");
        assertThat(after.net(Grade.G6)).isEqualByComparingTo("38");
        // Near mint floors 9 and 10 too, but they net 85.60 and 262 here, above the floor, so they're untouched.
        // 1 to 4 have no condition mapped; U is unchanged.
        assertThat(floors).containsKeys(Grade.G9, Grade.G10).doesNotContainKeys(Grade.G1, Grade.G2, Grade.G3, Grade.G4, Grade.U);
        for (Grade grade : List.of(Grade.G9, Grade.G10, Grade.G1, Grade.G2, Grade.G3, Grade.G4, Grade.U)) {
            assertThat(after.net(grade)).as("grade %s", grade).isEqualByComparingTo(before.net(grade));
            assertThat(after.get(grade).rawOverride()).as("grade %s", grade).isFalse();
        }
        // The gross is the graded price: the floor only sets the net.
        assertThat(after.gross(Grade.G7)).isEqualByComparingTo("33");
        assertThat(after.trace().stepsFor("net[7]")).anySatisfy(s -> assertThat(s.rule()).contains("raw-sale floor"));
    }

    @Test
    void aFloorBelowTheNetGradedPriceLeavesItAlone() {
        Map<RawFloors.Condition, List<Comp>> raw = Map.of(RawFloors.Condition.NEAR_MINT, days(3, "5", "6", "7"));
        Map<Grade, RawFloors.Floor> floors = RawFloors.compute(V180, AS_OF, raw, new Trace());
        List<GradePrice> gross = sixtyDollarCard();

        GradePriceTable after = GradePriceTable.netOf(gross, COST, floors);

        assertThat(after.net(Grade.G8)).isEqualByComparingTo(GradePriceTable.netOf(gross, COST).net(Grade.G8));
        assertThat(after.get(Grade.G8).rawOverride()).isFalse();
    }

    @Test
    void aNineThatNetsBelowTheNearMintFloorIsFlooredSoItDoesNotCapTheEightBackDown() {
        // The 9 nets 85.60; a near mint floor of 100 lifts 7, 8 and 9 to 100, and the 10 (262) stays above it.
        Map<RawFloors.Condition, List<Comp>> raw = Map.of(RawFloors.Condition.NEAR_MINT, days(3, "100", "100"));
        GradePriceTable after = GradePriceTable.netOf(sixtyDollarCard(), COST, RawFloors.compute(V180, AS_OF, raw, new Trace()));

        assertThat(after.net(Grade.G9)).isEqualByComparingTo("100");
        assertThat(after.get(Grade.G9).rawOverride()).isTrue();
        assertThat(after.get(Grade.G10).rawOverride()).isFalse();
        Map<Grade, BigDecimal> capped = CardValuator.neverUp(after);
        assertThat(capped.get(Grade.G8)).isEqualByComparingTo("100");
        assertThat(capped.get(Grade.G7)).isEqualByComparingTo("100");
    }

    @Test
    void theFlvStressStillCapsEachGradeAtTheGradeAbove() throws Exception {
        // With near mint floors on 7 and 8 only, the 9 nets 85.60; a floor of 100 lifts 7 and 8 above it, and the
        // stress caps them back.
        ParameterSet sevenEight = with("pricing.rawFloor.NEAR_MINT", "7,8");
        Map<RawFloors.Condition, List<Comp>> raw = Map.of(RawFloors.Condition.NEAR_MINT, days(3, "100", "100"));
        GradePriceTable after = GradePriceTable.netOf(sixtyDollarCard(), COST, RawFloors.compute(sevenEight, AS_OF, raw, new Trace()));

        Map<Grade, BigDecimal> capped = CardValuator.neverUp(after);

        assertThat(after.net(Grade.G8)).isEqualByComparingTo("100");
        assertThat(capped.get(Grade.G8)).isEqualByComparingTo(after.net(Grade.G9));
        assertThat(capped.get(Grade.G7)).isEqualByComparingTo(after.net(Grade.G9));
    }

    @Test
    void theFloorIsARecencyWeightedLowQuantileNeverTheAverage() {
        // A recent cheap day outweighs older dear ones.
        List<Comp> comps = new ArrayList<>(days(1, "40"));
        comps.addAll(days(60, "80", "85", "90"));

        assertThat(RawFloors.weightedQuantile(comps, AS_OF, 30, d("0.25"))).isEqualByComparingTo("40");
        // Same days, all equally recent: the 25th percentile of four is the lowest.
        assertThat(RawFloors.weightedQuantile(days(1, "90", "40", "85", "80"), AS_OF, 30, d("0.25"))).isEqualByComparingTo("40");
        assertThat(RawFloors.weightedQuantile(days(1, "90", "40", "85", "80"), AS_OF, 30, d("0.5"))).isEqualByComparingTo("80");
    }

    @Test
    void thinRawDataExtendsTheWindowThenGivesNoFloor() {
        Trace trace = new Trace();
        Map<RawFloors.Condition, List<Comp>> extended = Map.of(RawFloors.Condition.NEAR_MINT,
                concat(days(10, "50"), days(150, "45")));
        Map<Grade, RawFloors.Floor> floors = RawFloors.compute(V180, AS_OF, extended, trace);
        assertThat(floors.get(Grade.G7).windowDays()).isEqualTo(180);
        assertThat(floors.get(Grade.G7).saleDays()).isEqualTo(2);

        Map<RawFloors.Condition, List<Comp>> one = Map.of(RawFloors.Condition.NEAR_MINT, days(10, "50"));
        Trace thin = new Trace();
        assertThat(RawFloors.compute(V180, AS_OF, one, thin)).isEmpty();
        assertThat(thin.stepsFor("raw_floor[NEAR_MINT]").getFirst().rule()).contains("too few");
        // Older than 180 days doesn't count.
        assertThat(RawFloors.compute(V180, AS_OF, Map.of(RawFloors.Condition.NEAR_MINT,
                concat(days(200, "50"), days(10, "50"))), new Trace())).isEmpty();
    }

    @Test
    void aWorseConditionIsNeverFlooredAboveABetterOne() {
        Map<RawFloors.Condition, List<Comp>> raw = new EnumMap<>(RawFloors.Condition.class);
        raw.put(RawFloors.Condition.NEAR_MINT, days(3, "30", "32"));
        raw.put(RawFloors.Condition.LIGHTLY_PLAYED, days(3, "50", "55"));

        Map<Grade, RawFloors.Floor> floors = RawFloors.compute(V180, AS_OF, raw, new Trace());

        assertThat(floors.get(Grade.G6).price()).isEqualByComparingTo("30");
        assertThat(floors.get(Grade.G6).condition()).isEqualTo(RawFloors.Condition.LIGHTLY_PLAYED);
    }

    @Test
    void moderatelyAndHeavilyPlayedFloorNoGradeIn180() {
        Map<RawFloors.Condition, List<Comp>> raw = new EnumMap<>(RawFloors.Condition.class);
        raw.put(RawFloors.Condition.MODERATELY_PLAYED, days(3, "30", "32"));
        raw.put(RawFloors.Condition.HEAVILY_PLAYED, days(3, "20", "22"));

        assertThat(RawFloors.compute(V180, AS_OF, raw, new Trace())).isEmpty();
        assertThat(V180.rawFloorGrades(RawFloors.Condition.NEAR_MINT)).containsExactly(Grade.G7, Grade.G8, Grade.G9, Grade.G10);
        assertThat(V180.rawFloorGrades(RawFloors.Condition.LIGHTLY_PLAYED)).containsExactly(Grade.G5, Grade.G6);
        assertThat(V180.rawFloorGrades(RawFloors.Condition.MODERATELY_PLAYED)).isEmpty();
        assertThat(V180.rawFloorGrades(RawFloors.Condition.HEAVILY_PLAYED)).isEmpty();
    }

    @Test
    void setsBefore180HaveNoFloors() {
        Map<RawFloors.Condition, List<Comp>> raw = Map.of(RawFloors.Condition.NEAR_MINT, days(3, "55", "60"));

        assertThat(V170.hasRawFloors()).isFalse();
        assertThat(V170.rawFloorIsDerived()).isFalse();
        assertThat(RawFloors.compute(V170, AS_OF, raw, new Trace())).isEmpty();
    }

    @Test
    void aGradePricedAtItsFloorKeepsItsConfidenceIn180() {
        Map<RawFloors.Condition, List<Comp>> raw = Map.of(RawFloors.Condition.NEAR_MINT, days(3, "55", "58", "60", "62", "65"));
        GradePriceTable floored = GradePriceTable.netOf(sixtyDollarCard(), COST, RawFloors.compute(V180, AS_OF, raw, new Trace()));
        CardValuator.Input likelySeven = new CardValuator.Input("card", GradeDistribution.certain(Grade.G7), floored, d("0.05"),
                d("30"), 1, Confidence.A);

        // Zach, 2026-11-11: a floor is observed raw sales, not a derived price, so the offer stays at A.
        assertThat(V180.rawFloorIsDerived()).isFalse();
        assertThat(floored.get(Grade.G7).rawOverride()).isTrue();
        assertThat(new CardValuator(V180).value(likelySeven).offerConfidence()).isEqualTo(Confidence.A);
    }

    @Test
    void aSetThatCountsFloorsAsDerivedDropsConfidenceWhereTheFloorCarriesProbability() throws Exception {
        ParameterSet derived = with("pricing.rawFloor.derived", "true");
        Map<RawFloors.Condition, List<Comp>> raw = Map.of(RawFloors.Condition.NEAR_MINT, days(3, "55", "58", "60", "62", "65"));
        GradePriceTable floored = GradePriceTable.netOf(sixtyDollarCard(), COST, RawFloors.compute(derived, AS_OF, raw, new Trace()));
        CardValuator.Input likelySeven = new CardValuator.Input("card", GradeDistribution.certain(Grade.G7), floored, d("0.05"),
                d("30"), 1, Confidence.A);
        CardValuator.Input likelyNine = new CardValuator.Input("card", GradeDistribution.certain(Grade.G9), floored, d("0.05"),
                d("30"), 1, Confidence.A);

        CardValuation seven = new CardValuator(derived).value(likelySeven);

        assertThat(seven.offerConfidence()).isEqualTo(Confidence.B);
        assertThat(seven.trace().stepsFor("offer_confidence").getFirst().detail()).contains("raw floor");
        // The 9 nets above its floor here, so it isn't floored and keeps its A.
        assertThat(new CardValuator(derived).value(likelyNine).offerConfidence()).isEqualTo(Confidence.A);
    }

    @Test
    void theParameterSetRefusesFloorsOnOneAndOverlapsButAllowsNineAndTen() throws Exception {
        assertThatThrownBy(() -> with("pricing.rawFloor.NEAR_MINT", "11")).hasMessageContaining("2 to 10");
        assertThatThrownBy(() -> with("pricing.rawFloor.HEAVILY_PLAYED", "1")).hasMessageContaining("never 1");
        assertThatThrownBy(() -> with("pricing.rawFloor.LIGHTLY_PLAYED", "6,7")).hasMessageContaining("two conditions");
        assertThatThrownBy(() -> with("pricing.rawFloor.quantile", "0")).hasMessageContaining("quantile");
        assertThat(with("pricing.rawFloor.MODERATELY_PLAYED", "3,4").rawFloorGrades(RawFloors.Condition.MODERATELY_PLAYED))
                .containsExactly(Grade.G3, Grade.G4);
        assertThat(with("pricing.rawFloor.NEAR_MINT", "7,8,9,10").rawFloorGrades(RawFloors.Condition.NEAR_MINT))
                .containsExactly(Grade.G7, Grade.G8, Grade.G9, Grade.G10);
    }

    private static ParameterSet with(String key, String value) throws Exception {
        Properties p = new Properties();
        try (InputStream in = ParameterSet.class.getResourceAsStream("/parameter-sets/1.8.0.properties")) {
            p.load(in);
        }
        p.setProperty(key, value);
        return ParameterSet.fromProperties(p);
    }

    private static List<Comp> concat(List<Comp> a, List<Comp> b) {
        List<Comp> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}

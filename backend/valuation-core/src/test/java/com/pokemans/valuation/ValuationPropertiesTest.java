package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Invariants the methodology promises, over random inputs: probability never gains mass or moves
 * up, prices are never stepped up, grade 1 never anchors, stress and liquidity only ever lower FLV,
 * and the portfolio caps hold.
 */
class ValuationPropertiesTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);
    private static final GradingCost NO_COST = new GradingCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    private static final BigDecimal MASS_TOLERANCE = new BigDecimal("0.000000001");

    private final ParameterSet params = ParameterSet.defaults();

    // --- Distributions -------------------------------------------------------------------------

    @Provide
    Arbitrary<GradeDistribution> distributions() {
        return Arbitraries.integers().between(0, 100).list().ofSize(11)
                .filter(w -> w.stream().mapToInt(Integer::intValue).sum() > 0)
                .map(ValuationPropertiesTest::distribution);
    }

    private static GradeDistribution distribution(List<Integer> weights) {
        BigDecimal total = BigDecimal.valueOf(weights.stream().mapToInt(Integer::intValue).sum());
        Map<Grade, BigDecimal> p = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            p.put(grade, BigDecimal.valueOf(weights.get(grade.ordinal())).divide(total, Money.MC));
        }
        return GradeDistribution.of(p);
    }

    private static BigDecimal mass(GradeDistribution d) {
        return d.asMap().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Property
    void shiftDownKeepsMassAndUAndNeverRaisesTheMedian(@ForAll("distributions") GradeDistribution d,
                                                       @ForAll @IntRange(min = 0, max = 12) int steps) {
        GradeDistribution shifted = d.shiftDown(steps);

        assertThat(mass(shifted).subtract(BigDecimal.ONE).abs()).isLessThanOrEqualTo(MASS_TOLERANCE);
        assertThat(shifted.p(Grade.U)).isEqualByComparingTo(d.p(Grade.U));
        assertThat(shifted.median().ordinal()).isLessThanOrEqualTo(d.median().ordinal());
    }

    @Property
    void movingMassDownKeepsMassAndUAndNeverRaisesTheMedian(@ForAll("distributions") GradeDistribution d,
                                                            @ForAll @IntRange(min = 0, max = 100) int percent) {
        BigDecimal fraction = BigDecimal.valueOf(percent, 2);
        for (GradeDistribution moved : List.of(d.moveMassDown(fraction), d.applyPhotoConfidencePenalty(fraction),
                d.applySurfacePenalty(fraction))) {
            assertThat(mass(moved).subtract(BigDecimal.ONE).abs()).isLessThanOrEqualTo(MASS_TOLERANCE);
            assertThat(moved.p(Grade.U)).isEqualByComparingTo(d.p(Grade.U));
            assertThat(moved.median().ordinal()).isLessThanOrEqualTo(d.median().ordinal());
        }
    }

    // --- Pricing -------------------------------------------------------------------------------

    /** A random observed ladder: prices for a random subset of grades 2-10, an optional grade 1, era ratios. */
    record Ladder(Map<Grade, BigDecimal> observed, BigDecimal gradeOne, Map<Grade, BigDecimal> ratios) {
    }

    private static Arbitrary<BigDecimal> prices() {
        return Arbitraries.bigDecimals().between(new BigDecimal("1"), new BigDecimal("10000")).ofScale(2);
    }

    @Provide
    Arbitrary<Ladder> ladders() {
        Arbitrary<Map<Grade, BigDecimal>> observed = Arbitraries.maps(Arbitraries.integers().between(2, 10).map(Grade::of), prices())
                .ofMinSize(1).ofMaxSize(9);
        Arbitrary<BigDecimal> gradeOne = prices().injectNull(0.3);
        // Often no era ratios at all, so the card's own ladder and refusals get exercised.
        Arbitrary<Map<Grade, BigDecimal>> ratios = Arbitraries.oneOf(
                Arbitraries.just(Map.<Grade, BigDecimal>of()),
                Arbitraries.maps(Arbitraries.integers().between(2, 9).map(Grade::of),
                        Arbitraries.bigDecimals().between(new BigDecimal("0.01"), BigDecimal.ONE).ofScale(4)).ofMaxSize(8));
        return Combinators.combine(observed, gradeOne, ratios).as(Ladder::new);
    }

    private int nextId;

    private GradePriceTable price(Ladder ladder, BigDecimal gradeOneMultiplier) {
        Map<Grade, List<Comp>> comps = new EnumMap<>(Grade.class);
        ladder.observed().forEach((grade, price) -> comps.put(grade, twoSales(price)));
        if (ladder.gradeOne() != null) {
            comps.put(Grade.G1, twoSales(ladder.gradeOne().multiply(gradeOneMultiplier)));
        }
        return new GradePricer(params).price(new GradePricer.Input(AS_OF, comps, List.of(), ladder.ratios(), NO_COST, Map.of()));
    }

    private List<Comp> twoSales(BigDecimal price) {
        return List.of(new Comp("c" + nextId++, AS_OF.minusDays(1), price), new Comp("c" + nextId++, AS_OF.minusDays(2), price));
    }

    /** The pricer refuses some ladders (no ratio and no card ladder); those prove nothing here. */
    private Optional<GradePriceTable> priced(Ladder ladder, BigDecimal gradeOneMultiplier) {
        try {
            return Optional.of(price(ladder, gradeOneMultiplier));
        } catch (IllegalArgumentException refused) {
            return Optional.empty();
        }
    }

    @Property(maxDiscardRatio = 20)
    void derivedPricesNeverExceedTheNearestObservedGradeAbove(@ForAll("ladders") Ladder ladder) {
        Optional<GradePriceTable> priced = priced(ladder, BigDecimal.ONE);
        Assume.that(priced.isPresent());
        GradePriceTable table = priced.get();

        for (int g = 1; g <= 9; g++) {
            GradePrice price = table.get(Grade.of(g));
            if (price.method() == GradePrice.Method.OBSERVED) {
                continue;
            }
            for (int above = g + 1; above <= 10; above++) {
                if (table.get(Grade.of(above)).method() == GradePrice.Method.OBSERVED) {
                    assertThat(price.gross()).as("grade %d vs observed grade %d", g, above)
                            .isLessThanOrEqualTo(table.gross(Grade.of(above)));
                    break;
                }
            }
        }
    }

    @Property(maxDiscardRatio = 20)
    void interpolatedPricesLieBetweenTheirAnchors(@ForAll("ladders") Ladder ladder) {
        Optional<GradePriceTable> priced = priced(ladder, BigDecimal.ONE);
        Assume.that(priced.isPresent());
        GradePriceTable table = priced.get();

        for (GradePrice price : table.all()) {
            if (price.method() != GradePrice.Method.INTERPOLATED) {
                continue;
            }
            BigDecimal a = table.gross(price.basis().get(0));
            BigDecimal b = table.gross(price.basis().get(1));
            assertThat(price.gross()).isBetween(Money.min(a, b), Money.max(a, b));
            assertThat(price.basis()).doesNotContain(Grade.G1);
        }
    }

    @Property(maxDiscardRatio = 20)
    void gradeOneNeverAnchorsAnotherGrade(@ForAll("ladders") Ladder ladder) {
        Assume.that(ladder.gradeOne() != null);
        Optional<GradePriceTable> base = priced(ladder, BigDecimal.ONE);
        Optional<GradePriceTable> premium = priced(ladder, BigDecimal.valueOf(100));
        // Refusal can't depend on the grade 1 price either.
        assertThat(premium.isPresent()).isEqualTo(base.isPresent());
        Assume.that(base.isPresent());

        for (int g = 2; g <= 10; g++) {
            Grade grade = Grade.of(g);
            assertThat(premium.get().gross(grade)).as("grade %d", g).isEqualByComparingTo(base.get().gross(grade));
            assertThat(premium.get().get(grade).method()).isEqualTo(base.get().get(grade).method());
        }
    }

    // --- Card values ---------------------------------------------------------------------------

    record CardCase(GradeDistribution distribution, GradePriceTable prices, BigDecimal sigma, BigDecimal salesPerMonth,
                    int copies, Confidence photo) {

        CardValuator.Input input() {
            return input(sigma, copies);
        }

        CardValuator.Input input(BigDecimal sigmaOverride, int copiesOverride) {
            return new CardValuator.Input("card", distribution, prices, sigmaOverride, salesPerMonth, copiesOverride, photo);
        }
    }

    @Provide
    Arbitrary<GradePriceTable> priceTables() {
        Arbitrary<GradePrice.Method> methods = Arbitraries.of(GradePrice.Method.OBSERVED, GradePrice.Method.OBSERVED,
                GradePrice.Method.STEP_DOWN, GradePrice.Method.INTERPOLATED);
        Arbitrary<BigDecimal> net = Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("5000")).ofScale(2);
        Arbitrary<Integer> sales = Arbitraries.integers().between(0, 30);
        Arbitrary<GradePrice> one = Combinators.combine(net, methods, sales)
                .as((p, m, n) -> GradePrice.given(Grade.G10, p, m, m == GradePrice.Method.OBSERVED ? n : 0));
        return one.list().ofSize(11).map(list -> {
            Map<Grade, GradePrice> prices = new EnumMap<>(Grade.class);
            for (Grade grade : Grade.values()) {
                GradePrice p = list.get(grade.ordinal());
                prices.put(grade, GradePrice.given(grade, p.net(), p.method(), p.salesUsed()));
            }
            return new GradePriceTable(prices, new Trace());
        });
    }

    @Provide
    Arbitrary<CardCase> cardCases() {
        return Combinators.combine(
                distributions(),
                priceTables(),
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("0.6")).ofScale(3),
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("50")).ofScale(2),
                Arbitraries.integers().between(1, 20),
                Arbitraries.of(Confidence.class)).as(CardCase::new);
    }

    @Property
    void stressedValuesStayBelowCentralOnes(@ForAll("cardCases") CardCase c) {
        CardValuation v = new CardValuator(params).value(c.input());

        assertThat(v.stressedFmv()).isLessThanOrEqualTo(v.fmv());
        assertThat(v.flv()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(v.flv()).isLessThanOrEqualTo(v.stressedFmv());
        if (v.fmv().compareTo(BigDecimal.ONE) >= 0) {
            assertThat(v.qsv()).isLessThan(v.fmv());
            assertThat(v.fmv()).isLessThan(v.rv());
        }
        assertThat(v.flv()).isLessThanOrEqualTo(v.qsv());
    }

    @Property
    void moreVolatilityNeverRaisesFlv(@ForAll("cardCases") CardCase c,
                                      @ForAll @IntRange(min = 0, max = 300) int extraThousandths) {
        CardValuator valuator = new CardValuator(params);
        BigDecimal higher = c.sigma().add(BigDecimal.valueOf(extraThousandths, 3));

        assertThat(valuator.value(c.input(higher, c.copies())).flv()).isLessThanOrEqualTo(valuator.value(c.input()).flv());
    }

    @Property
    void moreCopiesNeverRaiseFlv(@ForAll("cardCases") CardCase c, @ForAll @IntRange(min = 0, max = 50) int extraCopies) {
        CardValuator valuator = new CardValuator(params);

        assertThat(valuator.value(c.input(c.sigma(), c.copies() + extraCopies)).flv())
                .isLessThanOrEqualTo(valuator.value(c.input()).flv());
    }

    // --- Portfolio -----------------------------------------------------------------------------

    @Provide
    Arbitrary<List<PortfolioEvaluator.Card>> portfolios() {
        Arbitrary<BigDecimal> flv = Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("10000")).ofScale(2);
        Arbitrary<BigDecimal> fmvExtra = Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("2000")).ofScale(2);
        Arbitrary<Confidence> confidence = Arbitraries.of(Confidence.A, Confidence.A, Confidence.B, Confidence.C, Confidence.D);
        Arbitrary<String> set = Arbitraries.of("base", "jungle", "fossil", "rocket", "neo");
        Arbitrary<String> character = Arbitraries.of("charizard", "pikachu", "mewtwo", "blastoise", "venusaur", "gengar");
        Arbitrary<Boolean> often = Arbitraries.of(true, true, true, true, false);
        Arbitrary<Boolean> rarely = Arbitraries.of(false, false, false, false, true);
        Arbitrary<PortfolioEvaluator.Card> card = Combinators.combine(flv, fmvExtra, confidence, set, character,
                        Arbitraries.of(true, false), rarely, often)
                .as((f, extra, conf, s, ch, graded, auth, custody) -> new PortfolioEvaluator.Card(
                        new CardValuation("tmp", f.add(extra), f, f, f, f, conf, 2, ParameterSet.LiquidityTier.MID,
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, new Trace()),
                        s, ch, graded, auth, custody));
        return card.list().ofMinSize(1).ofMaxSize(30).map(cards -> {
            List<PortfolioEvaluator.Card> withIds = new ArrayList<>();
            for (int i = 0; i < cards.size(); i++) {
                PortfolioEvaluator.Card c = cards.get(i);
                CardValuation v = c.valuation();
                withIds.add(new PortfolioEvaluator.Card(new CardValuation("card-" + i, v.fmv(), v.rv(), v.qsv(), v.stressedFmv(),
                        v.flv(), v.confidence(), v.stressShift(), v.liquidityTier(), v.searchPremium(), v.speedDiscount(),
                        v.volatilityShock(), v.depthDiscount(), v.trace()), c.setKey(), c.characterKey(), c.graded(),
                        c.authFlagUnresolved(), c.custodyVerified()));
            }
            return withIds;
        });
    }

    @Property
    void portfolioCapsHold(@ForAll("portfolios") List<PortfolioEvaluator.Card> cards,
                           @ForAll @IntRange(min = 0, max = 100) int alphaPercent) {
        BigDecimal alpha = BigDecimal.valueOf(alphaPercent, 2);
        PortfolioEvaluator.Result result = new PortfolioEvaluator(params).evaluate(cards, Optional.of(alpha));
        BigDecimal eligible = result.eligibleFlv();
        BigDecimal cent = new BigDecimal("0.01");

        assertThat(result.advance().orElseThrow()).isLessThanOrEqualTo(Money.cents(eligible.multiply(alpha)).add(cent));
        assertThat(result.collateralBase()).isLessThanOrEqualTo(eligible);
        List<BigDecimal> contributions = result.cards().stream().map(PortfolioEvaluator.Contribution::contribution)
                .sorted(Comparator.reverseOrder()).toList();
        long eligibleCards = result.cards().stream().filter(PortfolioEvaluator.Contribution::eligible).count();
        if (eligibleCards >= params.capsMinCards()) {
            // Caps hold against the capped base; the correlation haircut after them takes at most 19% more off
            // (two 10% slices), so against the final base allow that much.
            BigDecimal haircutRoom = BigDecimal.ONE.subtract(params.correlationHaircut()).pow(2);
            BigDecimal preHaircut = result.collateralBase().divide(haircutRoom, Money.MC);
            for (BigDecimal contribution : contributions) {
                assertThat(contribution).isLessThanOrEqualTo(preHaircut.multiply(params.singleCardCap()).add(cent));
            }
            BigDecimal topFive = contributions.stream().limit(5).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(topFive).isLessThanOrEqualTo(preHaircut.multiply(params.topFiveCap()).add(cent));
        }
        for (PortfolioEvaluator.Contribution c : result.cards()) {
            if (!c.eligible()) {
                assertThat(c.contribution()).isEqualByComparingTo("0");
            }
            assertThat(c.contribution()).isLessThanOrEqualTo(c.flv());
        }
        assertThat(result.suggestedAdvanceLow()).isLessThanOrEqualTo(result.suggestedAdvanceHigh());
    }
}

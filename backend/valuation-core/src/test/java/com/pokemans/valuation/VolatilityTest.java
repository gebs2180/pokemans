package com.pokemans.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VolatilityTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);
    private int nextId;

    private Comp comp(int daysAgo, String price) {
        return new Comp("c" + nextId++, AS_OF.minusDays(daysAgo), new BigDecimal(price));
    }

    @Test
    void monthlyIsTheSampleStdDevOfRelativeChanges() {
        // Changes +10%, -10%: mean 0, sample variance 0.02.
        BigDecimal sigma = Volatility.monthly(List.of(new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("99")));

        assertThat(sigma.doubleValue()).isCloseTo(Math.sqrt(0.02), org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void fromCompsUsesTwelveThirtyDayChanges() {
        // One sale per bucket, alternating 100 / 110: changes +10%, -9.09%, ...
        List<Comp> comps = new ArrayList<>();
        List<BigDecimal> points = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            String price = i % 2 == 0 ? "100" : "110";
            comps.add(comp((12 - i) * 30, price));
            points.add(new BigDecimal(price));
        }

        Optional<Volatility.Estimate> estimate = Volatility.fromComps(Map.of(Grade.G9, comps), AS_OF, 12);

        assertThat(estimate).isPresent();
        assertThat(estimate.get().changes()).isEqualTo(12);
        assertThat(estimate.get().sigma()).isEqualByComparingTo(Volatility.monthly(points));
    }

    @Test
    void neverBridgesAGapBetweenBuckets() {
        // Sales 0, 30 and 90 days ago: only the 30 -> 0 change is a 30-day change.
        Map<Grade, List<Comp>> comps = Map.of(Grade.G9, List.of(comp(90, "50"), comp(30, "100"), comp(0, "110")));

        assertThat(Volatility.fromComps(comps, AS_OF, 12)).isEmpty();
    }

    @Test
    void poolsChangesAcrossGrades() {
        Map<Grade, List<Comp>> comps = Map.of(
                Grade.G9, List.of(comp(30, "100"), comp(0, "110")),
                Grade.G10, List.of(comp(30, "300"), comp(0, "270")));

        Optional<Volatility.Estimate> estimate = Volatility.fromComps(comps, AS_OF, 12);

        assertThat(estimate).isPresent();
        assertThat(estimate.get().changes()).isEqualTo(2);
        // +10% and -10%.
        assertThat(estimate.get().sigma().doubleValue()).isCloseTo(Math.sqrt(0.02), org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void ignoresSalesOlderThanTheLookback() {
        Map<Grade, List<Comp>> comps = Map.of(Grade.G9, List.of(comp(500, "10"), comp(470, "1000"), comp(30, "100"), comp(0, "110")));

        assertThat(Volatility.fromComps(comps, AS_OF, 12)).isEmpty();
    }

    @Test
    void needsAtLeastTwoMonths() {
        assertThatThrownBy(() -> Volatility.fromComps(Map.of(), AS_OF, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverPoolsRawUngradableSales() {
        Map<Grade, List<Comp>> comps = Map.of(
                Grade.G9, List.of(comp(30, "100"), comp(0, "110")),
                Grade.U, List.of(comp(30, "10"), comp(0, "20")));

        assertThatThrownBy(() -> Volatility.fromComps(comps, AS_OF, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graded sales only");
    }

    @Test
    void monthlyRefusesAZeroOrNegativePrice() {
        assertThatThrownBy(() -> Volatility.monthly(List.of(new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("99"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> Volatility.monthly(List.of(new BigDecimal("100"), new BigDecimal("-1"), new BigDecimal("99"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void monthlyNeedsThreePoints() {
        assertThatThrownBy(() -> Volatility.monthly(List.of(new BigDecimal("100"), new BigDecimal("110"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEvenBucketTakesTheLowerMiddleSale() {
        // Bucket ending today: 100 and 120, median taken as 100 (conservative); the one before: 100 and 80, so 80.
        Map<Grade, List<Comp>> comps = Map.of(Grade.G9, List.of(
                comp(31, "100"), comp(40, "80"), comp(0, "100"), comp(1, "120"),
                comp(61, "100")));

        Optional<Volatility.Estimate> estimate = Volatility.fromComps(comps, AS_OF, 12);

        assertThat(estimate).isPresent();
        assertThat(estimate.get().changes()).isEqualTo(2);
        assertThat(estimate.get().sigma()).isEqualByComparingTo(
                Volatility.monthly(List.of(new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("100"))));
    }
}

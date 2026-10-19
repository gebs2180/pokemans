package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.pricing.PriceSource.GradingCompany;
import com.pokemans.app.pricing.PriceSource.RawCondition;
import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradePrice;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Identified cards get comps from a (fixture) price source, then per-grade snapshots with method and comp ids. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PricingFlowTest.FixtureConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class PricingFlowTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfig {
        @Bean
        FixturePriceSource fixturePriceSource() {
            return new FixturePriceSource();
        }
    }

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private FixturePriceSource source;

    @Autowired
    private PricingRefresh refresh;

    @Autowired
    private Prices prices;

    @Autowired
    private JdbcTemplate jdbc;

    private TestApi api;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        source.clear();
        orgId = api.collectorOrg("Pricing Collector " + UUID.randomUUID(), "auth0|pricing-" + UUID.randomUUID());
    }

    /** A physical card identified by hand as a new catalog card. Returns the catalog card id. */
    private UUID identified(String name, String number, LocalDate released) throws Exception {
        String cardId = api.card(orgId, name, api.admin());
        String json = "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\","
                + "\"setName\":\"Pricing Set " + released.getYear() + "\",\"setReleaseDate\":\"" + released + "\","
                + "\"number\":\"" + number + "\",\"name\":\"" + name + "\"}";
        return UUID.fromString(TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                json, api.admin()).andExpect(status().isCreated()), "$.current.card.id"));
    }

    private static LocalDate daysAgo(int days) {
        return TODAY.minusDays(days);
    }

    @Test
    void ingestsSoldCompsAndSnapshotsEveryGrade() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LocalDate wotc = LocalDate.of(1999, 1, 9);
        UUID target = identified("Target", "T-" + suffix, wotc);
        UUID[] comparables = {
                identified("Comparable A", "A-" + suffix, wotc),
                identified("Comparable B", "B-" + suffix, wotc),
                identified("Comparable C", "C-" + suffix, wotc)};
        UUID modern = identified("Modern", "M-" + suffix, LocalDate.of(2022, 5, 1));

        // Target: PSA 10 monthly for a year (σ), recent 9s, 8.5s, 2s, damaged raw (U), plus noise.
        for (int k = 0; k <= 12; k++) {
            source.graded(target, GradingCompany.PSA, "10", k % 2 == 0 ? "1000" : "1100", daysAgo(30 * k + 5));
        }
        source.graded(target, GradingCompany.PSA, "9", "400", daysAgo(3));
        source.graded(target, GradingCompany.PSA, "9", "400", daysAgo(8));
        source.graded(target, GradingCompany.PSA, "8.5", "300", daysAgo(4));
        source.graded(target, GradingCompany.PSA, "8.5", "300", daysAgo(9));
        source.graded(target, GradingCompany.PSA, "2", "40", daysAgo(6));
        source.graded(target, GradingCompany.PSA, "2", "40", daysAgo(12));
        source.raw(target, RawCondition.DAMAGED, "15", daysAgo(7));
        source.raw(target, RawCondition.DAMAGED, "15", daysAgo(14));
        source.graded(target, GradingCompany.CGC, "10", "5000", daysAgo(2)); // CGC is priced on its own
        source.graded(target, GradingCompany.CGC, "2", "30", daysAgo(3));
        source.raw(target, RawCondition.NEAR_MINT, "200", daysAgo(2));       // stored, not used for grades
        PriceSource.Sale twice = source.graded(target, GradingCompany.PSA, "9", "400", daysAgo(20));
        source.add(twice);                                                    // the source repeats a sale
        source.add(new PriceSource.Sale(FixturePriceSource.NAME, "eur-1", target, GradingCompany.PSA, BigDecimal.TEN, null,
                new BigDecimal("900"), "EUR", daysAgo(1), null, PriceSource.ListingType.AUCTION, Map.of()));
        source.graded(target, GradingCompany.PSA, "10", "9999", TODAY.plusDays(3)); // future-dated

        // Comparables give the WotC era its grade 1 step-down ratio (20 / 40).
        for (UUID c : comparables) {
            source.graded(c, GradingCompany.PSA, "1", "20", daysAgo(5));
            source.graded(c, GradingCompany.PSA, "1", "20", daysAgo(6));
            source.graded(c, GradingCompany.PSA, "2", "40", daysAgo(5));
            source.graded(c, GradingCompany.PSA, "2", "40", daysAgo(6));
        }
        // A modern card with only PSA 10s and no era ratios: left unpriced, not guessed.
        source.graded(modern, GradingCompany.PSA, "10", "80", daysAgo(5));
        source.graded(modern, GradingCompany.PSA, "10", "80", daysAgo(6));

        PriceSnapshotService.Result result = refresh.runOnce(TODAY);

        // Ingestion: provenance stored, bad sales rejected, the repeat skipped.
        Map<String, Object> run = jdbc.queryForMap("select * from comp_ingest_run order by started_at desc limit 1");
        assertThat(run.get("source")).isEqualTo(FixturePriceSource.NAME);
        assertThat(run.get("rejected")).isEqualTo(2);
        assertThat((Integer) run.get("duplicates")).isGreaterThanOrEqualTo(1);
        assertThat(run.get("errors").toString()).contains("currency EUR").contains("in the future");
        assertThat(jdbc.queryForObject("select count(*) from sold_comp where catalog_card_id = ?", Integer.class, target))
                .isEqualTo(13 + 2 + 2 + 2 + 2 + 2 + 1 + 1);
        assertThat(jdbc.queryForObject("select count(*) from sold_comp where catalog_card_id = ? and (licence <> 'test fixture'"
                + " or retrieved_at is null or ingest_run_id is null)", Integer.class, target)).isZero();

        // Snapshots: every grade of the target, with method, comps, window and confidence.
        assertThat(result.snapshotSets()).containsKeys(target, comparables[0], comparables[1], comparables[2]);
        assertThat(result.skipped()).containsKey(modern);
        assertThat(result.skipped().get(modern)).singleElement().asString().startsWith("PSA: ").contains("step-down ratio");
        assertThat(prices.latestByCompany(modern)).isEmpty();
        assertThat(result.snapshotSets().get(target)).containsOnlyKeys(GradingCompany.PSA, GradingCompany.CGC);
        assertThat(result.snapshotSets().get(comparables[0])).containsOnlyKeys(GradingCompany.PSA);

        Prices.CardPrices card = prices.latest(target, "PSA").orElseThrow();
        assertThat(card.snapshotSetId()).isEqualTo(result.snapshotSets().get(target).get(GradingCompany.PSA));
        assertThat(card.grades()).hasSize(11);
        assertThat(card.gradingCompany()).isEqualTo("PSA");
        assertThat(card.parameterSet()).isEqualTo("1.0.0");

        Prices.GradeSnapshot g10 = card.grades().get(Grade.G10);
        assertThat(g10.method()).isEqualTo(GradePrice.Method.OBSERVED);
        assertThat(g10.price()).isEqualByComparingTo("1000");
        assertThat(g10.n()).isEqualTo(3);
        assertThat(g10.windowDays()).isEqualTo(90);
        assertThat(g10.confidence()).isEqualTo(Confidence.C);
        assertThat(g10.compIds()).hasSize(3);
        for (UUID compId : g10.compIds()) {
            assertThat(jdbc.queryForObject("select grading_company || ' ' || grade from sold_comp where id = ?", String.class, compId))
                    .isEqualTo("PSA 10.0");
        }

        // 9: two sales plus the repeated one, stored once.
        assertThat(card.grades().get(Grade.G9).n()).isEqualTo(3);
        // 8.5 sales fold into 8.
        assertThat(card.grades().get(Grade.G8).method()).isEqualTo(GradePrice.Method.OBSERVED);
        assertThat(card.grades().get(Grade.G8).price()).isEqualByComparingTo("300");

        // 3-7: log interpolation between 2 and 8, one confidence level below the worse side.
        Prices.GradeSnapshot g5 = card.grades().get(Grade.G5);
        assertThat(g5.method()).isEqualTo(GradePrice.Method.INTERPOLATED);
        assertThat(g5.basis()).containsExactly(Grade.G2, Grade.G8);
        assertThat(g5.price()).isEqualByComparingTo("109.54");
        assertThat(g5.compIds()).isEmpty();
        assertThat(g5.confidence()).isEqualTo(Confidence.D);

        // 1: the comparables' grade 1 sales don't make an era ratio (PSA 1s carry a collector premium), so
        // the comparables give no 2-to-3 ratio to borrow either: grade 1 steps down from grade 2 with the
        // card's own ladder, never above grade 2.
        Prices.GradeSnapshot g1 = card.grades().get(Grade.G1);
        assertThat(g1.method()).isEqualTo(GradePrice.Method.CARD_STEP_DOWN);
        assertThat(g1.stepDownRatio()).isLessThanOrEqualTo(java.math.BigDecimal.ONE);
        assertThat(g1.price()).isLessThanOrEqualTo(card.grades().get(Grade.G2).price());

        // U from damaged raw sales; σ from the monthly 10s; sales rate from every comp.
        assertThat(card.grades().get(Grade.U).method()).isEqualTo(GradePrice.Method.OBSERVED);
        assertThat(card.grades().get(Grade.U).price()).isEqualByComparingTo("15");
        assertThat(card.sigma()).isNotNull();
        assertThat(card.sigmaChanges()).isEqualTo(12);
        assertThat(card.salesPerMonth()).isEqualByComparingTo("5.00"); // 15 comps in 90 days, raw and every grader

        // CGC: its own table from CGC sales only, never mixed with or converted from PSA.
        Prices.CardPrices cgc = prices.latestByCompany(target).get("CGC");
        assertThat(cgc.gradingCompany()).isEqualTo("CGC");
        assertThat(cgc.grades()).hasSize(11);
        assertThat(cgc.grades().get(Grade.G10).price()).isEqualByComparingTo("5000");
        assertThat(cgc.grades().get(Grade.G10).compIds()).singleElement().satisfies(compId ->
                assertThat(jdbc.queryForObject("select grading_company from sold_comp where id = ?", String.class, compId)).isEqualTo("CGC"));
        assertThat(cgc.grades().get(Grade.G5).method()).isEqualTo(GradePrice.Method.INTERPOLATED);
        assertThat(cgc.grades().get(Grade.G5).basis()).containsExactly(Grade.G2, Grade.G10);
        assertThat(cgc.grades().get(Grade.U).price()).isEqualByComparingTo("15"); // damaged raw sales serve every table
        assertThat(cgc.sigma()).isEqualByComparingTo(card.sigma());               // σ belongs to the card
        assertThat(cgc.salesPerMonth()).isEqualByComparingTo(card.salesPerMonth());

        // A comparable with nothing above grade 2: higher grades carry grade 2's price, never stepped up.
        Prices.CardPrices comparable = prices.latest(comparables[0], "PSA").orElseThrow();
        assertThat(comparable.grades().get(Grade.G10).method()).isEqualTo(GradePrice.Method.CARRIED_DOWN);
        assertThat(comparable.grades().get(Grade.G10).price()).isEqualByComparingTo("40");
        // No damaged sales: U is worth zero. There is no bulk floor.
        assertThat(comparable.grades().get(Grade.U).method()).isEqualTo(GradePrice.Method.NO_SALES);
        assertThat(comparable.grades().get(Grade.U).price()).isEqualByComparingTo("0");

        // Running again stores no new comps and appends a new snapshot set.
        int before = jdbc.queryForObject("select count(*) from sold_comp", Integer.class);
        PriceSnapshotService.Result again = refresh.runOnce(TODAY);
        assertThat(jdbc.queryForObject("select count(*) from sold_comp", Integer.class)).isEqualTo(before);
        UUID newPsaSet = again.snapshotSets().get(target).get(GradingCompany.PSA);
        assertThat(newPsaSet).isNotEqualTo(card.snapshotSetId());
        assertThat(prices.latest(target, "PSA").orElseThrow().snapshotSetId()).isEqualTo(newPsaSet);
        assertThat(prices.bySet(card.snapshotSetId())).isPresent();

        // Append-only is enforced by the database, not only by the code.
        for (String table : new String[] {"sold_comp", "price_snapshot", "price_stats"}) {
            assertThatThrownBy(() -> jdbc.update("update " + table + " set created_at = now() where catalog_card_id = ?", target))
                    .hasMessageContaining("append-only");
        }
    }
}

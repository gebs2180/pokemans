package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.pricing.PriceSource.GradingCompany;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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

/**
 * Ingestion and refresh with more than one instance: duplicates skipped rather than rolling a run
 * back, one card's failure kept to that card, and one refresh at a time.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PricingConcurrencyTest.FixtureConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class PricingConcurrencyTest {

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
    private CompIngestionService ingestion;

    @Autowired
    private PricingRefresh refresh;

    @Autowired
    private Catalog catalog;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private TestApi api;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        source.clear();
        orgId = api.collectorOrg("Concurrency Collector " + UUID.randomUUID(), "auth0|concurrency-" + UUID.randomUUID());
    }

    private Catalog.CardSummary identified(String name) throws Exception {
        String cardId = api.card(orgId, name, api.admin());
        String json = "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\","
                + "\"setName\":\"Concurrency Set\",\"setReleaseDate\":\"1999-01-09\","
                + "\"number\":\"" + UUID.randomUUID().toString().substring(0, 8) + "\",\"name\":\"" + name + "\"}";
        UUID id = UUID.fromString(TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                json, api.admin()).andExpect(status().isCreated()), "$.current.card.id"));
        return catalog.cards(List.of(id)).getFirst();
    }

    private int comps(UUID catalogCardId) {
        return jdbc.queryForObject("select count(*) from sold_comp where catalog_card_id = ?", Integer.class, catalogCardId);
    }

    @Test
    void oneCardFailingToStoreDoesNotRollBackTheOthersAndTheRunIsAudited() throws Exception {
        Catalog.CardSummary before = identified("Before");
        Catalog.CardSummary after = identified("After");
        // Not in the catalog: its sale passes the checks but breaks the foreign key on insert.
        Catalog.CardSummary missing = new Catalog.CardSummary(UUID.randomUUID(), null, "en", "Nowhere", TODAY, "0", "Missing",
                before.edition(), before.variant(), null, false);
        source.graded(before.catalogCardId(), GradingCompany.PSA, "9", "100", TODAY.minusDays(2));
        source.graded(missing.catalogCardId(), GradingCompany.PSA, "9", "100", TODAY.minusDays(2));
        source.graded(after.catalogCardId(), GradingCompany.PSA, "9", "100", TODAY.minusDays(2));
        source.graded(after.catalogCardId(), GradingCompany.PSA, "8", "60", TODAY.minusDays(3));

        CompIngestRun run = ingestion.ingest(source, List.of(before, missing, after), TODAY.minusDays(90), TODAY);

        assertThat(comps(before.catalogCardId())).isEqualTo(1);
        assertThat(comps(after.catalogCardId())).isEqualTo(2);
        assertThat(run.getInserted()).isEqualTo(3);
        assertThat(run.getErrors()).singleElement().asString().startsWith(missing.catalogCardId() + ": storing failed");
        Map<String, Object> row = jdbc.queryForMap("select * from comp_ingest_run where id = ?", run.getId());
        assertThat(row.get("inserted")).isEqualTo(3);
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("errors").toString()).contains("storing failed");
    }

    @Test
    void concurrentRunsOfTheSameSalesStoreEachSaleOnceAndBothFinish() throws Exception {
        List<Catalog.CardSummary> cards = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Catalog.CardSummary card = identified("Busy " + i);
            cards.add(card);
            for (int s = 0; s < 10; s++) {
                source.graded(card.catalogCardId(), GradingCompany.PSA, "9", String.valueOf(100 + s), TODAY.minusDays(s + 1));
            }
        }
        int sales = 40;

        CountDownLatch start = new CountDownLatch(1);
        Callable<CompIngestRun> run = () -> {
            start.await();
            return ingestion.ingest(source, cards, TODAY.minusDays(90), TODAY);
        };
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<CompIngestRun>> runs = List.of(pool.submit(run), pool.submit(run), pool.submit(run));
            start.countDown();
            int inserted = 0;
            int duplicates = 0;
            for (Future<CompIngestRun> f : runs) {
                CompIngestRun r = f.get();
                assertThat(r.getErrors()).isEmpty();
                inserted += r.getInserted();
                duplicates += r.getDuplicates();
            }
            assertThat(inserted).isEqualTo(sales);
            assertThat(duplicates).isEqualTo(2 * sales);
        } finally {
            pool.shutdownNow();
        }
        for (Catalog.CardSummary card : cards) {
            assertThat(comps(card.catalogCardId())).isEqualTo(10);
        }

        // And again: nothing new, every sale a duplicate, no rollback.
        CompIngestRun again = ingestion.ingest(source, cards, TODAY.minusDays(90), TODAY);
        assertThat(again.getInserted()).isZero();
        assertThat(again.getDuplicates()).isEqualTo(sales);
    }

    @Test
    void aRefreshWhileAnotherIsRunningIsRefusedWith409() throws Exception {
        Catalog.CardSummary card = identified("Refreshed");
        String url = "/api/admin/catalog-cards/" + card.catalogCardId() + "/prices/refresh";

        // Another instance's refresh holds the lock.
        try (Connection other = dataSource.getConnection()) {
            advisory(other, "select pg_advisory_lock(?)");
            api.postJson(url, "", api.admin()).andExpect(status().isConflict());
            assertThatThrownBy(() -> refresh.runOnce(TODAY)).isInstanceOf(PricingLock.Busy.class);
            advisory(other, "select pg_advisory_unlock(?)");
        }

        api.postJson(url, "", api.admin()).andExpect(status().isOk());
        refresh.runOnce(TODAY); // and the lock was released after each run
        api.postJson(url, "", api.admin()).andExpect(status().isOk());
    }

    @Test
    void theNightlyRunAndTheRefreshButtonNeverOverlap() throws Exception {
        Catalog.CardSummary card = identified("Raced");
        // Grades 1 and 2 price without any step-down ratio (higher grades carry grade 2's price).
        for (String grade : new String[] {"1", "1", "2", "2"}) {
            source.graded(card.catalogCardId(), GradingCompany.PSA, grade, grade.equals("1") ? "20" : "40", TODAY.minusDays(2));
        }
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        source.onFetch(() -> {
            fetching.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        String url = "/api/admin/catalog-cards/" + card.catalogCardId() + "/prices/refresh";
        int setsBefore = sets(card);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PriceSnapshotService.Result> nightly = pool.submit(() -> refresh.runOnce(TODAY));
            assertThat(fetching.await(30, TimeUnit.SECONDS)).isTrue();

            // While the nightly run is ingesting, a click and a second nightly run are both turned away.
            api.postJson(url, "", api.admin()).andExpect(status().isConflict());
            assertThatThrownBy(() -> refresh.runOnce(TODAY)).isInstanceOf(PricingLock.Busy.class);

            release.countDown();
            nightly.get(60, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(sets(card)).isEqualTo(setsBefore + 1);
        api.postJson(url, "", api.admin()).andExpect(status().isOk());
        assertThat(sets(card)).isEqualTo(setsBefore + 2);
    }

    private int sets(Catalog.CardSummary card) {
        return jdbc.queryForObject("select count(distinct snapshot_set_id) from price_snapshot where catalog_card_id = ?",
                Integer.class, card.catalogCardId());
    }

    private static void advisory(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, PricingLock.KEY);
            statement.execute();
        }
    }
}

package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.valuation.ParameterSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Review A3: the snapshot job commits each card on its own, so one card failing doesn't undo the rest, and a
 * run that didn't finish is resumed by the next one for the same date, which skips the cards it had done.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PriceSnapshotRunTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private PriceSnapshotService snapshots;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ParameterSet params;

    private TestApi api;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        orgId = api.collectorOrg("Snapshot Runs " + UUID.randomUUID(), "auth0|runs-" + UUID.randomUUID());
    }

    /** An identified card with PSA 2, 4 and 10 sales, enough to price its whole PSA table. */
    private UUID pricedCard(String name) throws Exception {
        String cardId = api.card(orgId, name, api.admin());
        String json = "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\","
                + "\"setName\":\"Run Set\",\"setReleaseDate\":\"2005-08-01\",\"number\":\"" + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"name\":\"" + name + "\"}";
        String card = TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                json, api.admin()).andExpect(status().isCreated()), "$.current.card.id");
        for (String[] sale : new String[][] {{"10", "500", "3"}, {"4", "60", "4"}, {"2", "20", "5"}}) {
            api.postJson("/api/admin/catalog-cards/" + card + "/comps", "{\"gradingCompany\":\"PSA\",\"grade\":" + sale[0]
                    + ",\"price\":" + sale[1] + ",\"soldOn\":\"" + TODAY.minusDays(Integer.parseInt(sale[2]))
                    + "\",\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\"}", api.admin()).andExpect(status().isCreated());
        }
        return UUID.fromString(card);
    }

    private Map<String, Object> run(UUID id) {
        return jdbc.sql("select status, resumed_from, failed, errors::text as errors from price_snapshot_run where id = :id")
                .param("id", id).query().singleRow();
    }

    private UUID latestRun() {
        return jdbc.sql("select id from price_snapshot_run where as_of = :d order by started_at desc limit 1")
                .param("d", TODAY).query(UUID.class).single();
    }

    @Test
    void oneCardFailingLeavesTheOthersPriced() throws Exception {
        UUID failing = pricedCard("Run Failing");
        UUID fine = pricedCard("Run Fine");
        jdbc.sql("""
                create or replace function test_fail_stats() returns trigger language plpgsql as $$
                begin
                  if new.catalog_card_id = '%s' then raise exception 'test failure for one card'; end if;
                  return new;
                end $$""".formatted(failing)).update();
        jdbc.sql("create trigger test_fail_stats before insert on price_stats for each row execute function test_fail_stats()").update();
        PriceSnapshotService.Result result;
        try {
            result = snapshots.snapshotAll(TODAY, false);
        } finally {
            jdbc.sql("drop trigger test_fail_stats on price_stats").update();
            jdbc.sql("drop function test_fail_stats()").update();
        }

        assertThat(result.snapshotSets()).containsKey(fine).doesNotContainKey(failing);
        assertThat(result.skipped().get(failing)).anySatisfy(s -> assertThat(s).startsWith("Failed"));
        // The failed card's tables were rolled back with it, the other card's kept.
        assertThat(jdbc.sql("select count(*) from price_snapshot where catalog_card_id = :c and as_of = :d")
                .param("c", failing).param("d", TODAY).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from price_snapshot where catalog_card_id = :c and as_of = :d")
                .param("c", fine).param("d", TODAY).query(Integer.class).single()).isEqualTo(11);
        Map<String, Object> run = run(latestRun());
        assertThat(run.get("status")).isEqualTo("COMPLETE");
        assertThat(run.get("failed")).isEqualTo(1);
        assertThat((String) run.get("errors")).contains(failing.toString()).contains("test failure for one card");
    }

    @Test
    void anUnfinishedRunIsResumedAndSkipsTheCardsItHadDone() throws Exception {
        UUID done = pricedCard("Run Done");
        UUID left = pricedCard("Run Left");
        // A run that died after finishing one card.
        UUID crashed = UUID.randomUUID();
        jdbc.sql("""
                        insert into price_snapshot_run (id, as_of, parameter_set, status, cards, priced, started_at)
                        values (:id, :d, :p, 'RUNNING', 2, 1, :now)""")
                .param("id", crashed).param("d", TODAY).param("p", params.version()).param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                        insert into price_snapshot_run_card (snapshot_run_id, catalog_card_id, outcome, created_at)
                        values (:id, :c, 'PRICED', :now)""")
                .param("id", crashed).param("c", done).param("now", OffsetDateTime.now(ZoneOffset.UTC)).update();

        PriceSnapshotService.Result resumed = snapshots.snapshotAll(TODAY);

        assertThat(resumed.snapshotSets()).containsKey(left).doesNotContainKey(done);
        UUID resumer = latestRun();
        assertThat(run(resumer).get("status")).isEqualTo("COMPLETE");
        assertThat(run(resumer).get("resumed_from")).isEqualTo(crashed);
        assertThat(run(crashed).get("status")).isEqualTo("ABORTED");
        assertThat(jdbc.sql("select catalog_card_id from price_snapshot_run_card where snapshot_run_id = :r")
                .param("r", resumer).query(UUID.class).list()).contains(done, left);

        // Rerunning a completed day starts afresh and prices every card again.
        PriceSnapshotService.Result again = snapshots.snapshotAll(TODAY);
        assertThat(again.snapshotSets()).containsKeys(done, left);
        assertThat(run(latestRun()).get("resumed_from")).isNull();
        assertThat(List.of(latestRun())).doesNotContain(resumer);
    }
}

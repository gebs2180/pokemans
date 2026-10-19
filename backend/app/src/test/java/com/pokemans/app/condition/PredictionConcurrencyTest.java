package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Several workers (as on several instances) running at once: each request is submitted and answered
 * once, at most one request is open per card and provider, and a lost capture-completed event is
 * caught by the sweep.
 */
// One request per claim, so the four workers interleave rather than one claiming everything.
@SpringBootTest(properties = {"pokemans.condition.worker.enabled=false", "pokemans.condition.worker.claim-batch=1"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PredictionConcurrencyTest.SlowProviderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class PredictionConcurrencyTest {

    /** Takes a while to answer, so concurrent workers overlap; counts calls per request. */
    static class SlowProvider implements PreGradeProvider {

        final Map<String, AtomicInteger> submits = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> polls = new ConcurrentHashMap<>();

        @Override
        public String name() {
            return "SLOW";
        }

        @Override
        public String submit(Photos photos, String idempotencyKey) {
            submits.computeIfAbsent(idempotencyKey, k -> new AtomicInteger()).incrementAndGet();
            pause();
            return "slow-" + idempotencyKey;
        }

        @Override
        public Poll poll(String externalRequestId) {
            polls.computeIfAbsent(externalRequestId, k -> new AtomicInteger()).incrementAndGet();
            pause();
            return Poll.done(new Reading("slow-1", 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, Map.of()), Map.of("id", externalRequestId));
        }

        private static void pause() {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowProviderConfig {
        @Bean
        SlowProvider slowProvider() {
            return new SlowProvider();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private PredictionWorker worker;

    @Autowired
    private ConditionService condition;

    @Autowired
    private SlowProvider provider;

    @Autowired
    private JdbcTemplate jdbc;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
    }

    /** A single card with passing front and back, in an org of its own (the same photo twice in one org is a duplicate). */
    private String capturedCard() throws Exception {
        String user = "auth0|slow-" + UUID.randomUUID();
        JwtRequestPostProcessor collector = api.user(user, List.of());
        String orgId = api.collectorOrg("Slow Collector " + UUID.randomUUID(), user);
        String cardId = api.card(orgId, "Card", collector);
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        return cardId;
    }

    private List<String> requestIds(List<String> cards) {
        return jdbc.queryForList("select id::text from prediction_request where card_item_id::text in ("
                + String.join(",", cards.stream().map(c -> "'" + c + "'").toList()) + ")", String.class);
    }

    /** Makes every open request due, then runs four workers at once. */
    private void runWorkersConcurrently() throws Exception {
        jdbc.update("update prediction_request set next_attempt_at = now() - interval '1 day' where status in ('PENDING', 'SUBMITTED')");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return worker.runOnce();
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentWorkersSubmitAndRecordEachRequestOnce() throws Exception {
        List<String> cards = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cards.add(capturedCard());
        }
        List<String> ids = requestIds(cards);
        assertThat(ids).hasSize(5);

        runWorkersConcurrently(); // submit
        for (String id : ids) {
            assertThat(provider.submits.get(id)).as("submits of %s", id).hasValue(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from prediction_request where id::text = any(?) and status = 'SUBMITTED'",
                Integer.class, (Object) ids.toArray(String[]::new))).isEqualTo(5);

        runWorkersConcurrently(); // poll: done
        for (String id : ids) {
            assertThat(provider.polls.get("slow-" + id)).as("polls of %s", id).hasValue(1);
        }
        for (String card : cards) {
            assertThat(jdbc.queryForObject("select count(*) from provider_prediction where card_item_id = ?::uuid", Integer.class, card))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from prediction_request where card_item_id = ?::uuid", String.class, card))
                    .isEqualTo("DONE");
            assertThat(jdbc.queryForObject("select count(*) from condition_assessment where card_item_id = ?::uuid and is_current",
                    Integer.class, card)).isEqualTo(1);
        }
    }

    @Test
    void theDatabaseRefusesASecondOpenRequestForTheSameCardAndProvider() throws Exception {
        String card = capturedCard();
        String insert = """
                insert into prediction_request (id, org_id, card_item_id, provider, status, photo_ids, next_attempt_at, created_at)
                select gen_random_uuid(), org_id, card_item_id, provider, ?, photo_ids, now(), now()
                from prediction_request where card_item_id = ?::uuid""";
        assertThatThrownBy(() -> jdbc.update(insert, "PENDING", card)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "SUBMITTED", card)).isInstanceOf(DataIntegrityViolationException.class);
        // Finished requests don't count: the history can hold any number.
        jdbc.update(insert, "FAILED", card);
        jdbc.update(insert, "DONE", card);

        // Asking again while one is open queues nothing.
        UUID orgId = jdbc.queryForObject("select org_id from card_item where id = ?::uuid", UUID.class, card);
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + card + "/prediction-requests", "", api.admin());
        assertThat(jdbc.queryForObject("select count(*) from prediction_request where card_item_id = ?::uuid and status = 'PENDING'",
                Integer.class, card)).isEqualTo(1);
    }

    @Test
    void theSweepQueuesCompletedCapturesWhoseEventWasLost() throws Exception {
        String card = capturedCard();
        // As if the instance died after the photo check committed but before the listener ran.
        jdbc.update("delete from prediction_request where card_item_id = ?::uuid", card);
        Instant now = Instant.now();

        // Too fresh: left to the listener.
        assertThat(condition.sweepMissedCaptures(now)).isZero();

        jdbc.update("update photo set checked_at = checked_at - interval '10 minutes' where card_item_id = ?::uuid", card);
        assertThat(condition.sweepMissedCaptures(now)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from prediction_request where card_item_id = ?::uuid", String.class, card))
                .isEqualTo("PENDING");
        // Once queued, it's not queued again.
        assertThat(condition.sweepMissedCaptures(now)).isZero();

        // Older than the sweep window: left alone.
        String old = capturedCard();
        jdbc.update("delete from prediction_request where card_item_id = ?::uuid", old);
        jdbc.update("update photo set checked_at = checked_at - interval '30 days' where card_item_id = ?::uuid", old);
        assertThat(condition.sweepMissedCaptures(now)).isZero();
    }
}

package com.pokemans.app.catalog;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Several recognition workers at once read each card once; a lost capture-completed event is swept up. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecognitionConcurrencyTest.SlowReaderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class RecognitionConcurrencyTest {

    /** Takes a while to read, so concurrent workers overlap. */
    static class SlowReader implements CardReader {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "SLOW_READER";
        }

        @Override
        public Reading read(byte[] image, String contentType) {
            calls.incrementAndGet();
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Reading("Nobody", "1", 100, "en", false, Variant.HOLO, 2005, null, null, "slow-1", Map.of());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowReaderConfig {
        @Bean
        SlowReader slowReader() {
            return new SlowReader();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private RecognitionService recognition;

    @Autowired
    private SlowReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    /** Matches nothing: no network in tests. */
    @MockitoBean
    private CatalogProvider provider;

    private String capturedCard(TestApi api) throws Exception {
        String user = "auth0|slow-reader-" + UUID.randomUUID();
        JwtRequestPostProcessor collector = api.user(user, List.of());
        String orgId = api.collectorOrg("Slow Reader " + UUID.randomUUID(), user);
        String cardId = api.card(orgId, "Card", collector);
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        return cardId;
    }

    @Test
    void concurrentWorkersReadEachCardOnce() throws Exception {
        TestApi api = new TestApi(mvc, security);
        List<String> cards = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            cards.add(capturedCard(api));
        }
        int before = reader.calls.get();
        jdbc.update("update card_reading set next_attempt_at = now() - interval '1 day' where status = 'PENDING'");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return recognition.runOnce();
                }));
            }
            start.countDown();
            int handled = 0;
            for (Future<Integer> run : runs) {
                handled += run.get();
            }
            assertThat(handled).isEqualTo(12);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reader.calls.get() - before).isEqualTo(12);
        for (String card : cards) {
            assertThat(jdbc.queryForObject("select status from card_reading where card_item_id = ?::uuid", String.class, card))
                    .isEqualTo("DONE");
        }
    }

    @Test
    void theSweepQueuesCompletedCapturesWhoseEventWasLost() throws Exception {
        String card = capturedCard(new TestApi(mvc, security));
        jdbc.update("delete from card_reading where card_item_id = ?::uuid", card);
        Instant now = Instant.now();
        assertThat(recognition.sweepMissedCaptures(now)).isZero(); // too fresh: left to the listener

        jdbc.update("update photo set checked_at = checked_at - interval '10 minutes' where card_item_id = ?::uuid", card);
        assertThat(recognition.sweepMissedCaptures(now)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from card_reading where card_item_id = ?::uuid", String.class, card))
                .isEqualTo("PENDING");
        assertThat(recognition.sweepMissedCaptures(now)).isZero();
        // Out of the way of the other test's count.
        jdbc.update("update card_reading set status = 'FAILED' where card_item_id = ?::uuid", card);
    }
}

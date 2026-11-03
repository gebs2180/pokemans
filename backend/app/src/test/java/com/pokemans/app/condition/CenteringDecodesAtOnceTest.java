package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import com.pokemans.app.storage.StorageProperties;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A local provider decodes full-size photos, so however many worker passes run at once (the kick after
 * each commit runs one on the 8-thread executor), only as many decode as the photo checks allow
 * (review of 2026-11, finding 6).
 */
@SpringBootTest(properties = {"pokemans.condition.worker.enabled=false", "pokemans.condition.worker.claim-batch=1"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CenteringDecodesAtOnceTest.SlowLocalConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class CenteringDecodesAtOnceTest {

    /** Stands in for our centering measurement: answers in one call, slowly, counting how many run at once. */
    static class SlowLocal implements PreGradeProvider.Local {

        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "SLOW_LOCAL";
        }

        @Override
        public Poll grade(Photos photos) {
            calls.incrementAndGet();
            most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return Poll.done(new Reading("slow-local-1", null, null, 10.0, null, null, null, Map.of()), Map.of());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowLocalConfig {
        @Bean
        SlowLocal slowLocal() {
            return new SlowLocal();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private StorageProperties storage;

    @Autowired
    private PredictionWorker worker;

    @Autowired
    private SlowLocal provider;

    @Autowired
    private JdbcTemplate jdbc;

    /** A single card with passing front and back, in an org of its own (the same photo twice in one org is a duplicate). */
    private void capturedCard(TestApi api) throws Exception {
        String user = "auth0|decodes-" + UUID.randomUUID();
        JwtRequestPostProcessor collector = api.user(user, List.of());
        String orgId = api.collectorOrg("Decodes " + UUID.randomUUID(), user);
        String cardId = api.card(orgId, "Card", collector);
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));
    }

    @Test
    void concurrentPassesDecodeNoMoreAtOnceThanThePhotoChecksAllow() throws Exception {
        TestApi api = new TestApi(mvc, security);
        int cards = 6;
        for (int i = 0; i < cards; i++) {
            capturedCard(api);
        }
        jdbc.update("update prediction_request set next_attempt_at = now() - interval '1 day' where status in ('PENDING', 'SUBMITTED')");

        // As many passes at once as cards, as when every kick lands on its own executor thread.
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(cards)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < cards; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return worker.runOnce();
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                run.get();
            }
        }

        assertThat(provider.calls).hasValue(cards);
        assertThat(provider.most.get()).isEqualTo(storage.photoChecksAtOnce());
    }
}

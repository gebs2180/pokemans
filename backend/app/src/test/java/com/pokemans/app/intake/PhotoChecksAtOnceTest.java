package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.PhotoDecodes;
import com.pokemans.app.storage.StorageProperties;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A burst of phone photos can't exhaust the server: only a few are read and decoded at once (audit of 2026-11-02). */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PhotoChecksAtOnceTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private StorageProperties storage;

    @Autowired
    private PhotoDecodes decodes;

    @MockitoSpyBean
    private BlobStore blobs;

    @Test
    void aBurstOfPhotosIsCheckedAFewAtATime() throws Exception {
        TestApi api = new TestApi(mvc, security);
        String subject = "auth0|burst-" + UUID.randomUUID();
        String org = api.collectorOrg("Burst " + UUID.randomUUID(), subject);
        var seller = api.user(subject, List.of());

        byte[] photo = TestImages.goodFront();
        List<String> photoIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            // One card each: a card's photos already take turns on the card's lock.
            String card = api.card(org, "Card " + i, seller);
            String slot = api.postJson("/api/orgs/" + org + "/cards/" + card + "/photos",
                    "{\"side\":\"FRONT\",\"contentType\":\"image/png\",\"sizeBytes\":" + photo.length + "}", seller)
                    .andReturn().getResponse().getContentAsString();
            Map<String, Object> headers = JsonPath.read(slot, "$.headers");
            assertThat(api.put(JsonPath.read(slot, "$.url"), headers, photo)).isEqualTo(200);
            photoIds.add(JsonPath.read(slot, "$.photoId"));
        }

        // Each read holds until every confirm is either reading or queued for a turn (review finding 53): without
        // the limit all six would be reading at once, and with it the rest are provably waiting, not just late.
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        doAnswer(call -> {
            most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                assertThat(gate.await(20, TimeUnit.SECONDS)).as("the test opened the gate").isTrue();
                return call.callRealMethod();
            } finally {
                inFlight.decrementAndGet();
            }
        }).when(blobs).read(any(), any());

        try (ExecutorService pool = Executors.newFixedThreadPool(photoIds.size())) {
            List<Future<Integer>> results = new ArrayList<>();
            for (String id : photoIds) {
                Callable<Integer> confirm = () -> mvc.perform(post("/api/orgs/" + org + "/photos/" + id + "/confirm").with(seller))
                        .andExpect(status().isOk()).andReturn().getResponse().getStatus();
                results.add(pool.submit(confirm));
            }
            long deadline = System.currentTimeMillis() + 10_000;
            while (inFlight.get() + decodes.waiting() < photoIds.size() && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(inFlight.get() + decodes.waiting()).as("every confirm reading or queued").isEqualTo(photoIds.size());
            gate.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        }
        assertThat(most.get()).isEqualTo(storage.photoChecksAtOnce());
    }
}

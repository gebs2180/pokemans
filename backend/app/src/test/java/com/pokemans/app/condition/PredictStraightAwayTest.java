package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
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
 * A completed capture's prediction request is sent to the provider straight away in the background,
 * not at the next poll: the poll is set to an hour here, so only the immediate start can send it.
 */
@SpringBootTest(properties = {
        "pokemans.condition.worker.enabled=true",
        "pokemans.condition.worker.poll-interval=1h",
        "pokemans.condition.worker.sweep-interval=1h"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PredictStraightAwayTest.QuickProviderConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class PredictStraightAwayTest {

    /** Takes requests at once; the answer comes on a later poll, as with the real providers. */
    static class QuickProvider implements PreGradeProvider {

        final AtomicInteger submits = new AtomicInteger();
        final List<Photos> sent = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "QUICK";
        }

        @Override
        public String submit(Photos photos, String idempotencyKey) {
            submits.incrementAndGet();
            sent.add(photos);
            return "quick-" + idempotencyKey;
        }

        @Override
        public Poll poll(String externalRequestId) {
            return Poll.done(new Reading("quick-1", 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, Map.of()), Map.of("id", externalRequestId));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QuickProviderConfig {
        @Bean
        QuickProvider quickProvider() {
            return new QuickProvider();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private QuickProvider provider;

    @Test
    void aCompletedCaptureIsSentWithinSeconds() throws Exception {
        int before = provider.submits.get();
        TestApi api = new TestApi(mvc, security);
        JwtRequestPostProcessor collector = api.user("auth0|predict-straight-away", List.of());
        String orgId = api.collectorOrg("Predict Straight Away", "auth0|predict-straight-away");
        String cardId = api.card(orgId, "Card", collector);
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select status from prediction_request where card_item_id = ?::uuid", String.class, cardId)).isNotEqualTo("PENDING"));
        assertThat(provider.submits.get() - before).isEqualTo(1);
    }

    @Test
    void providersGetThePhotosWithoutTheirLocation() throws Exception {
        // Security review 2026-10-26, #7: a phone photo's EXIF says where it was taken, and the provider needs only the pixels.
        TestApi api = new TestApi(mvc, security);
        JwtRequestPostProcessor collector = api.user("auth0|predict-no-exif", List.of());
        String orgId = api.collectorOrg("Predict Without Exif", "auth0|predict-no-exif");
        String cardId = api.card(orgId, "Card", collector);
        byte[] front = TestImages.geotaggedJpeg(TestImages.goodRetake(1));
        api.uploadPhoto(orgId, cardId, "FRONT", front, "image/jpeg", collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.geotaggedJpeg(TestImages.goodRetake(2)), "image/jpeg", collector)
                .andExpect(jsonPath("$.status").value("PASSED"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(provider.sent).anySatisfy(photos ->
                assertThat(photos.front().bytes()).hasSizeLessThan(front.length)));
        PreGradeProvider.Photos photos = provider.sent.stream()
                .filter(p -> p.front().bytes().length < front.length).findFirst().orElseThrow();
        for (PreGradeProvider.Image image : List.of(photos.front(), photos.back())) {
            String text = new String(image.bytes(), StandardCharsets.ISO_8859_1);
            assertThat(text).doesNotContain(TestImages.GPS_SECRET).doesNotContain("Exif");
            assertThat(ImageIO.read(new ByteArrayInputStream(image.bytes()))).as("an image").isNotNull();
        }
        // The same pixels as uploaded: only the EXIF segment is cut out.
        byte[] sentFront = photos.front().bytes();
        assertThat(Arrays.copyOfRange(front, front.length - (sentFront.length - 2), front.length))
                .isEqualTo(Arrays.copyOfRange(sentFront, 2, sentFront.length));
    }
}

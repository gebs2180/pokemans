package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The clean back end to end: the admin builds a reference back from chosen photos, the backfill makes clean backs
 * against it (or records why not), and the collector's card links the image. The uploads are synthetic plain cards, so
 * their stored surface crops are swapped for a synthetic printed design ({@link CleanBackTest#design}) first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CleanBackFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private StorageProperties storage;

    @Autowired
    private BlobStore blobs;

    @Autowired
    private PhotoRepository photos;

    @Test
    void aSeededReferenceGivesCleanBacksAndAnotherDesignGetsNone() throws Exception {
        TestApi api = new TestApi(mvc, security);
        String subject = "auth0|clean-" + UUID.randomUUID();
        String org = api.collectorOrg("Clean " + UUID.randomUUID(), subject);
        var seller = api.user(subject, List.of());
        Mat design = CleanBackTest.design(42);

        List<String> seedIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Photo seed = uploadBack(api, org, api.card(org, "Seed " + i, seller), seller, TestImages.goodRetake(i));
            withSurface(seed, CleanBackTest.photograph(design, 100 + i, null));
            seedIds.add(seed.getId().toString());
        }
        String scratchedCard = api.card(org, "Gengar", seller);
        Photo scratched = uploadBack(api, org, scratchedCard, seller, TestImages.goodRetake(3));
        withSurface(scratched, CleanBackTest.photograph(design, 200,
                new CleanBackTest.Scratch(new Point(CleanBack.MARGIN_X + 300, CleanBack.MARGIN_Y + 700),
                        new Point(CleanBack.MARGIN_X + 900, CleanBack.MARGIN_Y + 760))));
        Photo other = uploadBack(api, org, api.card(org, "Japanese back", seller), seller, TestImages.goodRetake(4));
        withSurface(other, CleanBackTest.photograph(CleanBackTest.design(7), 300, null));

        String request = "{\"photoIds\": [\"" + String.join("\", \"", seedIds) + "\"]}";
        mvc.perform(post("/api/admin/clean-backs/reference").with(seller).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        String built = mvc.perform(post("/api/admin/clean-backs/reference").with(api.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.built").value(true))
                .andExpect(jsonPath("$.seeded").value(true))
                .andExpect(jsonPath("$.used.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(built, "$.version");
        assertThat(blobs.head(storage.photoBucket(), BackReference.storageKey(version))).isPresent();
        mvc.perform(get("/api/admin/clean-backs/reference").with(api.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version));

        for (int run = 0; run < 50 && photos.findById(other.getId()).orElseThrow().getCleanBackReferenceVersion() == null
                || run < 50 && photos.findById(scratched.getId()).orElseThrow().getCleanBackReferenceVersion() == null; run++) {
            mvc.perform(post("/api/admin/photos/wide-crops").param("limit", "1000").with(api.admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.referenceVersion").value(version));
        }

        Photo clean = photos.findById(scratched.getId()).orElseThrow();
        assertThat(clean.getCleanBackFailure()).isNull();
        assertThat(clean.getSurfaceCleanKey()).isEqualTo(clean.getStorageKey() + ".clean-v" + version + ".jpg");
        assertThat(blobs.head(storage.photoBucket(), clean.getSurfaceCleanKey())).isPresent();
        mvc.perform(get("/api/orgs/" + org + "/cards/" + scratchedCard).with(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos[0].cleanUrl").isNotEmpty());

        Photo refused = photos.findById(other.getId()).orElseThrow();
        assertThat(refused.getCleanBackReferenceVersion()).isEqualTo(version);
        assertThat(refused.getSurfaceCleanKey()).isNull();
        assertThat(refused.getCleanBackFailure()).isNotNull();

        // A back confirmed now is checked at once; a plain synthetic card has no print to match, and confirm still passes.
        Photo later = uploadBack(api, org, api.card(org, "Later", seller), seller, TestImages.goodBack());
        assertThat(later.getStatus()).isEqualTo(Photo.Status.PASSED);
        assertThat(later.getCleanBackReferenceVersion()).isEqualTo(version);
        assertThat(later.getSurfaceCleanKey()).isNull();
        assertThat(later.getCleanBackFailure()).isNotNull();
    }

    @Test
    void tooFewSeedsBuildNothingAndSayHowManyAreNeeded() throws Exception {
        TestApi api = new TestApi(mvc, security);
        String subject = "auth0|clean-few-" + UUID.randomUUID();
        String org = api.collectorOrg("Clean few " + UUID.randomUUID(), subject);
        var seller = api.user(subject, List.of());
        Photo one = uploadBack(api, org, api.card(org, "One", seller), seller, TestImages.goodRetake(0));
        withSurface(one, CleanBackTest.photograph(CleanBackTest.design(42), 400, null));
        Photo front = photos.findById(UUID.fromString(JsonPath.read(api.uploadPhoto(org, api.card(org, "Two", seller), "FRONT",
                TestImages.goodFront(), "image/png", seller).andReturn().getResponse().getContentAsString(), "$.id"))).orElseThrow();

        mvc.perform(post("/api/admin/clean-backs/reference").with(api.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoIds\": [\"" + one.getId() + "\", \"" + front.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.built").value(false))
                .andExpect(jsonPath("$.needed").value(CleanBack.MIN_SEEDED_BACKS))
                .andExpect(jsonPath("$.rejected['" + front.getId() + "']").value("NOT_A_BACK"))
                .andExpect(jsonPath("$.message").value(containsString("1 of 3 needed")));
    }

    private Photo uploadBack(TestApi api, String org, String card, JwtRequestPostProcessor who, byte[] png) throws Exception {
        String body = api.uploadPhoto(org, card, "BACK", png, "image/png", who)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Photo photo = photos.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow();
        assertThat(photo.getSurfaceKey()).isNotNull();
        return photo;
    }

    private void withSurface(Photo photo, byte[] jpeg) {
        blobs.replace(storage.photoBucket(), photo.getSurfaceKey(), jpeg, "image/jpeg");
    }
}

package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The surface view shows the card with a little background past each edge (the wide crop): kept at upload, linked on
 * the collector's card, and made by the admin's one-off backfill for photos checked before.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PhotoWideCropTest {

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

    @Autowired
    private JdbcClient jdbc;

    @Test
    void anUploadKeepsAWideCropAndTheCollectorsCardLinksIt() throws Exception {
        TestApi api = new TestApi(mvc, security);
        String subject = "auth0|wide-" + UUID.randomUUID();
        String org = api.collectorOrg("Wide " + UUID.randomUUID(), subject);
        var seller = api.user(subject, List.of());
        String card = api.card(org, "Charizard", seller);

        Photo photo = upload(api, org, card, seller);

        assertThat(photo.getWideCropKey()).isEqualTo(photo.getStorageKey() + ".wide.jpg");
        assertThat(blobs.head(storage.photoBucket(), photo.getWideCropKey())).isPresent();
        mvc.perform(get("/api/orgs/" + org + "/cards/" + card).with(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos[0].wideUrl").isNotEmpty());
    }

    @Test
    void theBackfillMakesWideCropsForOlderPhotosAndOnlyTheAdminRunsIt() throws Exception {
        TestApi api = new TestApi(mvc, security);
        String subject = "auth0|wide-backfill-" + UUID.randomUUID();
        String org = api.collectorOrg("Wide backfill " + UUID.randomUUID(), subject);
        var seller = api.user(subject, List.of());
        String card = api.card(org, "Blastoise", seller);
        Photo photo = upload(api, org, card, seller);
        // As it was before this deploy: no wide crop.
        jdbc.sql("update photo set wide_crop_key = null where id = :id").param("id", photo.getId()).update();
        mvc.perform(get("/api/orgs/" + org + "/cards/" + card).with(seller))
                .andExpect(jsonPath("$.photos[0].wideUrl").doesNotExist());

        mvc.perform(post("/api/admin/photos/wide-crops").with(seller)).andExpect(status().isForbidden());

        long remaining = Long.MAX_VALUE;
        for (int run = 0; run < 20 && photos.findById(photo.getId()).orElseThrow().getWideCropKey() == null; run++) {
            String body = mvc.perform(post("/api/admin/photos/wide-crops").param("limit", "1000").with(api.admin()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            remaining = ((Number) JsonPath.read(body, "$.remaining")).longValue();
        }
        Photo after = photos.findById(photo.getId()).orElseThrow();
        assertThat(after.getWideCropKey()).isEqualTo(photo.getStorageKey() + ".wide.jpg");
        assertThat(blobs.read(storage.photoBucket(), after.getWideCropKey())).isNotEmpty();
        mvc.perform(get("/api/orgs/" + org + "/cards/" + card).with(seller))
                .andExpect(jsonPath("$.photos[0].wideUrl").isNotEmpty());
        assertThat(remaining).isNotNegative();
    }

    private Photo upload(TestApi api, String org, String card,
                         org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor who)
            throws Exception {
        String body = api.uploadPhoto(org, card, "FRONT", TestImages.goodFront(), "image/png", who)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return photos.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow();
    }
}

package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.GpsDirectory;
import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * The originals we keep lose their location and device metadata, keeping pixels, orientation and capture time (privacy
 * review 2026-11-12): at upload, and for older ones by the admin's one-off backfill.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PhotoLocationTest {

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
    void anUploadIsStoredWithoutItsLocationAndKeepsItsCaptureTime() throws Exception {
        TestApi api = new TestApi(mvc, security);
        String subject = "auth0|location-" + UUID.randomUUID();
        String org = api.collectorOrg("Location " + UUID.randomUUID(), subject);
        var seller = api.user(subject, List.of());
        byte[] upload = phonePhoto();

        Photo photo = upload(api, org, api.card(org, "Charizard", seller), upload, seller);

        byte[] stored = blobs.read(storage.photoBucket(), photo.getStorageKey());
        assertStripped(stored, upload);
        assertThat(photo.getLocationCheckedAt()).isNotNull();
        // The upload's hash stays (duplicate checks, issued reports); the stored object's is recorded beside it.
        assertThat(photo.getSha256()).isEqualTo(PhotoService.sha256(upload));
        assertThat(photo.getStoredSha256()).isEqualTo(PhotoService.sha256(stored));
        assertThat(photo.getCapturedAt()).isEqualTo(Instant.parse("2026-10-30T14:05:09Z"));
    }

    @Test
    void theBackfillStripsOriginalsUploadedBeforeAndOnlyTheAdminRunsIt() throws Exception {
        TestApi api = new TestApi(mvc, security);
        String subject = "auth0|backfill-" + UUID.randomUUID();
        String org = api.collectorOrg("Backfill " + UUID.randomUUID(), subject);
        var seller = api.user(subject, List.of());
        byte[] upload = phonePhoto();
        Photo photo = upload(api, org, api.card(org, "Blastoise", seller), upload, seller);
        // As it was before this deploy: the original as uploaded, never checked.
        blobs.replace(storage.photoBucket(), photo.getStorageKey(), upload, "image/jpeg");
        jdbc.sql("update photo set location_checked_at = null, stored_sha256 = null, stored_size_bytes = null where id = :id")
                .param("id", photo.getId()).update();

        mvc.perform(post("/api/admin/photos/remove-location").with(seller)).andExpect(status().isForbidden());

        long remaining = Long.MAX_VALUE;
        for (int run = 0; run < 20 && photos.findById(photo.getId()).orElseThrow().getLocationCheckedAt() == null; run++) {
            String body = mvc.perform(post("/api/admin/photos/remove-location").param("limit", "1000").with(api.admin()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat((Integer) JsonPath.read(body, "$.failed")).isZero();
            remaining = ((Number) JsonPath.read(body, "$.remaining")).longValue();
        }
        assertThat(remaining).isZero();

        Photo after = photos.findById(photo.getId()).orElseThrow();
        byte[] stored = blobs.read(storage.photoBucket(), after.getStorageKey());
        assertStripped(stored, upload);
        assertThat(after.getStoredSha256()).isEqualTo(PhotoService.sha256(stored));
        assertThat(after.getSha256()).isEqualTo(PhotoService.sha256(upload));

        // A second run has nothing left to do for it.
        String again = mvc.perform(post("/api/admin/photos/remove-location").with(api.admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(again, "$.remaining")).longValue()).isZero();
        assertThat(blobs.read(storage.photoBucket(), after.getStorageKey())).isEqualTo(stored);
    }

    private Photo upload(TestApi api, String org, String card, byte[] bytes,
                         org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor who)
            throws Exception {
        String body = api.uploadPhoto(org, card, "FRONT", bytes, "image/jpeg", who)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return photos.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow();
    }

    private static void assertStripped(byte[] stored, byte[] upload) throws Exception {
        assertThat(ImageMetadataReader.readMetadata(new ByteArrayInputStream(stored)).getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("PhoneMaker").doesNotContain("2026:11:01");
        assertThat(ExifReader.capturedAt(stored)).isEqualTo(ExifReader.capturedAt(upload)).isNotNull();
        assertThat(ExactCopy.orientation(stored)).isEqualTo(6);
        assertThat(stored.length).isLessThan(upload.length);
    }

    /** A good front as a phone JPEG: maker, orientation, capture time and a GPS IFD. */
    private static byte[] phonePhoto() throws Exception {
        byte[] plain = ExactCopy.of(TestImages.geotaggedJpeg(TestImages.goodFront()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);
        out.write(ExactCopyTest.cameraExif());
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }
}

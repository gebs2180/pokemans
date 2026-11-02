package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.storage.StorageProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What a stranger can push into the photo bucket and pull out of it (audit of 2026-11-02): sizes and types
 * are limited, what the file really is beats what it claims, and a photo's address is no way in without
 * a signature made for that exact object.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PhotoLimitsTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private StorageProperties storage;

    private final HttpClient http = HttpClient.newHttpClient();
    private TestApi api;
    private String orgId;
    private String cardId;
    private JwtRequestPostProcessor seller;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        String subject = "auth0|photos-" + UUID.randomUUID();
        orgId = api.collectorOrg("Photos " + UUID.randomUUID(), subject);
        seller = api.user(subject, List.of());
        cardId = api.card(orgId, "Card", seller);
    }

    private org.springframework.test.web.servlet.ResultActions slot(String side, String type, long size) throws Exception {
        return api.postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/photos",
                "{\"side\":\"" + side + "\",\"contentType\":\"" + type + "\",\"sizeBytes\":" + size + "}", seller);
    }

    @Test
    void anUploadSlotIsLimitedBySizeAndType() throws Exception {
        slot("FRONT", "image/png", storage.maxUploadBytes()).andExpect(status().isCreated());
        slot("FRONT", "image/png", storage.maxUploadBytes() + 1).andExpect(status().isContentTooLarge());
        slot("FRONT", "image/png", 0).andExpect(status().isBadRequest());
        slot("FRONT", "image/png", -1).andExpect(status().isBadRequest());
        for (String type : List.of("image/svg+xml", "text/html", "image/gif", "application/octet-stream", "IMAGE/PNG; x=1")) {
            slot("FRONT", type, 1000).andExpect(status().isUnsupportedMediaType());
        }
    }

    @Test
    void theUploadHasToBeTheSizeAndTypeTheSlotWasSignedFor() throws Exception {
        byte[] photo = TestImages.goodFront();
        String body = slot("FRONT", "image/png", photo.length).andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.url");
        Map<String, Object> headers = JsonPath.read(body, "$.headers");

        byte[] bigger = java.util.Arrays.copyOf(photo, photo.length + 1024);
        assertThat(api.put(url, headers, bigger)).as("longer than declared").isIn(400, 403);

        Map<String, Object> asHtml = new java.util.HashMap<>(headers);
        asHtml.put("Content-Type", "text/html");
        assertThat(api.put(url, asHtml, photo)).as("another content type").isEqualTo(403);

        assertThat(api.put(url, headers, photo)).as("what was signed").isEqualTo(200);
    }

    @Test
    void aFileThatIsntAPhotoNeverPassesWhateverItClaims() throws Exception {
        for (byte[] notAPhoto : List.of("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8),
                "GIF89a not really".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00})) {
            api.uploadPhoto(orgId, cardId, "FRONT", notAPhoto, seller)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RETAKE"));
        }
    }

    @Test
    void anUnconfirmedSlotOrAnotherOrgsPhotoCantBeConfirmed() throws Exception {
        String pending = JsonPath.read(slot("FRONT", "image/png", 1000).andReturn().getResponse().getContentAsString(), "$.photoId");
        mvc.perform(post("/api/orgs/" + orgId + "/photos/" + pending + "/confirm").with(seller)).andExpect(status().isConflict());

        String other = "auth0|photos-other-" + UUID.randomUUID();
        String otherOrg = api.collectorOrg("Other " + UUID.randomUUID(), other);
        mvc.perform(post("/api/orgs/" + otherOrg + "/photos/" + pending + "/confirm").with(api.user(other, List.of())))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPhotosAddressAloneGetsNothing() throws Exception {
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), seller).andExpect(jsonPath("$.status").value("PASSED"));
        String link = TestApi.read(api.get("/api/orgs/" + orgId + "/cards/" + cardId, seller), "$.photos[0].url");
        assertThat(get(link)).as("the signed link").isEqualTo(200);

        String[] parts = link.split("\\?", 2);
        assertThat(parts[0]).as("a card crop").endsWith(".card.jpg");
        assertThat(get(parts[0])).as("no signature").isIn(400, 403);
        // The crop's signature doesn't open the upload beside it, the one with the EXIF.
        assertThat(get(parts[0].replace(".card.jpg", "") + "?" + parts[1])).as("the crop's signature on the original").isEqualTo(403);
    }

    private int get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}

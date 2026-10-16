package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.RoleClaimConverter;
import com.pokemans.app.identity.SecurityProperties;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M1 "done when": a partner can upload a batch and see pass/retake per photo. Runs the whole path
 * against real Postgres and MinIO: org, invite, accept, batch, card, presigned upload, confirm.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class IntakeFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    private final HttpClient http = HttpClient.newHttpClient();

    private JwtRequestPostProcessor user(String subject, List<String> roles) {
        return jwt()
                .jwt(b -> b.subject(subject).claim(security.rolesClaim(), roles).claim(security.mfaClaim(), true))
                .authorities(new RoleClaimConverter(security)::convert);
    }

    private JwtRequestPostProcessor admin() {
        return user("auth0|admin", List.of("ADMIN"));
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path).toString();
    }

    private ResultActions postJson(String url, String json, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Requests an upload slot, PUTs the bytes to storage, and confirms. Returns the confirm response. */
    private ResultActions uploadPhoto(String orgId, String cardId, String side, byte[] png, JwtRequestPostProcessor who) throws Exception {
        ResultActions slot = postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/photos",
                "{\"side\":\"" + side + "\",\"contentType\":\"image/png\",\"sizeBytes\":" + png.length + "}", who)
                .andExpect(status().isCreated());
        String body = slot.andReturn().getResponse().getContentAsString();
        String photoId = JsonPath.read(body, "$.photoId");
        assertThat(put(JsonPath.read(body, "$.url"), JsonPath.read(body, "$.headers"), png)).isEqualTo(200);
        return mvc.perform(post("/api/orgs/" + orgId + "/photos/" + photoId + "/confirm").with(who));
    }

    private int put(String url, Map<String, Object> headers, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, value) -> request.header(name, value.toString()));
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void partnerUploadsABatchAndSeesPassOrRetakePerPhoto() throws Exception {
        // Admin creates a partner org and an invite.
        String orgId = read(postJson("/api/admin/orgs", "{\"name\":\"Card Shop\",\"type\":\"PARTNER\"}", admin())
                .andExpect(status().isCreated()), "$.id");
        String token = read(postJson("/api/admin/orgs/" + orgId + "/invites", "{}", admin())
                .andExpect(status().isCreated()), "$.token");

        // The partner previews and accepts it; the invite is then used up.
        JwtRequestPostProcessor partner = user("auth0|partner", List.of());
        mvc.perform(get("/api/invites/" + token).with(partner))
                .andExpect(jsonPath("$.orgName").value("Card Shop"))
                .andExpect(jsonPath("$.usable").value(true));
        mvc.perform(post("/api/invites/" + token + "/accept").with(partner)).andExpect(status().isOk());
        mvc.perform(post("/api/invites/" + token + "/accept").with(partner)).andExpect(status().isOk());
        mvc.perform(post("/api/invites/" + token + "/accept").with(user("auth0|someone-else", List.of())))
                .andExpect(status().isGone());
        mvc.perform(get("/api/me").with(partner))
                .andExpect(jsonPath("$.roles[0]").value("PARTNER"))
                .andExpect(jsonPath("$.memberships[0].orgName").value("Card Shop"));

        // Outsiders and the wrong org type see nothing.
        mvc.perform(get("/api/orgs/" + orgId + "/batches").with(user("auth0|outsider", List.of()))).andExpect(status().isNotFound());
        mvc.perform(get("/api/orgs/" + orgId + "/collections").with(partner)).andExpect(status().isNotFound());

        // Batch and card.
        String batchId = read(postJson("/api/orgs/" + orgId + "/batches", "{\"name\":\"PSA October\"}", partner)
                .andExpect(status().isCreated()), "$.id");
        String cardId = read(postJson("/api/orgs/" + orgId + "/batches/" + batchId + "/cards",
                "{\"label\":\"Charizard Base Set Unlimited\",\"lot\":false}", partner)
                .andExpect(status().isCreated()), "$.id");

        // Unsupported type and confirm-before-upload are refused.
        postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/photos",
                "{\"side\":\"FRONT\",\"contentType\":\"image/heic\",\"sizeBytes\":1000}", partner)
                .andExpect(status().isUnsupportedMediaType());
        String pendingId = read(postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/photos",
                "{\"side\":\"FRONT\",\"contentType\":\"image/png\",\"sizeBytes\":1000}", partner), "$.photoId");
        mvc.perform(post("/api/orgs/" + orgId + "/photos/" + pendingId + "/confirm").with(partner)).andExpect(status().isConflict());

        // A sharp front passes.
        uploadPhoto(orgId, cardId, "FRONT", QualityCheckerTest.png(QualityCheckerTest.sharp(1600, 2200)), partner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.width").value(1600));

        // A blurry back needs a retake, with a reason the partner can act on.
        BufferedImage smooth = new BufferedImage(1600, 2200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < smooth.getHeight(); y++) {
            for (int x = 0; x < smooth.getWidth(); x++) {
                int v = 60 + x * 120 / smooth.getWidth();
                smooth.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        uploadPhoto(orgId, cardId, "BACK", QualityCheckerTest.png(smooth), partner)
                .andExpect(jsonPath("$.status").value("RETAKE"))
                .andExpect(jsonPath("$.retakeReasons[0].code").value("BLURRY"))
                .andExpect(jsonPath("$.retakeReasons[0].message").isNotEmpty());
        mvc.perform(get("/api/orgs/" + orgId + "/cards/" + cardId).with(partner))
                .andExpect(jsonPath("$.captureComplete").value(false))
                .andExpect(jsonPath("$.missingSides[0]").value("BACK"));

        // The retake passes and the card is complete.
        uploadPhoto(orgId, cardId, "BACK", QualityCheckerTest.png(QualityCheckerTest.sharp(1700, 2300)), partner)
                .andExpect(jsonPath("$.status").value("PASSED"));
        mvc.perform(get("/api/orgs/" + orgId + "/batches/" + batchId).with(partner))
                .andExpect(jsonPath("$.cards[0].captureComplete").value(true))
                .andExpect(jsonPath("$.cards[0].photos.length()").value(4));
    }

    @Test
    void storedPhotosCannotBeOverwritten() throws Exception {
        String orgId = read(postJson("/api/admin/orgs", "{\"name\":\"Collector\",\"type\":\"COLLECTOR\"}", admin()), "$.id");
        String collectionId = read(postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"Binder\"}", admin()), "$.id");
        String cardId = read(postJson("/api/orgs/" + orgId + "/collections/" + collectionId + "/cards",
                "{\"label\":\"Bulk commons\",\"lot\":true,\"quantity\":200}", admin()), "$.id");

        byte[] png = QualityCheckerTest.png(QualityCheckerTest.sharp(1600, 2200));
        String body = postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/photos",
                "{\"side\":\"FRONT\",\"contentType\":\"image/png\",\"sizeBytes\":" + png.length + "}", admin())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.url");
        Map<String, Object> headers = JsonPath.read(body, "$.headers");

        assertThat(put(url, headers, png)).isEqualTo(200);
        assertThat(put(url, headers, png)).isEqualTo(412);

        // A lot needs only a front.
        mvc.perform(post("/api/orgs/" + orgId + "/photos/" + JsonPath.read(body, "$.photoId") + "/confirm").with(admin()))
                .andExpect(jsonPath("$.status").value("PASSED"));
        mvc.perform(get("/api/orgs/" + orgId + "/cards/" + cardId).with(admin()))
                .andExpect(jsonPath("$.captureComplete").value(true));
    }
}

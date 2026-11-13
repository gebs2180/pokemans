package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Labelling a card's printed frame by hand, and the file the centering scoreboard runs on. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CenteringLabelFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
    }

    private record Card(String orgId, String cardId, String front, String back, JwtRequestPostProcessor collector) {

        String base() {
            return "/api/admin/orgs/" + orgId + "/cards/" + cardId;
        }
    }

    private Card card(String name) throws Exception {
        String subject = "auth0|" + name;
        JwtRequestPostProcessor collector = api.user(subject, List.of());
        String orgId = api.collectorOrg(name, subject);
        String cardId = api.card(orgId, name, collector);
        String front = TestApi.read(api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector)
                .andExpect(jsonPath("$.status").value("PASSED")), "$.id");
        String back = TestApi.read(api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector)
                .andExpect(jsonPath("$.status").value("PASSED")), "$.id");
        return new Card(orgId, cardId, front, back, collector);
    }

    @Test
    void aLabelIsStoredPerCardAndFaceWithWhoAndWhen() throws Exception {
        Card card = card("label-store");

        // The straightened card the lines are drawn on.
        api.get(card.base() + "/photos/" + card.front() + "/straightened", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pxPerMm").value(20))
                .andExpect(jsonPath("$.width").value(1260))
                .andExpect(jsonPath("$.height").value(1760))
                .andExpect(jsonPath("$.image").value(startsWith("data:image/jpeg;base64,")))
                .andExpect(jsonPath("$.contrast").value(startsWith("data:image/jpeg;base64,")))
                .andExpect(jsonPath("$.refused").value(nullValue()));

        api.postJson(card.base() + "/centering-labels", """
                {"face":"FRONT","photoId":"%s","left":2.5,"right":2.45,"top":3.1,"bottom":null,"hardCard":true}
                """.formatted(card.front()), api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label.bottom").value(nullValue()))
                .andExpect(jsonPath("$.label.labelledBy").isNotEmpty())
                .andExpect(jsonPath("$.label.createdAt").isNotEmpty())
                // Saving is the reveal: stored with the label, and only now the measurer's reading of that photo.
                .andExpect(jsonPath("$.label.revealedAt").isNotEmpty())
                .andExpect(jsonPath("$.system.left").isNumber())
                .andExpect(jsonPath("$.system.bottom").isNumber())
                .andExpect(jsonPath("$.system.refused").value(nullValue()));
        assertThat(jdbc.queryForObject("select count(*) from centering_label where card_item_id = ?::uuid and revealed_at = created_at",
                Integer.class, card.cardId())).isEqualTo(1);

        api.get(card.base() + "/centering-labels", api.admin())
                .andExpect(jsonPath("$.front.left").value(2.5))
                .andExpect(jsonPath("$.front.hardCard").value(true))
                .andExpect(jsonPath("$.back").value(nullValue()))
                .andExpect(jsonPath("$.photos", hasSize(2)));

        // Labelling again adds a row and the newest counts.
        api.postJson(card.base() + "/centering-labels", """
                {"face":"FRONT","photoId":"%s","left":2.6,"right":2.4,"top":3.1,"bottom":2.9,"hardCard":true}
                """.formatted(card.front()), api.admin()).andExpect(status().isCreated());
        api.get(card.base() + "/centering-labels", api.admin()).andExpect(jsonPath("$.front.left").value(2.6));
        assertThat(jdbc.queryForObject("select count(*) from centering_label where card_item_id = ?::uuid", Integer.class, card.cardId()))
                .isEqualTo(2);
        // Append-only, enforced (review finding 40).
        assertThatThrownBy(() -> jdbc.update("update centering_label set left_mm = 1 where card_item_id = ?::uuid", card.cardId()))
                .hasMessageContaining("append-only");
    }

    @Test
    void graderStandardsAreForAdminsAndPsaMatchesTheCap() throws Exception {
        api.get("/api/admin/centering-standards", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].grader").value("PSA"))
                .andExpect(jsonPath("$[0].allowances[0].grade").value("Gem Mint 10"))
                .andExpect(jsonPath("$[0].allowances[0].maxFrontShare").value(55.0))
                .andExpect(jsonPath("$[*].grader", org.hamcrest.Matchers.contains("PSA", "BGS", "CGC", "TAG")));
        api.get("/api/admin/centering-standards", api.user("auth0|standards-collector", List.of())).andExpect(status().isForbidden());
    }

    @Test
    void aLabelMustBeOnAPhotoOfThatFaceOfThatCard() throws Exception {
        Card card = card("label-refused");
        Card other = card("label-other");

        api.postJson(card.base() + "/centering-labels", """
                {"face":"FRONT","photoId":"%s","left":2.5,"right":2.5,"top":2.5,"bottom":2.5,"hardCard":false}
                """.formatted(card.back()), api.admin()).andExpect(status().isBadRequest());
        api.postJson(card.base() + "/centering-labels", """
                {"face":"FRONT","photoId":"%s","left":2.5,"right":2.5,"top":2.5,"bottom":2.5,"hardCard":false}
                """.formatted(other.front()), api.admin()).andExpect(status().isNotFound());
        api.get(card.base() + "/photos/" + other.front() + "/straightened", api.admin()).andExpect(status().isNotFound());
        api.postJson(card.base() + "/centering-labels", """
                {"face":"FRONT","photoId":"%s","left":16,"right":2.5,"top":2.5,"bottom":2.5,"hardCard":false}
                """.formatted(card.front()), api.admin()).andExpect(status().isBadRequest());
    }

    @Test
    void labellingIsForAdmins() throws Exception {
        Card card = card("label-admin");
        api.get("/api/admin/centering-labels", card.collector()).andExpect(status().isForbidden());
        api.get("/api/admin/centering-labels/export", card.collector()).andExpect(status().isForbidden());
        api.postJson(card.base() + "/centering-labels", """
                {"face":"FRONT","photoId":"%s","left":2.5,"right":2.5,"top":2.5,"bottom":2.5,"hardCard":false}
                """.formatted(card.front()), card.collector()).andExpect(status().isForbidden());
    }

    @Test
    void theQueueAndExportCarryEveryPassingPhotoOfAFace() throws Exception {
        Card labelled = card("label-queue-done");
        Card waiting = card("label-queue-waiting");
        String retake = TestApi.read(api.uploadPhoto(labelled.orgId(), labelled.cardId(), "FRONT", TestImages.goodRetake(7), labelled.collector())
                .andExpect(jsonPath("$.status").value("PASSED")), "$.id");
        api.uploadPhoto(labelled.orgId(), labelled.cardId(), "DETAIL", TestImages.goodRetake(8), labelled.collector())
                .andExpect(jsonPath("$.status").value("PASSED"));
        api.postJson(labelled.base() + "/centering-labels", """
                {"face":"FRONT","photoId":"%s","left":2.5,"right":2.5,"top":null,"bottom":3,"hardCard":false}
                """.formatted(labelled.front()), api.admin()).andExpect(status().isCreated());

        String queue = "$[?(@.cardId == '%s')]";
        api.get("/api/admin/centering-labels", api.admin())
                .andExpect(jsonPath(queue.formatted(labelled.cardId()) + ".front.photos").value(2))
                .andExpect(jsonPath(queue.formatted(labelled.cardId()) + ".front.label.left").value(2.5))
                .andExpect(jsonPath(queue.formatted(waiting.cardId()) + ".front.label").value(org.hamcrest.Matchers.contains(nullValue())));

        // Every photo of the labelled face is scored against the one label, the close-up isn't.
        String face = "$.cards[?(@.cardId == '%s')].faces[?(@.face == 'FRONT')]".formatted(labelled.cardId());
        String file = api.get("/api/admin/centering-labels/export", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurerVersion").value(CenteringPreGradeProvider.VERSION))
                .andExpect(jsonPath(face + ".label.bottom").value(3.0))
                .andExpect(jsonPath(face + ".label.revealedAt").isNotEmpty())
                .andExpect(jsonPath(face + ".photos[*].id").value(org.hamcrest.Matchers.containsInAnyOrder(labelled.front(), retake)))
                .andReturn().getResponse().getContentAsString();
        List<String> links = com.jayway.jsonpath.JsonPath.read(file, face + ".photos[*].url");
        assertThat(api.download(links.getFirst())).isNotEmpty();
    }
}

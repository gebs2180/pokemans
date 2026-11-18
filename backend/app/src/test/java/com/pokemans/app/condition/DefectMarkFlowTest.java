package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.util.List;
import java.util.UUID;
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

/**
 * Defects a reviewer marks on a card's surface view: only the admin writes them, the collector reads them on their own
 * cards, another org is refused, deleting keeps the row, and they change nothing about the condition.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class DefectMarkFlowTest {

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

    private record Card(String orgId, String cardId, String photoId, JwtRequestPostProcessor collector) {

        String admin() {
            return "/api/admin/orgs/" + orgId + "/cards/" + cardId + "/defect-marks";
        }

        String collectorPath() {
            return "/api/orgs/" + orgId + "/cards/" + cardId + "/defect-marks";
        }
    }

    private Card card(String name) throws Exception {
        String subject = "auth0|" + name + "-" + UUID.randomUUID();
        JwtRequestPostProcessor collector = api.user(subject, List.of());
        String orgId = api.collectorOrg(name + " " + UUID.randomUUID(), subject);
        String cardId = api.card(orgId, "Charizard", collector);
        String body = api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Card(orgId, cardId, JsonPath.read(body, "$.id"), collector);
    }

    private static String mark(String photoId, String type) {
        return "{\"photoId\":\"" + photoId + "\",\"x\":0.25,\"y\":0.5,\"type\":\"" + type + "\",\"severity\":\"MODERATE\",\"note\":\" a line \"}";
    }

    @Test
    void theAdminMarksADefectAndTheCollectorReadsItOnTheirOwnCard() throws Exception {
        Card card = card("marks");

        String created = api.postJson(card.admin(), mark(card.photoId(), "SCRATCH"), api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.side").value("FRONT"))
                .andExpect(jsonPath("$.note").value("a line"))
                .andExpect(jsonPath("$.createdBy").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        api.get(card.collectorPath(), card.collector())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((String) JsonPath.read(created, "$.id")))
                .andExpect(jsonPath("$[0].type").value("SCRATCH"))
                .andExpect(jsonPath("$[0].severity").value("MODERATE"))
                .andExpect(jsonPath("$[0].x").value(0.25))
                .andExpect(jsonPath("$[0].createdBy").doesNotExist());
        api.get(card.admin(), api.admin()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void aCollectorCannotWriteOrDeleteMarks() throws Exception {
        Card card = card("nowrite");
        String created = api.postJson(card.admin(), mark(card.photoId(), "STAIN"), api.admin()).andReturn().getResponse().getContentAsString();
        String markId = JsonPath.read(created, "$.id");

        api.postJson(card.admin(), mark(card.photoId(), "OTHER"), card.collector()).andExpect(status().isForbidden());
        mvc.perform(delete(card.admin() + "/" + markId).with(card.collector())).andExpect(status().isForbidden());
        api.get(card.admin(), card.collector()).andExpect(status().isForbidden());

        api.get(card.collectorPath(), card.collector()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void anotherOrgCannotReadMarks() throws Exception {
        Card card = card("owner");
        api.postJson(card.admin(), mark(card.photoId(), "WHITENING"), api.admin()).andExpect(status().isCreated());
        Card stranger = card("stranger");

        api.get(card.collectorPath(), stranger.collector()).andExpect(status().isNotFound());
        // Their own org id with someone else's card id finds nothing either.
        api.get("/api/orgs/" + stranger.orgId() + "/cards/" + card.cardId() + "/defect-marks", stranger.collector())
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingIsSoftAndTheMarkStopsShowing() throws Exception {
        Card card = card("soft");
        String created = api.postJson(card.admin(), mark(card.photoId(), "DENT_CREASE"), api.admin()).andReturn().getResponse().getContentAsString();
        String markId = JsonPath.read(created, "$.id");

        mvc.perform(delete(card.admin() + "/" + markId).with(api.admin())).andExpect(status().isNoContent());

        api.get(card.collectorPath(), card.collector()).andExpect(jsonPath("$", hasSize(0)));
        api.get(card.admin(), api.admin()).andExpect(jsonPath("$", hasSize(0)));
        assertThat(jdbc.queryForObject("select count(*) from defect_mark where id = ?::uuid and deleted_at is not null and deleted_by is not null",
                Long.class, markId)).isOne();
    }

    @Test
    void aMarkMustBeOnAPhotoOfThisCardAndInsideTheCrop() throws Exception {
        Card card = card("bad");
        Card other = card("other-card");

        api.postJson(card.admin(), mark(other.photoId(), "SCRATCH"), api.admin()).andExpect(status().isNotFound());
        api.postJson(card.admin(), "{\"photoId\":\"" + card.photoId() + "\",\"x\":1.5,\"y\":0.5,\"type\":\"SCRATCH\",\"severity\":\"MINOR\"}",
                api.admin()).andExpect(status().isBadRequest());
        api.postJson(card.admin(), "{\"photoId\":\"" + card.photoId() + "\",\"x\":0.5,\"y\":0.5,\"type\":\"BURN\",\"severity\":\"MINOR\"}",
                api.admin()).andExpect(status().isBadRequest());
    }

    @Test
    void marksDoNotTouchTheConditionOrThePhotos() throws Exception {
        Card card = card("noeffect");
        Long assessments = jdbc.queryForObject("select count(*) from condition_assessment where card_item_id = ?::uuid", Long.class, card.cardId());

        api.postJson(card.admin(), mark(card.photoId(), "PRINT_LINE"), api.admin()).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select count(*) from condition_assessment where card_item_id = ?::uuid", Long.class, card.cardId()))
                .isEqualTo(assessments);
    }
}

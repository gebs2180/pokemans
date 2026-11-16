package com.pokemans.app.catalog;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** With the card reader off, a card added photos first goes straight to the search once front and back pass. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ReaderOffIdentifyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @MockitoBean
    private CatalogProvider provider;

    @Test
    void searchesRightAfterThePhotos() throws Exception {
        when(provider.id()).thenReturn(CatalogCard.Provider.TCGDEX);
        when(provider.card("base1-4", "en")).thenReturn(Optional.of(new CatalogProvider.ProviderCard("base1-4", "en", "base1",
                "Base", "Base", LocalDate.of(1999, 1, 9), "4", "Charizard", "Rare Holo",
                List.of(Edition.FIRST_EDITION, Edition.SHADOWLESS, Edition.UNLIMITED), List.of(Variant.HOLO), List.of(), false,
                "https://img/base1/4", Map.of(), 102)));

        TestApi api = new TestApi(mvc, security);
        JwtRequestPostProcessor collector = api.user("auth0|reader-off", List.of());
        String orgId = api.collectorOrg("Reader Off", "auth0|reader-off");
        String collectionId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"Binder\"}", collector), "$.id");
        String cardId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections/" + collectionId + "/cards",
                "{\"label\":\"  \",\"lot\":false}", collector).andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("New card")), "$.id");
        String identify = "/api/orgs/" + orgId + "/cards/" + cardId + "/identify";

        api.get(identify, collector).andExpect(jsonPath("$.step").value("PHOTOS"));
        api.uploadPhoto(orgId, cardId, "FRONT", TestImages.goodFront(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(orgId, cardId, "BACK", TestImages.goodBack(), collector).andExpect(jsonPath("$.status").value("PASSED"));
        api.get(identify, collector)
                .andExpect(jsonPath("$.step").value("PICK"))
                .andExpect(jsonPath("$.why").value("READER_OFF"))
                .andExpect(jsonPath("$.readingId").isEmpty());

        // The edition is asked because the catalog lists three; the card takes the picked card's name.
        api.postJson("/api/orgs/" + orgId + "/cards/" + cardId + "/proposed-identity",
                        "{\"externalId\":\"base1-4\",\"language\":\"en\",\"edition\":\"SHADOWLESS\"}", collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("SEARCH"))
                .andExpect(jsonPath("$.readingId").isEmpty())
                .andExpect(jsonPath("$.variant").value("HOLO"));
        api.get("/api/orgs/" + orgId + "/cards/" + cardId, collector)
                .andExpect(jsonPath("$.label").value("Charizard · Base 4/102 · Shadowless · Holo"))
                .andExpect(jsonPath("$.named").value(true));
        api.get(identify, collector).andExpect(jsonPath("$.step").value("PROPOSED"));
        api.get(identify + "?again=true", collector).andExpect(jsonPath("$.step").value("PICK"));
    }
}

package com.pokemans.app.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Reviewer identifies cards from the catalog or by hand; the org's members can read the result. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CatalogFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @MockitoBean
    private CatalogProvider provider;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
        when(provider.id()).thenReturn(CatalogCard.Provider.TCGDEX);
        when(provider.search(eq("charizard"), any(), eq("en")))
                .thenReturn(List.of(new CatalogProvider.Hit("base1-4", "4", "Charizard", "base1", "https://img/base1/4")));
        when(provider.card("base1-4", "en")).thenReturn(Optional.of(new CatalogProvider.ProviderCard("base1-4", "en", "base1",
                "Base Set", "Base", LocalDate.of(1999, 1, 9), "4", "Charizard", "Rare",
                List.of(Edition.UNLIMITED, Edition.FIRST_EDITION, Edition.SHADOWLESS), List.of(Variant.HOLO), List.of(), false,
                "https://img/base1/4", Map.of("id", "base1-4"))));
    }

    @Test
    void reviewerIdentifiesACardAndMembersSeeIt() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|catalog-collector", List.of());
        String orgId = api.collectorOrg("Catalog Collector", "auth0|catalog-collector");
        String cardId = api.card(orgId, "Zard from the binder", collector);

        // Search is admin-only.
        api.get("/api/admin/catalog/search?name=charizard", collector).andExpect(status().isForbidden());
        api.get("/api/admin/catalog/search?name=charizard", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("base1-4"));
        api.get("/api/admin/catalog/cards/base1-4", api.admin())
                .andExpect(jsonPath("$.editions.length()").value(3))
                .andExpect(jsonPath("$.payload").doesNotExist());

        // Not yet identified.
        api.get("/api/orgs/" + orgId + "/cards/" + cardId + "/identification", collector)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").isEmpty());

        // Identified from the catalog: a WotC-era holo is an authenticity risk.
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                        "{\"provider\":\"TCGDEX\",\"externalId\":\"base1-4\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\"}",
                        api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current.card.name").value("Charizard"))
                .andExpect(jsonPath("$.current.card.setReleaseDate").value("1999-01-09"))
                .andExpect(jsonPath("$.current.card.authenticityRisk").value("VINTAGE_HOLO"));

        // Corrected to Shadowless: a new identity and a new row; the first stays in the history.
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                        "{\"provider\":\"TCGDEX\",\"externalId\":\"base1-4\",\"language\":\"en\",\"edition\":\"SHADOWLESS\",\"variant\":\"HOLO\",\"note\":\"No drop shadow\"}",
                        api.admin())
                .andExpect(jsonPath("$.current.card.edition").value("SHADOWLESS"))
                .andExpect(jsonPath("$.current.note").value("No drop shadow"))
                .andExpect(jsonPath("$.history.length()").value(2));

        api.get("/api/orgs/" + orgId + "/cards/" + cardId + "/identification", collector)
                .andExpect(jsonPath("$.current.card.edition").value("SHADOWLESS"));
        api.get("/api/orgs/" + orgId + "/cards/" + cardId + "/identification", api.user("auth0|catalog-outsider", List.of()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cardsNotInTheCatalogAreEnteredByHand() throws Exception {
        String orgId = api.collectorOrg("Manual Collector", "auth0|manual-collector");
        String first = api.card(orgId, "Odd promo", api.admin());
        String second = api.card(orgId, "Same promo, second copy", api.admin());
        String manual = "{\"provider\":\"MANUAL\",\"language\":\"ja\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\",\"promo\":true,"
                + "\"setName\":\"Pokemon Center Promo\",\"number\":\"001/SV-P\",\"name\":\"Pikachu\"}";

        String catalogCardId = TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + first + "/identifications", manual, api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current.card.provider").value("MANUAL"))
                .andExpect(jsonPath("$.current.card.authenticityRisk").value("JAPANESE_PROMO")), "$.current.card.id");
        // A second copy of the same card lands on the same catalog identity.
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + second + "/identifications", manual, api.admin())
                .andExpect(jsonPath("$.current.card.id").value(catalogCardId));

        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + first + "/identifications",
                        "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\"}", api.admin())
                .andExpect(status().isBadRequest());
        // The card must belong to the org in the path.
        String otherOrg = api.collectorOrg("Other Collector", "auth0|other-collector");
        api.postJson("/api/admin/orgs/" + otherOrg + "/cards/" + first + "/identifications", manual, api.admin())
                .andExpect(status().isNotFound());
    }
}

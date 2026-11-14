package com.pokemans.app.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
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

/**
 * The collector's card picker: search for any signed-in user with only the picker's fields, the
 * card's prints, and a pick stored as a proposal that changes no identity until the admin confirms.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CardPickerFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private Catalog catalog;

    @MockitoBean
    private CatalogProvider provider;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
        when(provider.id()).thenReturn(CatalogCard.Provider.TCGDEX);
        // Fifteen matches: the picker shows ten.
        List<CatalogProvider.Hit> hits = IntStream.rangeClosed(1, 15)
                .mapToObj(i -> new CatalogProvider.Hit("base1-" + i, String.valueOf(i), "Charizard", "base1",
                        "https://img/base1/" + i, "Base Set", 102))
                .toList();
        when(provider.search(eq("charizard"), any(), eq("en"))).thenReturn(hits);
        when(provider.setReleaseDate("base1", "en")).thenReturn(Optional.of(LocalDate.of(1999, 1, 9)));
        when(provider.card("base1-4", "en")).thenReturn(Optional.of(new CatalogProvider.ProviderCard("base1-4", "en", "base1",
                "Base Set", "Base", LocalDate.of(1999, 1, 9), "4", "Charizard", "Rare Holo",
                List.of(Edition.UNLIMITED, Edition.FIRST_EDITION, Edition.SHADOWLESS), List.of(Variant.HOLO), List.of("1st-edition"), false,
                "https://img/base1/4", Map.of("id", "base1-4"), 102)));
        when(provider.card("swsh12-160", "en")).thenReturn(Optional.of(new CatalogProvider.ProviderCard("swsh12-160", "en", "swsh12",
                "Silver Tempest", "Sword & Shield", LocalDate.of(2022, 11, 11), "160", "Lugia V", "Rare Ultra",
                List.of(Edition.UNLIMITED), List.of(Variant.NORMAL, Variant.REVERSE_HOLO), List.of("prerelease"), false,
                "https://img/swsh12/160", Map.of("id", "swsh12-160"), 195)));
    }

    @Test
    void anySignedInCollectorSearchesWithOnlyThePickersFields() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|picker-searcher", List.of());
        mvc.perform(get("/api/catalog/search?q=charizard")).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/catalog/search").param("q", "charizard 4/102").with(collector))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[3].externalId").value("base1-4"))
                .andExpect(jsonPath("$[3].name").value("Charizard"))
                .andExpect(jsonPath("$[3].setName").value("Base Set"))
                .andExpect(jsonPath("$[3].number").value("4/102"))
                .andExpect(jsonPath("$[3].year").value(1999))
                .andExpect(jsonPath("$[3].language").value("en"))
                .andExpect(jsonPath("$[3].imageUrl").value("https://img/base1/4"))
                // No admin fields.
                .andExpect(jsonPath("$[3].setId").doesNotExist())
                .andExpect(jsonPath("$[3].setTotal").doesNotExist())
                .andExpect(jsonPath("$[3].payload").doesNotExist());
        verify(provider).search("charizard", "4/102", "en");

        // A number alone isn't enough to search on.
        api.get("/api/catalog/search?q=4/102", collector).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        api.get("/api/catalog/cards/base1-4", collector)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editions.length()").value(3))
                .andExpect(jsonPath("$.variants[0]").value("HOLO"))
                // TCGdex marks 1st Edition prints with a stamp too; the edition already asks it.
                .andExpect(jsonPath("$.stamps.length()").value(0))
                .andExpect(jsonPath("$.number").value("4/102"))
                .andExpect(jsonPath("$.payload").doesNotExist())
                .andExpect(jsonPath("$.rarity").doesNotExist())
                .andExpect(jsonPath("$.authenticityRisk").doesNotExist());
        when(provider.card(eq("nope-1"), anyString())).thenReturn(Optional.empty());
        api.get("/api/catalog/cards/nope-1", collector).andExpect(status().isNotFound());
    }

    @Test
    void aPickIsAProposalTheAdminStillConfirms() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|picker-collector", List.of());
        String orgId = api.collectorOrg("Picker Collector", "auth0|picker-collector");
        String cardId = api.card(orgId, "Charizard · Base Set · 4/102", collector);
        String proposal = "/api/orgs/" + orgId + "/cards/" + cardId + "/proposed-identity";

        // Only prints the catalog lists.
        api.postJson(proposal, "{\"externalId\":\"base1-4\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"REVERSE_HOLO\"}", collector)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The catalog doesn't list that variant for this card"));
        // Not sure of the edition: left for review. The only listed finish is taken.
        api.postJson(proposal, "{\"externalId\":\"base1-4\",\"language\":\"en\"}", collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.edition").isEmpty())
                .andExpect(jsonPath("$.variant").value("HOLO"))
                .andExpect(jsonPath("$.stampUnsure").value(false));
        api.postJson(proposal, "{\"externalId\":\"base1-4\",\"language\":\"en\",\"edition\":\"SHADOWLESS\",\"variant\":\"HOLO\"}", collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.edition").value("SHADOWLESS"));

        // The admin sees the latest pick; the card is still not identified, so nothing is priced or flagged on it.
        api.get("/api/orgs/" + orgId + "/cards/" + cardId + "/identification", collector)
                .andExpect(jsonPath("$.current").isEmpty())
                .andExpect(jsonPath("$.history.length()").value(0))
                .andExpect(jsonPath("$.proposal.externalId").value("base1-4"))
                .andExpect(jsonPath("$.proposal.edition").value("SHADOWLESS"))
                .andExpect(jsonPath("$.proposal.setReleaseDate").value("1999-01-09"));
        org.assertj.core.api.Assertions.assertThat(catalog.current(java.util.UUID.fromString(cardId))).isEmpty();

        // The admin confirms as before.
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                        "{\"provider\":\"TCGDEX\",\"externalId\":\"base1-4\",\"language\":\"en\",\"edition\":\"SHADOWLESS\",\"variant\":\"HOLO\"}",
                        api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current.card.authenticityRisk").value("VINTAGE_HOLO"))
                .andExpect(jsonPath("$.proposal.edition").value("SHADOWLESS"));

        // Someone else's card: not found.
        api.postJson(proposal, "{\"externalId\":\"base1-4\",\"language\":\"en\"}", api.user("auth0|picker-outsider", List.of()))
                .andExpect(status().isNotFound());
    }

    @Test
    void stampsAreAskedOnlyWhereTheCatalogListsThem() throws Exception {
        JwtRequestPostProcessor collector = api.user("auth0|picker-stamps", List.of());
        String orgId = api.collectorOrg("Picker Stamps", "auth0|picker-stamps");
        String cardId = api.card(orgId, "Lugia V", collector);
        String proposal = "/api/orgs/" + orgId + "/cards/" + cardId + "/proposed-identity";

        api.postJson(proposal, "{\"externalId\":\"swsh12-160\",\"language\":\"en\",\"variant\":\"NORMAL\",\"stamp\":\"staff\"}", collector)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The catalog doesn't list that stamp for this card"));
        api.postJson(proposal, "{\"externalId\":\"swsh12-160\",\"language\":\"en\",\"stampUnsure\":true}", collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.edition").value("UNLIMITED"))
                .andExpect(jsonPath("$.variant").isEmpty())
                .andExpect(jsonPath("$.stamp").isEmpty())
                .andExpect(jsonPath("$.stampUnsure").value(true));
        api.postJson(proposal, "{\"externalId\":\"swsh12-160\",\"language\":\"en\",\"variant\":\"NORMAL\",\"stamp\":\"prerelease\"}", collector)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stamp").value("prerelease"))
                .andExpect(jsonPath("$.stampUnsure").value(false));
    }

    @Test
    void noPriceTableMeansNoHint() throws Exception {
        api.get("/api/catalog/price-hint?externalId=base1-4&language=en&edition=UNLIMITED&variant=HOLO",
                        api.user("auth0|picker-hint", List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underMinimum").value(false))
                .andExpect(jsonPath("$.minFmv").value(25));
    }
}

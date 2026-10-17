package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The pilot route: a reviewer enters sold comps by hand, refreshes, and sees the card's price tables. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ManualCompFlowTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    private TestApi api;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        orgId = api.collectorOrg("Manual Comps " + UUID.randomUUID(), "auth0|manual-" + UUID.randomUUID());
    }

    private String identified(String name, LocalDate released) throws Exception {
        String cardId = api.card(orgId, name, api.admin());
        String json = "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\","
                + "\"setName\":\"Manual Set " + released.getYear() + "\",\"setReleaseDate\":\"" + released + "\","
                + "\"number\":\"" + UUID.randomUUID().toString().substring(0, 6) + "\",\"name\":\"" + name + "\"}";
        return TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                json, api.admin()).andExpect(status().isCreated()), "$.current.card.id");
    }

    private static String graded(String company, String grade, String price, int daysAgo, String url) {
        return "{\"gradingCompany\":\"" + company + "\",\"grade\":" + grade + ",\"price\":" + price + ",\"soldOn\":\""
                + TODAY.minusDays(daysAgo) + "\",\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\""
                + (url == null ? "" : ",\"url\":\"" + url + "\"") + "}";
    }

    @Test
    void enteredCompsArePricedPerGrader() throws Exception {
        String card = identified("Manual Target", LocalDate.of(2005, 8, 1));
        String comps = "/api/admin/catalog-cards/" + card + "/comps";
        String listing = "https://www.ebay.com/itm/" + UUID.randomUUID();

        api.postJson(comps, graded("PSA", "10", "500", 3, listing), api.admin()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.licence").value("manual entry"))
                .andExpect(jsonPath("$.details.marketplace").value("eBay"));
        api.postJson(comps, graded("PSA", "10", "520", 9, null), api.admin()).andExpect(status().isCreated());
        api.postJson(comps, graded("PSA", "1", "20", 5, null), api.admin()).andExpect(status().isCreated());
        api.postJson(comps, graded("CGC", "9.5", "300", 4, null), api.admin()).andExpect(status().isCreated());
        api.postJson(comps, "{\"rawCondition\":\"DAMAGED\",\"price\":5,\"soldOn\":\"" + TODAY.minusDays(2)
                + "\",\"listingType\":\"FIXED_PRICE\",\"marketplace\":\"TCGplayer\"}", api.admin()).andExpect(status().isCreated());

        // Refused: the same listing twice, a future sale, a sale both graded and raw, a grade with no grader.
        api.postJson(comps, graded("PSA", "10", "500", 3, listing), api.admin()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("already recorded")));
        api.postJson(comps, graded("PSA", "10", "500", -2, null), api.admin()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("in the future")));
        api.postJson(comps, "{\"gradingCompany\":\"PSA\",\"grade\":9,\"rawCondition\":\"DAMAGED\",\"price\":5,\"soldOn\":\""
                + TODAY + "\",\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\"}", api.admin()).andExpect(status().isBadRequest());
        api.postJson(comps, "{\"grade\":9,\"price\":5,\"soldOn\":\"" + TODAY + "\",\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\"}",
                api.admin()).andExpect(status().isBadRequest());
        // An asking price has no sold date, so it can't be entered.
        api.postJson(comps, "{\"gradingCompany\":\"PSA\",\"grade\":9,\"price\":5,\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\"}",
                api.admin()).andExpect(status().isBadRequest());

        // Before a refresh: comps listed, no tables yet.
        api.get("/api/admin/catalog-cards/" + card + "/prices", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.comps.length()").value(5))
                .andExpect(jsonPath("$.tables.length()").value(0));

        // PSA: 1 and 10 observed, 2-9 interpolated. CGC: only a 9.5 (folded to 9), so 1-8 need an era ratio: skipped.
        api.postJson("/api/admin/catalog-cards/" + card + "/prices/refresh", "", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.length()").value(1))
                .andExpect(jsonPath("$.tables[0].gradingCompany").value("PSA"))
                .andExpect(jsonPath("$.tables[0].grades.length()").value(11))
                .andExpect(jsonPath("$.tables[0].grades[0].grade").value("U"))
                .andExpect(jsonPath("$.tables[0].grades[0].price").value(5))
                .andExpect(jsonPath("$.tables[0].grades[10].grade").value("10"))
                .andExpect(jsonPath("$.tables[0].grades[10].method").value("OBSERVED"))
                .andExpect(jsonPath("$.tables[0].grades[10].n").value(2))
                .andExpect(jsonPath("$.tables[0].grades[5].method").value("INTERPOLATED"))
                .andExpect(jsonPath("$.tables[0].grades[5].basis", org.hamcrest.Matchers.contains("1", "10")))
                .andExpect(jsonPath("$.skipped", hasItem(containsString("CGC: No era step-down ratio"))));

        // The table is kept: reading it again needs no refresh.
        String setId = TestApi.read(api.get("/api/admin/catalog-cards/" + card + "/prices", api.admin()), "$.tables[0].snapshotSetId");
        assertThat(UUID.fromString(setId)).isNotNull();
    }

    @Test
    void onlyAdminsAndOnlyKnownCards() throws Exception {
        String card = identified("Manual Guarded", LocalDate.of(2005, 8, 1));
        api.get("/api/admin/catalog-cards/" + card + "/prices", api.user("auth0|not-admin-" + UUID.randomUUID(), List.of()))
                .andExpect(status().isForbidden());
        api.get("/api/admin/catalog-cards/" + UUID.randomUUID() + "/prices", api.admin()).andExpect(status().isNotFound());
    }
}

package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.identity.SecurityProperties;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * An admin maps a catalog card to a PokeTrace card, its sale days become comps, and a remap stops the
 * old card's comps pricing it. PokeTrace itself is faked.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PokeTraceFlowTest.FakeConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class PokeTraceFlowTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    /** "pt-a" matches the card; "pt-b" has another number and no sales. */
    static final class FakeApi implements PokeTraceApi {
        volatile String setName = "";
        volatile boolean onlyOtherNumbers;
        final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public List<Map<String, Object>> search(String text) {
            if (onlyOtherNumbers) {
                return List.of(detail("pt-b"));
            }
            return List.of(detail("pt-b"), detail("pt-a"), Map.of("id", "pt-sealed", "productType", "sealed"));
        }

        @Override
        public Map<String, Object> card(String id) {
            return id.startsWith("pt-a") || id.startsWith("pt-b") ? detail(id) : Map.of();
        }

        private Map<String, Object> detail(String id) {
            return Map.of("id", id, "name", "Flow Charizard", "cardNumber", id.equals("pt-a") ? "004/102" : "5/102",
                    "variant", "Holofoil", "currency", "USD", "set", Map.of("slug", "s", "name", setName),
                    "gradedOptions", List.of("PSA_8", "PSA_10", "CGC_10"), "conditionOptions", List.of("NEAR_MINT"));
        }

        @Override
        public List<Map<String, Object>> history(String id, String tier, String period) {
            asked.add(id + ":" + tier + "@" + period);
            if (!id.equals("pt-a")) {
                return List.of();
            }
            int avg = tier.equals("PSA_10") ? 1000 : tier.equals("PSA_8") ? 400 : 150;
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(Map.of("date", TODAY.toString(), "source", "ebay", "saleCount", 3, "avg", 9999));
            for (int d = 1; d <= 5; d++) {
                rows.add(Map.of("date", TODAY.minusDays(d).toString(), "source", "ebay", "saleCount", d, "avg", avg));
            }
            rows.add(Map.of("date", TODAY.minusDays(6).toString(), "source", "ebay", "saleCount", 0, "avg", avg));
            rows.add(Map.of("date", TODAY.minusDays(7).toString(), "source", "cardmarket_unsold", "saleCount", 4, "avg", avg));
            return rows;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeConfig {
        @Bean
        FakeApi fakePokeTraceApi() {
            return new FakeApi();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private FakeApi fake;

    @Autowired
    private PokeTraceMappings mappings;

    @Autowired
    private SoldCompRepository comps;

    @Autowired
    private PricingProperties properties;

    @Autowired
    private CompIngestionService ingestion;

    @Autowired
    private PriceSnapshotService snapshots;

    @Autowired
    private Catalog catalog;

    private TestApi api;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        orgId = api.collectorOrg("PokeTrace " + UUID.randomUUID(), "auth0|pt-" + UUID.randomUUID());
    }

    private String identified(String setName) throws Exception {
        String cardId = api.card(orgId, "Flow Charizard", api.admin());
        String json = "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\","
                + "\"setName\":\"" + setName + "\",\"setReleaseDate\":\"1999-01-09\",\"number\":\"4\",\"name\":\"Flow Charizard\"}";
        return TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                json, api.admin()).andExpect(status().isCreated()), "$.current.card.id");
    }

    private ResultActions putJson(String url, String json, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(put(url).with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void ingest(String card) {
        PokeTraceSource source = new PokeTraceSource(fake, mappings, comps, properties);
        ingestion.ingest(source, catalog.cards(List.of(UUID.fromString(card))), TODAY.minusDays(90), TODAY);
    }

    @Test
    void anAdminMapsTheCardAndItsSaleDaysPriceIt() throws Exception {
        String setName = "PT Set " + UUID.randomUUID().toString().substring(0, 6);
        fake.setName = setName;
        String card = identified(setName);
        String url = "/api/admin/catalog-cards/" + card + "/poketrace";

        api.get(url, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.poketraceId").doesNotExist());

        // Only the card with our number; the other number and the sealed product are left out.
        api.get(url + "/candidates", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].poketraceId").value("pt-a"))
                .andExpect(jsonPath("$[0].disagreements.length()").value(0));
        // With no card of our number, the wider list stays and says why it isn't ours.
        fake.onlyOtherNumbers = true;
        try {
            api.get(url + "/candidates", api.admin()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].poketraceId").value("pt-b"))
                    .andExpect(jsonPath("$[0].disagreements[0]", containsString("number")));
        } finally {
            fake.onlyOtherNumbers = false;
        }

        // Refused: a disagreeing card without a reason, a card PokeTrace doesn't have, a non-admin.
        putJson(url, "{\"poketraceId\":\"pt-b\"}", api.admin()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("Give a reason")));
        putJson(url, "{\"poketraceId\":\"pt-zzz\"}", api.admin()).andExpect(status().isBadRequest());
        putJson(url, "{\"poketraceId\":\"pt-a\"}", api.user("auth0|nope-" + UUID.randomUUID(), List.of()))
                .andExpect(status().isForbidden());

        putJson(url, "{\"poketraceId\":\"pt-a\",\"overrideReason\":\"not needed\"}", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.poketraceId").value("pt-a"))
                .andExpect(jsonPath("$.overrideReason").doesNotExist())
                .andExpect(jsonPath("$.poketraceCard.cardNumber").value("004/102"));

        // First pull: a year back. PSA 8, PSA 10 and near mint, 5 sale days each; today, no-sale days and listings are left out.
        ingest(card);
        assertThat(fake.asked).filteredOn(a -> a.startsWith("pt-a:")).containsExactly("pt-a:PSA_8@1y", "pt-a:PSA_10@1y",
                "pt-a:NEAR_MINT@1y");
        List<SoldComp> stored = comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(UUID.fromString(card));
        assertThat(stored).hasSize(15).allSatisfy(c -> {
            assertThat(c.getSource()).isEqualTo(PokeTraceSource.NAME);
            assertThat(c.getSoldOn()).isBefore(TODAY);
        });

        PriceSnapshotService.Result priced = snapshots.snapshotAll(TODAY);
        assertThat(priced.snapshotSets().get(UUID.fromString(card))).containsOnlyKeys(PriceSource.GradingCompany.PSA);
        api.get("/api/admin/catalog-cards/" + card + "/prices", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].grades[10].n").value(5))
                .andExpect(jsonPath("$.tables[0].grades[10].price").value(1000));

        // Later pulls: the last 30 days, and a day already stored isn't stored again.
        fake.asked.clear();
        ingest(card);
        assertThat(fake.asked).filteredOn(a -> a.startsWith("pt-a:")).allMatch(a -> a.endsWith("@30d"));
        assertThat(comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(UUID.fromString(card))).hasSize(15);

        // Remapped (with a reason, since pt-b disagrees): pt-a's comps are kept but no longer price the card.
        putJson(url, "{\"poketraceId\":\"pt-b\",\"overrideReason\":\"PokeTrace numbers it 5\"}", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideReason").value("PokeTrace numbers it 5"))
                .andExpect(jsonPath("$.poketraceCard.disagreements[0]", containsString("number")));
        PriceSnapshotService.Result remapped = snapshots.snapshotAll(TODAY);
        assertThat(remapped.snapshotSets()).doesNotContainKey(UUID.fromString(card));
        assertThat(remapped.skipped().get(UUID.fromString(card))).contains("No graded sales");
        assertThat(comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(UUID.fromString(card))).hasSize(15);

        // Unmapped: nothing is asked for.
        mvc.perform(delete(url).with(api.admin())).andExpect(status().isOk()).andExpect(jsonPath("$.poketraceId").doesNotExist());
        fake.asked.clear();
        ingest(card);
        assertThat(fake.asked).isEmpty();
    }
}

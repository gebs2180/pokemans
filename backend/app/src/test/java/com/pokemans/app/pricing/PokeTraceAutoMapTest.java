package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.identity.SecurityProperties;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Automatic PokeTrace mapping, switched on: an identified card with exactly one clean candidate is mapped by the
 * system and its sales pulled; with none or several, the card waits on the admin's queue. PokeTrace is faked.
 */
@SpringBootTest(properties = {"pokemans.condition.worker.enabled=false", "pokemans.pricing.poketrace.auto-map.enabled=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PokeTraceAutoMapTest.FakeConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class PokeTraceAutoMapTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    /** How many PokeTrace cards share our number (004/102), each test's own set; "pt-b" (5/102) is always found too. */
    static final class FakeApi implements PokeTraceApi {
        volatile String setName = "";
        volatile int clean = 1;

        private List<String> ids() {
            List<String> ids = new ArrayList<>();
            for (int i = 1; i <= clean; i++) {
                ids.add("pt-a-" + Math.abs(setName.hashCode()) + "-" + i);
            }
            ids.add("pt-b");
            return ids;
        }

        @Override
        public List<Map<String, Object>> search(String text) {
            return ids().stream().map(this::detail).toList();
        }

        @Override
        public Map<String, Object> card(String id) {
            return ids().contains(id) ? detail(id) : Map.of();
        }

        private Map<String, Object> detail(String id) {
            return Map.of("id", id, "name", "Auto Charizard", "cardNumber", id.startsWith("pt-a") ? "004/102" : "5/102",
                    "variant", "Holofoil", "currency", "USD", "set", Map.of("slug", "s", "name", setName),
                    "gradedOptions", List.of("PSA_10"), "conditionOptions", List.of());
        }

        @Override
        public List<Map<String, Object>> history(String id, String tier, String period) {
            if (!id.startsWith("pt-a") || !tier.equals("PSA_10")) {
                return List.of();
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int d = 1; d <= 3; d++) {
                rows.add(Map.of("date", TODAY.minusDays(d).toString(), "source", "ebay", "saleCount", 1, "avg", 1000));
            }
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
    private PokeTraceAutoMap autoMap;

    @Autowired
    private PokeTraceSuggestions suggestions;

    @Autowired
    private Catalog catalog;

    @Autowired
    private SoldCompRepository comps;

    @Autowired
    private JdbcClient jdbc;

    private TestApi api;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        orgId = api.collectorOrg("PokeTrace auto " + UUID.randomUUID(), "auth0|pt-auto-" + UUID.randomUUID());
    }

    /** A card the admin identified in a new set, with {@code clean} PokeTrace cards matching it. */
    private UUID identified(int clean) throws Exception {
        String setName = "PT Auto " + UUID.randomUUID().toString().substring(0, 6);
        fake.setName = setName;
        fake.clean = clean;
        String cardId = api.card(orgId, "Auto Charizard", api.admin());
        String json = "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\","
                + "\"setName\":\"" + setName + "\",\"setReleaseDate\":\"1999-01-09\",\"number\":\"4\",\"name\":\"Auto Charizard\"}";
        return UUID.fromString(TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                json, api.admin()).andExpect(status().isCreated()), "$.current.card.id"));
    }

    /** The card's row in the admin's queue, once its automatic search is stored. */
    private Map<String, Object> searched(UUID card) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> {
            String body = api.get("/api/admin/poketrace/queue", api.admin()).andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> rows = JsonPath.read(body, "$.items[?(@.catalogCardId == '" + card + "')]");
            return rows.isEmpty() || rows.getFirst().get("searchedAt") == null ? null : rows.getFirst();
        }, r -> r != null);
    }

    private int pulls(UUID card) {
        return jdbc.sql("select count(*) from comp_pull where catalog_card_id = :c and ok").param("c", card).query(Integer.class).single();
    }

    @Test
    void oneCleanCandidateIsMappedAndItsSalesPulled() throws Exception {
        UUID card = identified(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> pulls(card) == 1);

        PokeTraceCard mapping = mappings.current(card).orElseThrow();
        assertThat(mapping.isAutomatic()).isTrue();
        assertThat(mapping.getRuleVersion()).isEqualTo(PokeTraceAutoMap.RULE_VERSION);
        assertThat(mapping.getMappedBy()).isNull();
        assertThat(mapping.getOverrideReason()).isNull();
        assertThat(mapping.getPoketraceId()).startsWith("pt-a");
        assertThat(comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(card)).hasSize(3)
                .allSatisfy(c -> assertThat(c.getSource()).isEqualTo(PokeTraceSource.NAME));

        api.get("/api/admin/catalog-cards/" + card + "/poketrace", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.automatic").value(true))
                .andExpect(jsonPath("$.ruleVersion").value(PokeTraceAutoMap.RULE_VERSION))
                .andExpect(jsonPath("$.mappedBy").doesNotExist());
        // Repriced straight after, as the admin's "Pull sales now" does.
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql(
                "select count(*) from price_snapshot_run_card where catalog_card_id = :c").param("c", card)
                .query(Integer.class).single() > 0);

        // Identified again: the mapping stands, nothing is mapped or pulled again.
        suggestions.identified(fake, card);
        assertThat(jdbc.sql("select count(*) from poketrace_card where catalog_card_id = :c").param("c", card)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(pulls(card)).isEqualTo(1);
    }

    @Test
    void severalCleanCandidatesWaitForTheAdmin() throws Exception {
        UUID card = identified(2);
        Map<String, Object> row = searched(card);
        assertThat(row.get("suggestedId")).isNull();
        assertThat((List<?>) row.get("candidates")).hasSize(2); // both of our number; the other number is left out
        assertThat(mappings.current(card)).isEmpty();
        assertThat(pulls(card)).isZero();
    }

    @Test
    void noCleanCandidateWaitsForTheAdmin() throws Exception {
        UUID card = identified(0);
        Map<String, Object> row = searched(card);
        assertThat(row.get("suggestedId")).isNull();
        assertThat(mappings.current(card)).isEmpty();
        // Even asked directly, a disagreeing card is never mapped automatically.
        assertThat(autoMap.map(fake, catalog.cards(List.of(card)).getFirst(), "pt-b")).isEmpty();
        assertThat(mappings.current(card)).isEmpty();
        assertThat(pulls(card)).isZero();
    }

    @Test
    void aCardTheAdminMappedOrUnmappedIsLeftAlone() throws Exception {
        UUID card = identified(0);
        searched(card);
        fake.clean = 1; // PokeTrace now has a clean match, but the admin got there first
        String cleanId = "pt-a-" + Math.abs(fake.setName.hashCode()) + "-1";
        Catalog.CardSummary summary = catalog.cards(List.of(card)).getFirst();
        String url = "/api/admin/catalog-cards/" + card + "/poketrace";

        mvc.perform(put(url).with(api.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"poketraceId\":\"pt-b\",\"overrideReason\":\"PokeTrace numbers it 5\"}")).andExpect(status().isOk());
        assertThat(autoMap.map(fake, summary, cleanId)).isEmpty();
        assertThat(mappings.poketraceId(card)).contains("pt-b");

        mvc.perform(delete(url).with(api.admin())).andExpect(status().isOk());
        assertThat(autoMap.map(fake, summary, cleanId)).isEmpty();
        suggestions.identified(fake, card);
        assertThat(mappings.poketraceId(card)).isEmpty();
        assertThat(jdbc.sql("select count(*) from poketrace_card where catalog_card_id = :c and automatic")
                .param("c", card).query(Integer.class).single()).isZero();
        assertThat(pulls(card)).isZero();
    }
}

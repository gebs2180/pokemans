package com.pokemans.app.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.catalog.Catalog;
import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.identity.SecurityProperties;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.jdbc.core.simple.JdbcClient;
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
        /** When set, PokeTrace's revised average for yesterday's PSA 8 day. */
        volatile Integer revisedPsa8;
        volatile boolean failing;
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
            return Map.of("id", id, "name", "Flow Charizard", "cardNumber", id.startsWith("pt-a") ? "004/102" : "5/102",
                    "variant", "Holofoil", "currency", "USD", "set", Map.of("slug", "s", "name", setName),
                    "gradedOptions", List.of("PSA_6", "PSA_8", "PSA_10", "CGC_10"), "conditionOptions", List.of("NEAR_MINT"));
        }

        @Override
        public List<Map<String, Object>> history(String id, String tier, String period) {
            asked.add(id + ":" + tier + "@" + period);
            if (failing) {
                throw new IllegalStateException("PokeTrace timed out");
            }
            if (!id.startsWith("pt-a")) {
                return List.of();
            }
            int avg = tier.equals("PSA_10") ? 1000 : tier.equals("PSA_8") ? 400 : tier.equals("PSA_6") ? 300 : 150;
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(Map.of("date", TODAY.toString(), "source", "ebay", "saleCount", 3, "avg", 9999));
            for (int d = 1; d <= 5; d++) {
                int dayAvg = d == 1 && tier.equals("PSA_8") && revisedPsa8 != null ? revisedPsa8 : avg;
                rows.add(Map.of("date", TODAY.minusDays(d).toString(), "source", "ebay", "saleCount", d, "avg", dayAvg));
            }
            rows.add(Map.of("date", TODAY.minusDays(6).toString(), "source", "ebay", "saleCount", 0, "avg", avg));
            rows.add(Map.of("date", TODAY.minusDays(7).toString(), "source", "cardmarket_unsold", "saleCount", 4, "avg", avg));
            // Days the 1.5.0 guards leave out of pricing: a PSA 10 day five times its tier, a PSA 8 day nine times as wide as its low.
            if (tier.equals("PSA_10")) {
                rows.add(Map.of("date", TODAY.minusDays(8).toString(), "source", "ebay", "saleCount", 1, "avg", 5000,
                        "low", 5000, "high", 5000));
            } else if (tier.equals("PSA_8")) {
                rows.add(Map.of("date", TODAY.minusDays(9).toString(), "source", "ebay", "saleCount", 2, "avg", 500,
                        "low", 100, "high", 900));
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
    private SoldCompRepository comps;

    @Autowired
    private PricingProperties properties;

    @Autowired
    private CompIngestionService ingestion;

    @Autowired
    private PriceSnapshotService snapshots;

    @Autowired
    private Catalog catalog;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PokeTracePullGaps pullGaps;

    private TestApi api;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        orgId = api.collectorOrg("PokeTrace " + UUID.randomUUID(), "auth0|pt-" + UUID.randomUUID());
    }

    private String identified(String setName) throws Exception {
        return identified(setName, "4");
    }

    private String identified(String setName, String number) throws Exception {
        String cardId = api.card(orgId, "Flow Charizard", api.admin());
        String json = "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"HOLO\","
                + "\"setName\":\"" + setName + "\",\"setReleaseDate\":\"1999-01-09\",\"number\":\"" + number + "\",\"name\":\"Flow Charizard\"}";
        return TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + cardId + "/identifications",
                json, api.admin()).andExpect(status().isCreated()), "$.current.card.id");
    }

    private ResultActions putJson(String url, String json, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(put(url).with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private CompIngestRun ingest(String card) {
        PokeTraceSource source = new PokeTraceSource(fake, mappings, comps, properties);
        return ingestion.ingest(source, catalog.cards(List.of(UUID.fromString(card))), TODAY.minusDays(90), TODAY);
    }

    /** Maps the card to its own PokeTrace id (the fake serves any "pt-a…"), so its sale days are its own. */
    private String mapped(String card) throws Exception {
        String id = "pt-a-" + UUID.randomUUID().toString().substring(0, 8);
        putJson("/api/admin/catalog-cards/" + card + "/poketrace", "{\"poketraceId\":\"" + id + "\"}", api.admin())
                .andExpect(status().isOk());
        return id;
    }

    private String gradeTrace(PriceSnapshotService.Result priced, String card, String grade) {
        return jdbc.sql("select trace::text from price_snapshot where snapshot_set_id = :s and grade = :g")
                .param("s", priced.snapshotSets().get(UUID.fromString(card)).get(PriceSource.GradingCompany.PSA))
                .param("g", grade).query(String.class).single();
    }

    private SoldComp stored(UUID cardId, String externalSaleId) {
        return comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(cardId).stream()
                .filter(c -> c.getExternalSaleId().equals(externalSaleId)).findFirst().orElseThrow();
    }

    private List<String> exclusionRules(UUID compId) {
        return jdbc.sql("select rule from comp_exclusion where sold_comp_id = :c").param("c", compId).query(String.class).list();
    }

    /**
     * PokeTrace sends a new average for a day already stored (a PR #114 follow-up): the new values are appended as a
     * revision, never edited in, the newest prices the day from 1.7.0, and the older row is kept and traced as superseded.
     */
    @Test
    void aRevisedDayIsAppendedAndPricesAtItsNewestRevision() throws Exception {
        String setName = "PT Rev " + UUID.randomUUID().toString().substring(0, 6);
        fake.setName = setName;
        String card = identified(setName);
        mapped(card);
        UUID cardId = UUID.fromString(card);
        try {
            assertThat(ingest(card).getInserted()).isEqualTo(22);
            SoldComp original = comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(cardId).stream()
                    .filter(c -> c.getGradingCompany() == PriceSource.GradingCompany.PSA && c.getGrade().intValue() == 8
                            && c.getSoldOn().equals(TODAY.minusDays(1)))
                    .findFirst().orElseThrow();

            // The same values again are duplicates; a new average is revision 1, and the stored row is unchanged.
            CompIngestRun same = ingest(card);
            assertThat(same.getRevised()).isZero();
            assertThat(same.getDuplicates()).isEqualTo(22);
            fake.revisedPsa8 = 450;
            CompIngestRun revised = ingest(card);
            assertThat(revised.getRevised()).isEqualTo(1);
            assertThat(revised.getInserted()).isZero();
            assertThat(comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(cardId)).hasSize(23);
            SoldComp r1 = stored(cardId, original.getExternalSaleId() + "#r1");
            assertThat(r1.getPrice()).isEqualByComparingTo("450");
            assertThat(r1.getRaw()).containsEntry("revision", 1).containsEntry("revises", original.getExternalSaleId());
            assertThat(stored(cardId, original.getExternalSaleId()).getPrice()).isEqualByComparingTo("400");
            assertThat(ingest(card).getRevised()).isZero();

            // Priced at the revision; the original is stored as superseded and traced.
            PriceSnapshotService.Result priced = snapshots.snapshotAll(TODAY);
            api.get("/api/admin/catalog-cards/" + card + "/prices", api.admin()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.tables[0].parameterSet").value("1.8.0"))
                    .andExpect(jsonPath("$.tables[0].grades[8].n").value(5))
                    .andExpect(jsonPath("$.tables[0].grades[8].compIds", hasItem(r1.getId().toString())))
                    .andExpect(jsonPath("$.tables[0].grades[8].compIds", not(hasItem(original.getId().toString()))));
            assertThat(exclusionRules(original.getId())).isNotEmpty().allMatch(PokeTraceRevisions.SUPERSEDED::equals);
            assertThat(gradeTrace(priced, card, "8")).contains("superseded by PokeTrace's revision of the day")
                    .contains(original.getId().toString());

            // The day guards judge the newest revision: 5000 is over 3x the tier's other days, so the day prices nothing.
            fake.revisedPsa8 = 5000;
            assertThat(ingest(card).getRevised()).isEqualTo(1);
            PriceSnapshotService.Result guarded = snapshots.snapshotAll(TODAY);
            api.get("/api/admin/catalog-cards/" + card + "/prices", api.admin()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.tables[0].grades[8].n").value(4))
                    .andExpect(jsonPath("$.tables[0].grades[8].price").value(400));
            SoldComp r2 = stored(cardId, original.getExternalSaleId() + "#r2");
            assertThat(exclusionRules(r2.getId())).containsOnly(PokeTraceDayGuard.HIGH_DAY);
            assertThat(gradeTrace(guarded, card, "8")).contains("left out by the PokeTrace day guard HIGH_DAY");
        } finally {
            fake.revisedPsa8 = null;
        }
    }

    /** A mapped card whose pulls stop is flagged after the gap (a PR #114 follow-up); one pulled recently isn't. */
    @Test
    void aMappedCardWhosePullsStopIsFlagged() throws Exception {
        String setName = "PT Gap " + UUID.randomUUID().toString().substring(0, 6);
        fake.setName = setName;
        String card = identified(setName);
        UUID cardId = UUID.fromString(card);
        Instant now = Instant.now();
        // Unmapped: never a gap.
        assertThat(pullGaps.gaps(now.plus(Duration.ofDays(10)))).noneMatch(g -> g.catalogCardId().equals(cardId));

        String poketraceId = mapped(card);
        // Mapped but never pulled: flagged once the gap has passed since the mapping.
        assertThat(pullGaps.gaps(now)).noneMatch(g -> g.catalogCardId().equals(cardId));
        assertThat(pullGaps.gaps(now.plus(Duration.ofDays(3)))).filteredOn(g -> g.catalogCardId().equals(cardId))
                .singleElement().satisfies(g -> {
                    assertThat(g.lastPulledAt()).isNull();
                    assertThat(g.poketraceId()).isEqualTo(poketraceId);
                    assertThat(g.daysSince()).isEqualTo(2);
                });

        ingest(card);
        Instant pulled = Instant.now();
        assertThat(pullGaps.gaps(pulled.plus(Duration.ofDays(1)))).noneMatch(g -> g.catalogCardId().equals(cardId));

        // A failed pull doesn't count; the flag shows its error.
        fake.failing = true;
        try {
            ingest(card);
        } finally {
            fake.failing = false;
        }
        assertThat(pullGaps.gaps(pulled.plus(Duration.ofDays(4)))).filteredOn(g -> g.catalogCardId().equals(cardId))
                .singleElement().satisfies(g -> {
                    assertThat(g.lastPulledAt()).isNotNull().isBefore(pulled.plusSeconds(1));
                    assertThat(g.lastError()).contains("PokeTrace timed out");
                    assertThat(g.name()).isEqualTo("Flow Charizard");
                    assertThat(g.orgId()).isEqualTo(UUID.fromString(orgId));
                });

        api.get("/api/admin/poketrace/pull-gaps", api.user("auth0|nope-" + UUID.randomUUID(), List.of()))
                .andExpect(status().isForbidden());
        api.get("/api/admin/poketrace/pull-gaps", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.gapDays").value(2));
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

        // The admin's pull now, the first one: a year back. PSA 6, PSA 8, PSA 10 and near mint, 5 sale days each; today,
        // no-sale days and listings are left out. It re-prices straight after.
        api.postJson(url + "/pull", "", api.user("auth0|nope-" + UUID.randomUUID(), List.of())).andExpect(status().isForbidden());
        api.postJson(url + "/pull", "", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.fetched").value(22))
                .andExpect(jsonPath("$.inserted").value(22))
                .andExpect(jsonPath("$.repriced").value(true));
        assertThat(fake.asked).filteredOn(a -> a.startsWith("pt-a:")).containsExactly("pt-a:PSA_6@1y", "pt-a:PSA_8@1y", "pt-a:PSA_10@1y",
                "pt-a:NEAR_MINT@1y");
        List<SoldComp> stored = comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(UUID.fromString(card));
        assertThat(stored).hasSize(22).allSatisfy(c -> {
            assertThat(c.getSource()).isEqualTo(PokeTraceSource.NAME);
            assertThat(c.getSoldOn()).isBefore(TODAY);
        });

        PriceSnapshotService.Result priced = snapshots.snapshotAll(TODAY);
        assertThat(priced.snapshotSets().get(UUID.fromString(card))).containsOnlyKeys(PriceSource.GradingCompany.PSA);
        // The guarded days price nothing, and each run stores them with the rule that caught them.
        api.get("/api/admin/catalog-cards/" + card + "/prices", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].grades[10].n").value(5))
                .andExpect(jsonPath("$.tables[0].grades[10].price").value(1000))
                .andExpect(jsonPath("$.tables[0].grades[8].n").value(5))
                .andExpect(jsonPath("$.tables[0].grades[8].price").value(400));
        assertThat(jdbc.sql("select distinct rule from comp_exclusion where catalog_card_id = :c order by rule")
                .param("c", UUID.fromString(card)).query(String.class).list())
                .containsExactly(PokeTraceDayGuard.HIGH_DAY, PokeTraceDayGuard.WIDE_DAY);
        assertThat(jdbc.sql("select trace::text from price_snapshot where snapshot_set_id = :s and grade = '10'")
                .param("s", priced.snapshotSets().get(UUID.fromString(card)).get(PriceSource.GradingCompany.PSA))
                .query(String.class).single()).contains("left out by the PokeTrace day guard HIGH_DAY");

        // Later pulls: the last 30 days, and a day already stored isn't stored again.
        fake.asked.clear();
        ingest(card);
        assertThat(fake.asked).filteredOn(a -> a.startsWith("pt-a:")).allMatch(a -> a.endsWith("@30d"));
        assertThat(comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(UUID.fromString(card))).hasSize(22);

        // Remapped (with a reason, since pt-b disagrees): pt-a's comps are kept but no longer price the card.
        putJson(url, "{\"poketraceId\":\"pt-b\",\"overrideReason\":\"PokeTrace numbers it 5\"}", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideReason").value("PokeTrace numbers it 5"))
                .andExpect(jsonPath("$.poketraceCard.disagreements[0]", containsString("number")));
        PriceSnapshotService.Result remapped = snapshots.snapshotAll(TODAY);
        assertThat(remapped.snapshotSets()).doesNotContainKey(UUID.fromString(card));
        assertThat(remapped.skipped().get(UUID.fromString(card))).contains("No graded sales");
        assertThat(comps.findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(UUID.fromString(card))).hasSize(22);

        // Unmapped: nothing is asked for, and a pull is refused.
        mvc.perform(delete(url).with(api.admin())).andExpect(status().isOk()).andExpect(jsonPath("$.poketraceId").doesNotExist());
        api.postJson(url + "/pull", "", api.admin()).andExpect(status().isBadRequest());
        fake.asked.clear();
        ingest(card);
        assertThat(fake.asked).isEmpty();
    }

    /** The card's row in the admin's PokeTrace queue, once its automatic search has stored something. */
    private Map<String, Object> searched(String card) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = api.get("/api/admin/poketrace/queue", api.admin()).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> rows = JsonPath.read(body, "$.items[?(@.catalogCardId == '" + card + "')]");
            if (!rows.isEmpty() && rows.getFirst().get("searchedAt") != null) {
                return rows.getFirst();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No PokeTrace suggestion for " + card);
    }

    @Test
    void anIdentifiedCardIsSearchedAndAnAdminApprovesItsCleanMatch() throws Exception {
        String setName = "PT Queue " + UUID.randomUUID().toString().substring(0, 6);
        fake.setName = setName;
        String clean = identified(setName);
        String mismatch = identified(setName, "77");

        // Searched on identification: one clean match is suggested; no candidate with our number means none is.
        assertThat(searched(clean).get("suggestedId")).isEqualTo("pt-a");
        Map<String, Object> other = searched(mismatch);
        assertThat(other.get("suggestedId")).isNull();
        assertThat((List<?>) other.get("candidates")).hasSize(2);
        // A suggestion prices nothing: the card is still unmapped.
        assertThat(mappings.poketraceId(UUID.fromString(clean))).isEmpty();

        String user = "auth0|nope-" + UUID.randomUUID();
        api.get("/api/admin/poketrace/queue", api.user(user, List.of())).andExpect(status().isForbidden());
        String approve = "{\"catalogCardIds\":[\"" + clean + "\",\"" + mismatch + "\"]}";
        api.postJson("/api/admin/poketrace/queue/approve", approve, api.user(user, List.of())).andExpect(status().isForbidden());
        api.postJson("/api/admin/poketrace/queue/search", "", api.admin()).andExpect(status().isOk());

        api.postJson("/api/admin/poketrace/queue/approve", approve, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.mapped.length()").value(1))
                .andExpect(jsonPath("$.mapped[0]").value(clean))
                .andExpect(jsonPath("$.failures[0].catalogCardId").value(mismatch))
                .andExpect(jsonPath("$.failures[0].error", containsString("card page")));
        assertThat(mappings.poketraceId(UUID.fromString(clean))).contains("pt-a");
        assertThat(mappings.current(UUID.fromString(clean)).orElseThrow().getOverrideReason()).isNull();

        // Mapped cards leave the queue; approving again says it's already mapped.
        String body = api.get("/api/admin/poketrace/queue", api.admin()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<?>>read(body, "$.items[?(@.catalogCardId == '" + clean + "')]")).isEmpty();
        api.postJson("/api/admin/poketrace/queue/approve", "{\"catalogCardIds\":[\"" + clean + "\"]}", api.admin())
                .andExpect(jsonPath("$.failures[0].error").value("Already mapped"));
    }
}

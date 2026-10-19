package com.pokemans.app.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pokemans.app.TestApi;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
 * A collection valued end to end through the API: identities, assessments, comps, provenance, then a
 * run, its replay from stored inputs, and both reports.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ValuationFlowTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    /** Mostly 9s and 10s; the stress lands on 7 and 8, which have their own sales. */
    private static final String NEAR_MINT = "{\"distribution\":{\"9\":0.5,\"10\":0.5},\"confidence\":\"A\",\"reason\":\"reviewed\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BlobStore blobs;

    @Autowired
    private StorageProperties storage;

    private TestApi api;
    private String orgId;
    private String collectionId;
    private JwtRequestPostProcessor collector;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc, security);
        String subject = "auth0|valuation-" + UUID.randomUUID();
        orgId = api.collectorOrg("Valuation " + UUID.randomUUID(), subject);
        collector = api.user(subject, List.of());
        collectionId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections", "{\"name\":\"Pledge binder\"}", collector)
                .andExpect(status().isCreated()), "$.id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void valuesACollectionAndIssuesBothReports() throws Exception {
        // Ten clean cards: identified, assessed, priced with sales across three months, custody checked.
        for (int i = 0; i < 10; i++) {
            String card = card("Clean " + i, false);
            String catalog = identify(card, "Clean Card " + i, "2015-05-06", "NORMAL", "Rare");
            assess(card);
            priceLadder(catalog, 300 + 40 * i);
            custody(card, true);
        }
        String lot = card("Bulk lot of commons", true);
        String unidentified = card("Mystery card", false);
        String unpriced = card("Unpriced", false);
        identify(unpriced, "Never Sold", "2015-05-06", "NORMAL", "Rare");
        assess(unpriced);
        custody(unpriced, true);
        String vintage = card("Base Set Charizard", false);
        priceLadder(identify(vintage, "Charizard", "1999-01-09", "HOLO", "Rare Holo"), 900);
        assess(vintage);
        custody(vintage, true);
        String fake = card("Too good to be true", false);
        priceLadder(identify(fake, "Fake Card", "2015-05-06", "NORMAL", "Rare"), 500);
        assess(fake);
        custody(fake, true);
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + fake + "/authenticity-verifications",
                "{\"status\":\"COUNTERFEIT\",\"method\":\"IN_HAND_INSPECTION\",\"note\":\"wrong font\"}", api.admin())
                .andExpect(status().isCreated());
        String unchecked = card("At a friend's", false);
        priceLadder(identify(unchecked, "Unchecked Card", "2015-05-06", "NORMAL", "Rare"), 400);
        assess(unchecked);
        custody(unchecked, false);
        String thin = card("Thinly traded", false);
        String thinCatalog = identify(thin, "Thin Card", "2015-05-06", "NORMAL", "Rare");
        for (int grade = 7; grade <= 10; grade++) {
            for (int s = 0; s < 6; s++) {
                comp(thinCatalog, grade, 100 + 20 * grade, 2 + s); // all in one month: too few changes for sigma
            }
        }
        refresh(thinCatalog);
        assess(thin);
        custody(thin, true);

        // Only the admin runs valuations.
        String runs = "/api/admin/orgs/" + orgId + "/collections/" + collectionId + "/valuation-runs";
        api.postJson(runs, "{}", collector).andExpect(status().isForbidden());
        api.get("/api/admin/valuation/defaults", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.parameterSet").value("1.1.0"))
                .andExpect(jsonPath("$.gradingCompany").value("PSA"))
                .andExpect(jsonPath("$.gradingCost.fee").value(25));
        api.postJson(runs, "{\"gradingCost\":{\"fee\":-1,\"shipping\":5,\"insurance\":2,\"timeToCashRate\":0.02}}", api.admin())
                .andExpect(status().isBadRequest());

        String body = api.postJson(runs, "{\"note\":\"first look\"}", api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cardCount").value(17))
                .andExpect(jsonPath("$.gradingCompany").value("PSA"))
                .andExpect(jsonPath("$.gradingCost.fee").value(25))
                .andExpect(jsonPath("$.alphaLow").value(0.5))
                .andExpect(jsonPath("$.alphaHigh").value(0.7))
                .andExpect(jsonPath("$.capsApplied").value(true))
                .andExpect(jsonPath("$.note").value("first look"))
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.id");
        int eligible = JsonPath.read(body, "$.eligibleCount");
        assertThat(eligible).isBetween(10, 11);

        String detail = api.get("/api/admin/valuation-runs/" + runId, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(17))
                .andExpect(jsonPath("$.portfolioTrace[?(@.subject == 'concentration_caps')]").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(line(detail, lot).get("unvaluedReason")).isEqualTo("photographed lot: lots aren't valued");
        assertThat(line(detail, unidentified).get("unvaluedReason")).isEqualTo("not identified");
        assertThat(line(detail, unpriced).get("unvaluedReason")).isEqualTo("no PSA price table");
        assertThat((List<Object>) line(detail, vintage).get("exclusionReasons")).contains("unresolved authenticity flag");
        assertThat((List<Object>) line(detail, fake).get("exclusionReasons")).contains("unresolved authenticity flag");
        assertThat(line(detail, fake).get("insuredRv").toString()).isEqualTo("0.0");
        assertThat(line(detail, fake).get("insurerNote").toString()).contains("COUNTERFEIT");
        assertThat(line(detail, fake).get("lift")).isNull(); // a doubted card isn't worth grading
        assertThat((List<Object>) line(detail, unchecked).get("exclusionReasons")).containsExactly("custody not verified");
        assertThat(line(detail, vintage).get("insuredRv").toString()).isNotEqualTo("0.0"); // unverified, but insured
        assertThat(JsonPath.<List<Object>>read(detail, "$.cards[?(@.cardItemId == '" + thin + "')].trace[?(@.subject == 'sigma')]"))
                .hasSize(1);
        assertThat(JsonPath.<List<Object>>read(detail, "$.cards[?(@.cardItemId == '" + thin + "')].trace[?(@.subject == 'confidence_penalty')]"))
                .hasSize(1);
        Map<String, Object> clean = line(detail, null);
        assertThat(clean.get("eligible")).isEqualTo(true);
        assertThat(((Map<?, ?>) clean.get("inputs")).get("prices")).isNotNull();
        assertThat(JsonPath.<List<Object>>read(detail, "$.cards[?(@.gradeFirstRank)]")).isNotEmpty();

        // Reproducible from what the run stored, and nothing about it can be changed in place.
        api.get("/api/admin/valuation-runs/" + runId + "/replay", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reproduced").value(true))
                .andExpect(jsonPath("$.differences.length()").value(0));
        assertThatThrownBy(() -> jdbc.update("update valuation_run set collateral_base = 1 where id = ?::uuid", runId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update card_valuation set flv = 1 where run_id = ?::uuid", runId))
                .hasMessageContaining("append-only");

        // Reports: alpha is the lender's and only on a lender report.
        String reports = "/api/admin/valuation-runs/" + runId + "/reports";
        api.postJson(reports, "{\"type\":\"INSURER\",\"lenderAlpha\":0.6}", api.admin()).andExpect(status().isBadRequest());
        api.postJson(reports, "{\"type\":\"LENDER\",\"lenderAlpha\":1.5}", api.admin()).andExpect(status().isBadRequest());
        api.postJson(reports, "{\"type\":\"LENDER\"}", collector).andExpect(status().isForbidden());
        String lender = api.postJson(reports, "{\"type\":\"LENDER\",\"lenderAlpha\":0.6}", api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("LENDER"))
                .andExpect(jsonPath("$.lenderAlpha").value(0.6))
                .andExpect(jsonPath("$.sha256").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        api.postJson(reports, "{\"type\":\"INSURER\"}", api.admin()).andExpect(status().isCreated());

        String lenderId = JsonPath.read(lender, "$.id");
        String key = jdbc.queryForObject("select storage_key from report where id = ?::uuid", String.class, lenderId);
        byte[] pdf = blobs.read(storage.photoBucket(), key);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)))
                .isEqualTo(JsonPath.read(lender, "$.sha256"));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Collateral report", "Pledge binder", "The lender sets alpha", "Mystery card",
                    "custody not verified (unverified)");
        }

        // Members of the collection's org see the issued reports; nobody else does.
        String memberReports = "/api/orgs/" + orgId + "/collections/" + collectionId + "/reports";
        api.get(memberReports, collector).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].type", hasItem("INSURER")));
        api.get("/api/orgs/" + orgId + "/reports/" + lenderId + "/download", collector)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url", containsString(lenderId)));
        api.get("/api/admin/reports/" + lenderId + "/download", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.url", containsString(lenderId)));
        JwtRequestPostProcessor outsider = api.user("auth0|valuation-outsider-" + UUID.randomUUID(), List.of());
        api.get(memberReports, outsider).andExpect(status().isNotFound());
        api.get("/api/orgs/" + orgId + "/reports/" + lenderId + "/download", outsider).andExpect(status().isNotFound());
        String otherOrg = api.collectorOrg("Other " + UUID.randomUUID(), "auth0|valuation-other-" + UUID.randomUUID());
        api.get("/api/orgs/" + otherOrg + "/reports/" + lenderId + "/download", api.admin()).andExpect(status().isNotFound());

        api.get(runs, api.admin()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(runId));
    }

    @Test
    void aVerifiedGradeIsValuedAsGradedOnItsGradersTable() throws Exception {
        String contributorOrg = TestApi.read(api.postJson("/api/admin/orgs",
                "{\"name\":\"Graded " + UUID.randomUUID() + "\",\"type\":\"CONTRIBUTOR\"}", api.admin()).andExpect(status().isCreated()), "$.id");
        String token = TestApi.read(api.postJson("/api/admin/orgs/" + contributorOrg + "/invites", "{}", api.admin()), "$.token");
        JwtRequestPostProcessor member = api.user("auth0|valuation-contributor-" + UUID.randomUUID(), List.of());
        api.postJson("/api/invites/" + token + "/accept", "", member).andExpect(status().isOk());
        api.postJson("/api/orgs/" + contributorOrg + "/consent", "{\"version\":\"" + Consents.CONTRIBUTOR_TERMS_VERSION + "\"}", member)
                .andExpect(status().isOk());
        String collection = TestApi.read(api.postJson("/api/orgs/" + contributorOrg + "/collections", "{\"name\":\"Slabs\"}", member), "$.id");
        String card = TestApi.read(api.postJson("/api/orgs/" + contributorOrg + "/collections/" + collection + "/cards",
                "{\"label\":\"Lugia slab\",\"lot\":false,\"quantity\":1}", member), "$.id");
        api.uploadPhoto(contributorOrg, card, "FRONT", TestImages.goodFront(), member).andExpect(jsonPath("$.status").value("PASSED"));
        api.uploadPhoto(contributorOrg, card, "BACK", TestImages.goodBack(), member).andExpect(jsonPath("$.status").value("PASSED"));
        String outcome = TestApi.read(api.postJson("/api/orgs/" + contributorOrg + "/cards/" + card + "/outcomes",
                "{\"grader\":\"PSA\",\"certNumber\":\"" + (System.nanoTime() % 100_000_000) + "\",\"grade\":9.5}", member), "$.id");
        String catalog = TestApi.read(api.postJson("/api/admin/orgs/" + contributorOrg + "/cards/" + card + "/identifications",
                identityJson("Lugia " + UUID.randomUUID().toString().substring(0, 4), "2015-05-06", "NORMAL", "Rare"), api.admin())
                .andExpect(status().isCreated()), "$.current.card.id");
        priceLadder(catalog, 800);
        String runs = "/api/admin/orgs/" + contributorOrg + "/collections/" + collection + "/valuation-runs";

        // Before the cert is checked the card is raw, and with no assessment it can't be valued.
        String before = api.postJson(runs, "{}", api.admin()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        api.get("/api/admin/valuation-runs/" + JsonPath.read(before, "$.id"), api.admin())
                .andExpect(jsonPath("$.cards[0].unvaluedReason").value("no condition assessment"));

        api.postJson("/api/admin/outcomes/" + outcome + "/verifications", "{\"status\":\"VERIFIED\"}", api.admin())
                .andExpect(status().isCreated());
        String run = api.postJson(runs, "{}", api.admin()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.gradedShare").value(0)) // custody isn't verified, so nothing is in the base
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(run, "$.id");
        api.get("/api/admin/valuation-runs/" + runId, api.admin())
                .andExpect(jsonPath("$.cards[0].status").value("VALUED"))
                .andExpect(jsonPath("$.cards[0].graded").value(true))
                .andExpect(jsonPath("$.cards[0].inputs.graded.grade").value(9.5))
                .andExpect(jsonPath("$.cards[0].inputs.graded.gradingCompany").value("PSA"))
                .andExpect(jsonPath("$.cards[0].fmv").value(480.0)) // PSA 9.5 folds to 9: 0.6 x 800, gross
                .andExpect(jsonPath("$.cards[0].stressedFmv").value(480.0)) // a known grade gets no condition stress
                .andExpect(jsonPath("$.cards[0].lift").isEmpty())
                .andExpect(jsonPath("$.cards[0].exclusionReasons[0]").value("custody not verified"));

        // The insurer report carries the card's photos as pre-loss proof.
        String report = TestApi.read(api.postJson("/api/admin/valuation-runs/" + runId + "/reports", "{\"type\":\"INSURER\"}", api.admin())
                .andExpect(status().isCreated()), "$.id");
        String key = jdbc.queryForObject("select storage_key from report where id = ?::uuid", String.class, report);
        try (PDDocument doc = Loader.loadPDF(blobs.read(storage.photoBucket(), key))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Replacement value report", "Front, captured", "Back, captured",
                    "PSA 9.5 (cert");
            assertThat(doc.getPage(0).getResources().getXObjectNames()).isNotEmpty();
        }
        api.get("/api/admin/valuation-runs/" + runId + "/replay", api.admin()).andExpect(jsonPath("$.reproduced").value(true));
    }

    private String card(String label, boolean lot) throws Exception {
        return TestApi.read(api.postJson("/api/orgs/" + orgId + "/collections/" + collectionId + "/cards",
                "{\"label\":\"" + label + "\",\"lot\":" + lot + ",\"quantity\":" + (lot ? 30 : 1) + "}", collector)
                .andExpect(status().isCreated()), "$.id");
    }

    /** Identifies the card as a new manual catalog card; returns the catalog card id. */
    private String identify(String card, String name, String released, String variant, String rarity) throws Exception {
        return TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/cards/" + card + "/identifications",
                identityJson(name, released, variant, rarity), api.admin()).andExpect(status().isCreated()), "$.current.card.id");
    }

    private static String identityJson(String name, String released, String variant, String rarity) {
        return "{\"provider\":\"MANUAL\",\"language\":\"en\",\"edition\":\"UNLIMITED\",\"variant\":\"" + variant + "\","
                + "\"setName\":\"Set " + released + "\",\"setReleaseDate\":\"" + released + "\",\"number\":\""
                + UUID.randomUUID().toString().substring(0, 6) + "\",\"name\":\"" + name + "\",\"rarity\":\"" + rarity + "\"}";
    }

    private void assess(String card) throws Exception {
        api.postJson("/api/admin/orgs/" + orgId + "/cards/" + card + "/assessments", NEAR_MINT, api.admin())
                .andExpect(status().isCreated());
    }

    /**
     * PSA sales at 7 to 10 (10 at {@code top}, 9 at 0.6, 8 at 0.4, 7 at 0.3 of it), six per grade over three
     * months: enough for confidence B and a measured sigma.
     */
    private void priceLadder(String catalog, int top) throws Exception {
        Map<Integer, Double> share = Map.of(10, 1.0, 9, 0.6, 8, 0.4, 7, 0.3);
        for (Map.Entry<Integer, Double> grade : share.entrySet()) {
            for (int daysAgo : new int[] {2, 5, 33, 36, 63, 66}) {
                comp(catalog, grade.getKey(), (int) Math.round(top * grade.getValue()), daysAgo);
            }
        }
        refresh(catalog);
    }

    private void comp(String catalog, int grade, int price, int daysAgo) throws Exception {
        api.postJson("/api/admin/catalog-cards/" + catalog + "/comps", "{\"gradingCompany\":\"PSA\",\"grade\":" + grade + ",\"price\":"
                + price + ",\"soldOn\":\"" + TODAY.minusDays(daysAgo) + "\",\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\"}",
                api.admin()).andExpect(status().isCreated());
    }

    private void refresh(String catalog) throws Exception {
        api.postJson("/api/admin/catalog-cards/" + catalog + "/prices/refresh", "", api.admin()).andExpect(status().isOk());
    }

    private void custody(String card, boolean checked) throws Exception {
        api.postJson("/api/orgs/" + orgId + "/cards/" + card + "/custody-records", "{\"holderType\":\"OWNER\",\"holderName\":\"Sam\","
                + "\"location\":\"Home safe\",\"heldSince\":\"" + TODAY.minusYears(1) + "\""
                + (checked ? ",\"verificationMethod\":\"VIDEO_CALL\"" : "") + "}", checked ? api.admin() : collector)
                .andExpect(status().isCreated());
    }

    /** The card's line in a run detail, or the first eligible card's when {@code card} is null. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> line(String detail, String card) {
        List<Map<String, Object>> lines = card == null
                ? JsonPath.read(detail, "$.cards[?(@.eligible == true)]")
                : JsonPath.read(detail, "$.cards[?(@.cardItemId == '" + card + "')]");
        return lines.getFirst();
    }
}

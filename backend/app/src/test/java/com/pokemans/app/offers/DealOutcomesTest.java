package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.outcomes.Outcomes;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What a buyer says happened to a card it bought (review C1, 2026-11-09). The test market's cards are
 * assessed 50/50 at 9 and 10, so the predicted median is 9 and the stressed median 7.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class DealOutcomesTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Outcomes outcomes;

    private TestApi api;
    private TestMarket market;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
        market = new TestMarket(api);
    }

    private record Deal(Seller seller, Buyer buyer, Posted card) {

        String outcomes() {
            return "/api/orgs/" + buyer.orgId() + "/deals/" + card.listingId() + "/outcomes";
        }
    }

    private Deal taken(String name) throws Exception {
        Seller seller = market.seller();
        Posted card = market.posted(seller, name);
        Buyer buyer = market.buyer();
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());
        return new Deal(seller, buyer, card);
    }

    private Deal arrived(String name) throws Exception {
        Deal deal = taken(name);
        api.postJson("/api/orgs/" + deal.buyer().orgId() + "/deals/" + deal.card().listingId() + "/received", "{}", deal.buyer().who())
                .andExpect(status().isCreated());
        return deal;
    }

    private static String graded(String cert, String grade) {
        return "{\"kind\":\"GRADED\",\"grader\":\"PSA\",\"certNumber\":\"" + cert + "\",\"grade\":" + grade + ",\"gradedOn\":\"2026-11-01\"}";
    }

    private static String cert() {
        return Long.toString(System.nanoTime());
    }

    @Test
    void onlyAMemberOfTheBuyingOrgCanSayWhatHappened() throws Exception {
        Deal deal = arrived("Outcome Charizard");
        Buyer other = market.buyer();
        String body = "{\"kind\":\"KEPT\"}";

        // Not the admin: it's the buyer's word, and nobody records it for them.
        api.postJson(deal.outcomes(), body, api.admin()).andExpect(status().isNotFound());
        // Not the seller, under the buyer's org or their own.
        api.postJson(deal.outcomes(), body, deal.seller().who()).andExpect(status().isNotFound());
        api.postJson("/api/orgs/" + deal.seller().orgId() + "/deals/" + deal.card().listingId() + "/outcomes", body, deal.seller().who())
                .andExpect(status().isNotFound());
        // Not another buyer, under the buying org or their own.
        api.postJson(deal.outcomes(), body, other.who()).andExpect(status().isNotFound());
        api.postJson("/api/orgs/" + other.orgId() + "/deals/" + deal.card().listingId() + "/outcomes", body, other.who())
                .andExpect(status().isNotFound());
        assertThat(rows(deal)).isZero();

        api.postJson(deal.outcomes(), body, deal.buyer().who()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.kept").value(1));
        assertThat(rows(deal)).isOne();
    }

    @Test
    void nothingIsRecordedBeforeTheCardArrives() throws Exception {
        Deal deal = taken("Early Blastoise");
        api.postJson(deal.outcomes(), "{\"kind\":\"KEPT\"}", deal.buyer().who()).andExpect(status().isConflict());
    }

    @Test
    void aGradeIsComparedWithThePredictionAndCorrectionsAppend() throws Exception {
        Deal deal = arrived("Graded Venusaur");
        String cert = cert();

        // 8.5 folds down to 8: within one of the median 9, above the stressed median 7.
        api.postJson(deal.outcomes(), graded(cert, "8.5"), deal.buyer().who()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.graded").value(1))
                .andExpect(jsonPath("$.withinOne").value(1))
                .andExpect(jsonPath("$.outcomes[0].grade").value(8.5))
                .andExpect(jsonPath("$.outcomes[0].certStatus").value("UNVERIFIED"));
        // The same thing twice is refused; a typo fixed is a new row, the old one kept.
        api.postJson(deal.outcomes(), graded(cert, "8.5"), deal.buyer().who()).andExpect(status().isConflict());
        api.postJson(deal.outcomes(), graded(cert, "6"), deal.buyer().who()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.withinOne").value(0))
                .andExpect(jsonPath("$.outcomes[0].earlier").value(1));
        assertThat(rows(deal)).isEqualTo(2);

        api.get("/api/admin/deal-outcomes", api.admin())
                .andExpect(jsonPath("$.rows[?(@.listingId=='" + deal.card().listingId() + "')].median").value("9"))
                .andExpect(jsonPath("$.rows[?(@.listingId=='" + deal.card().listingId() + "')].stressedMedian").value("7"))
                .andExpect(jsonPath("$.rows[?(@.listingId=='" + deal.card().listingId() + "')].returnedGrade").value("6"))
                .andExpect(jsonPath("$.rows[?(@.listingId=='" + deal.card().listingId() + "')].withinOne").value(false))
                .andExpect(jsonPath("$.rows[?(@.listingId=='" + deal.card().listingId() + "')].belowStressed").value(true));
        // The seller's card page doesn't show the buyer's grade.
        api.get("/api/orgs/" + deal.seller().orgId() + "/cards/" + deal.card().cardId() + "/outcomes", deal.seller().who())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aCheckedCertValuesTheCardAsGradedUntilTheBuyerTakesItBack() throws Exception {
        Deal deal = arrived("Checked Pikachu");
        api.postJson(deal.outcomes(), graded(cert(), "9"), deal.buyer().who()).andExpect(status().isCreated());
        UUID cardId = UUID.fromString(deal.card().cardId());
        assertThat(outcomes.verifiedGrades(List.of(cardId))).isEmpty();

        String gradeId = jdbc.queryForObject("select grade_outcome_id::text from deal_outcome where listing_id = ?::uuid",
                String.class, deal.card().listingId());
        api.postJson("/api/admin/outcomes/" + gradeId + "/verifications", "{\"status\":\"VERIFIED\"}", api.admin())
                .andExpect(status().isCreated());
        assertThat(outcomes.verifiedGrades(List.of(cardId)).get(cardId).grade()).isEqualByComparingTo(new BigDecimal("9"));
        api.get("/api/orgs/" + deal.buyer().orgId() + "/deal-outcomes", deal.buyer().who())
                .andExpect(jsonPath("$.verified").value(1));

        // "Kept" after all: the grade stays stored but no longer says what the card is.
        api.postJson(deal.outcomes(), "{\"kind\":\"KEPT\",\"note\":\"Wrong card, sorry\"}", deal.buyer().who()).andExpect(status().isCreated());
        assertThat(outcomes.verifiedGrades(List.of(cardId))).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from grade_outcome where card_item_id = ?::uuid", Integer.class, cardId)).isOne();
    }

    @Test
    void resoldRawNeedsAPriceAndACertBelongsToOneCard() throws Exception {
        Deal deal = arrived("Resold Mew");
        api.postJson(deal.outcomes(), "{\"kind\":\"RESOLD_RAW\"}", deal.buyer().who()).andExpect(status().isBadRequest());
        api.postJson(deal.outcomes(), "{\"kind\":\"RESOLD_RAW\",\"resalePrice\":120.50}", deal.buyer().who()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.resold").value(1))
                .andExpect(jsonPath("$.outcomes[0].resalePrice").value(120.5));
        api.postJson(deal.outcomes(), "{\"kind\":\"GRADED\",\"grader\":\"PSA\",\"certNumber\":\"1\",\"grade\":9.3}", deal.buyer().who())
                .andExpect(status().isBadRequest());
        api.postJson(deal.outcomes(), "{\"kind\":\"GRADED\",\"grader\":\"XYZ\",\"certNumber\":\"1\",\"grade\":9}", deal.buyer().who())
                .andExpect(status().isBadRequest());

        Deal other = arrived("Another Mew");
        String cert = cert();
        api.postJson(deal.outcomes(), graded(cert, "9"), deal.buyer().who()).andExpect(status().isCreated());
        api.postJson(other.outcomes(), graded(cert, "9"), other.buyer().who()).andExpect(status().isConflict());
    }

    @Test
    void outcomesCantBeEdited() throws Exception {
        Deal deal = arrived("Sealed Gengar");
        api.postJson(deal.outcomes(), "{\"kind\":\"KEPT\"}", deal.buyer().who()).andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update("update deal_outcome set note = 'changed' where listing_id = ?::uuid", deal.card().listingId()))
                .hasMessageContaining("append-only");
    }

    private int rows(Deal deal) {
        return jdbc.queryForObject("select count(*) from deal_outcome where listing_id = ?::uuid", Integer.class, deal.card().listingId());
    }
}

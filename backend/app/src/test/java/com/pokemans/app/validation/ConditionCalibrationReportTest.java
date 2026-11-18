package com.pokemans.app.validation;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.offers.FakeShippingProvider;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The condition calibration report over stored rows. The test market's cards are assessed 50/50 at 9 and
 * 10 at confidence B, so the predicted median is 9 and the stressed median 7.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class ConditionCalibrationReportTest {

    private static final String REPORT = "/api/admin/validation/condition-calibration";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    private TestApi api;
    private TestMarket market;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
        market = new TestMarket(api);
    }

    private record Deal(Buyer buyer, Posted card) {

        String outcomes() {
            return "/api/orgs/" + buyer.orgId() + "/deals/" + card.listingId() + "/outcomes";
        }
    }

    private Deal arrived(String name) throws Exception {
        Seller seller = market.seller();
        Posted card = market.posted(seller, name);
        Buyer buyer = market.buyer();
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());
        api.postJson("/api/orgs/" + buyer.orgId() + "/deals/" + card.listingId() + "/received", "{}", buyer.who())
                .andExpect(status().isCreated());
        return new Deal(buyer, card);
    }

    private void graded(Deal deal, String grade, String gradedOn) throws Exception {
        String on = gradedOn == null ? "" : ",\"gradedOn\":\"" + gradedOn + "\"";
        api.postJson(deal.outcomes(), "{\"kind\":\"GRADED\",\"grader\":\"PSA\",\"certNumber\":\"" + System.nanoTime()
                + "\",\"grade\":" + grade + on + "}", deal.buyer().who()).andExpect(status().isCreated());
    }

    private static String row(Deal deal) {
        return "$.rows[?(@.cardItemId=='" + deal.card().cardId() + "')]";
    }

    @Test
    void eachReturnedGradeIsScoredAgainstTheAssessmentBeforeIt() throws Exception {
        Deal nine = arrived("Calibrated Gengar");
        graded(nine, "9", null);
        Deal five = arrived("Calibrated Pikachu");
        graded(five, "5", null);
        // Taken back: the buyer said "kept" after all, so the grade no longer counts.
        Deal retracted = arrived("Calibrated Venusaur");
        graded(retracted, "6", null);
        api.postJson(retracted.outcomes(), "{\"kind\":\"KEPT\"}", retracted.buyer().who()).andExpect(status().isCreated());
        // Graded before the card was assessed: the assessment can't have predicted it.
        Deal early = arrived("Calibrated Mew");
        graded(early, "9", "2020-01-01");

        api.get(REPORT, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath(row(nine) + ".median").value("9"))
                .andExpect(jsonPath(row(nine) + ".stressedMedian").value("7"))
                .andExpect(jsonPath(row(nine) + ".returned").value("9"))
                .andExpect(jsonPath(row(nine) + ".withinOne").value(true))
                .andExpect(jsonPath(row(nine) + ".belowStressed").value(false))
                .andExpect(jsonPath(row(nine) + ".source").value("BUYER"))
                .andExpect(jsonPath(row(five) + ".error").value(-4))
                .andExpect(jsonPath(row(five) + ".belowStressed").value(true))
                .andExpect(jsonPath(row(retracted)).value(Matchers.empty()))
                .andExpect(jsonPath(row(early)).value(Matchers.empty()))
                .andExpect(jsonPath("$.unpredicted").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.psa.n").value(Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void onlyTheAdminSeesIt() throws Exception {
        Seller seller = market.seller();
        api.get(REPORT, seller.who()).andExpect(status().isForbidden());
    }
}

package com.pokemans.app.offers;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** What keeps a card off the market or out of a buyer's hands (audit of 2026-11-02, Zach's decisions). */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class MarketRulesTest {

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

    private void doubt(Seller seller, Posted card) throws Exception {
        api.postJson("/api/admin/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/authenticity-verifications",
                "{\"status\":\"SUSPECTED_COUNTERFEIT\",\"method\":\"IN_HAND_INSPECTION\"}", api.admin()).andExpect(status().isCreated());
    }

    @Test
    void aCardFoundDoubtfulComesOffTheMarket() throws Exception {
        Seller seller = market.seller();
        Posted card = market.posted(seller, "Doubtful Charizard");
        Buyer buyer = market.buyer();

        doubt(seller, card);

        api.get("/api/orgs/" + buyer.orgId() + "/market", buyer.who())
                .andExpect(jsonPath("$[?(@.listingId=='" + card.listingId() + "')]").isEmpty());
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isConflict());
        api.get("/api/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/sale", seller.who())
                .andExpect(jsonPath("$.listing.status").value("VOIDED"))
                .andExpect(jsonPath("$.offer.voided.reason").value("We have a doubt about this card's authenticity, so it came off the market."));
    }

    @Test
    void aDoubtfulCardsUnpostedOfferCantBePosted() throws Exception {
        Seller seller = market.seller();
        Posted card = market.offered(seller, "Doubtful Blastoise");

        doubt(seller, card);

        api.postJson("/api/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/listings",
                "{\"offerId\":\"" + card.offerId() + "\"}", seller.who()).andExpect(status().isConflict());
    }

    @Test
    void aDealAlreadyTakenStaysForTheAdminToDecide() throws Exception {
        Seller seller = market.seller();
        Posted card = market.posted(seller, "Doubtful Venusaur");
        Buyer buyer = market.buyer();
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());

        doubt(seller, card);

        api.get("/api/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/sale", seller.who())
                .andExpect(jsonPath("$.listing.status").value("ACCEPTED"));
    }

    @Test
    void nobodyBuysTheirOwnCard() throws Exception {
        String subject = "auth0|both-sides-" + UUID.randomUUID();
        Seller seller = market.seller(subject);
        Buyer sameLogin = market.buyer(subject);
        Posted card = market.posted(seller, "Wash Trade Pikachu");

        api.postJson(TestMarket.accept(sameLogin, card), "", sameLogin.who()).andExpect(status().isConflict());

        Buyer stranger = market.buyer();
        api.postJson(TestMarket.accept(stranger, card), "", stranger.who()).andExpect(status().isOk());
    }
}

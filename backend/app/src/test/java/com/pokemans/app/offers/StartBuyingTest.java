package com.pokemans.app.offers;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.SecurityProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One login buys and sells (Zach, 2026-11-22): a seller turns buying on from the same login, the admin approves or
 * declines, buyers see new listings at the same time, and nobody buys a card of any org they belong to.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class StartBuyingTest {

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

    private static String body(String version) {
        return "{\"name\":\"Sam buys\",\"version\":\"" + version + "\",\"shipTo\":" + TestMarket.SHIP_TO + "}";
    }

    private String marketOf(Buyer buyer) {
        return "/api/orgs/" + buyer.orgId() + "/market";
    }

    @Test
    void aSellerStartsBuyingAndWaitsForApprovalBeforeTheMarketOpens() throws Exception {
        Seller seller = market.seller();
        Posted someoneElses = market.posted(market.seller(), "Approved Pikachu");

        api.get("/api/me/buying", seller.who())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.terms.version").value(Consents.BUYER_TERMS_VERSION));

        Buyer waiting = market.startBuying(seller);
        api.get("/api/me/buying", seller.who())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.orgId").value(waiting.orgId()))
                .andExpect(jsonPath("$.orgName").value("Sam buys"))
                .andExpect(jsonPath("$.appliedAt").isNotEmpty());
        // Same login, both sides.
        api.get("/api/me", seller.who()).andExpect(jsonPath("$.memberships.length()").value(2));
        // The terms and the ship-to address went in with the request.
        api.get("/api/orgs/" + waiting.orgId() + "/consent", seller.who()).andExpect(status().isOk());
        api.get("/api/orgs/" + waiting.orgId() + "/ship-address", seller.who()).andExpect(jsonPath("$.street1").value("2 Oak St"));

        // Blocked until approved.
        api.get(marketOf(waiting), seller.who()).andExpect(status().isForbidden());
        api.postJson(TestMarket.accept(waiting, someoneElses), "", seller.who()).andExpect(status().isForbidden());
        api.get("/api/orgs/" + waiting.orgId() + "/deals", seller.who()).andExpect(status().isForbidden());

        // The admin sees who is asking, with the orgs they sell from.
        String request = market.request(waiting);
        api.get("/api/admin/buyers", api.admin())
                .andExpect(jsonPath("$[?(@.id=='" + request + "')].sellerOrgs[0]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id=='" + request + "')].attempt").value(hasItem(1)));
        // Admin-only.
        api.get("/api/admin/buyers", seller.who()).andExpect(status().isForbidden());
        api.postJson("/api/admin/buyers/" + request + "/approve", "", seller.who()).andExpect(status().isForbidden());

        api.postJson("/api/admin/buyers/" + request + "/approve", "", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.decidedBy").hasJsonPath())
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());
        api.postJson("/api/admin/buyers/" + request + "/approve", "", api.admin()).andExpect(status().isConflict());
        api.get("/api/me/buying", seller.who())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());

        api.get(marketOf(waiting), seller.who()).andExpect(jsonPath("$[?(@.listingId=='" + someoneElses.listingId() + "')]").isNotEmpty());
        api.postJson(TestMarket.accept(waiting, someoneElses), "", seller.who()).andExpect(status().isOk());

        // Approved is approved: no second request.
        api.postJson("/api/me/buying", body(Consents.BUYER_TERMS_VERSION), seller.who()).andExpect(status().isConflict());
    }

    @Test
    void aDeclinedSellerSeesWhyAndCanAskAgainOnTheSameBuyerAccount() throws Exception {
        Seller seller = market.seller();
        Buyer buyer = market.startBuying(seller);

        // Asking again while waiting changes nothing.
        api.postJson("/api/me/buying", body(Consents.BUYER_TERMS_VERSION), seller.who()).andExpect(status().isConflict());

        String first = market.request(buyer);
        api.postJson("/api/admin/buyers/" + first + "/decline", "{\"note\":\" \"}", api.admin()).andExpect(status().isBadRequest());
        api.postJson("/api/admin/buyers/" + first + "/decline", "{\"note\":\"Add a ship-to address we can label.\"}", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false));
        api.postJson("/api/admin/buyers/" + first + "/approve", "", api.admin()).andExpect(status().isConflict());

        api.get("/api/me/buying", seller.who())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.note").value("Add a ship-to address we can label."));
        api.get(marketOf(buyer), seller.who()).andExpect(status().isForbidden());

        api.postJson("/api/me/buying", body(Consents.BUYER_TERMS_VERSION), seller.who())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.orgId").value(buyer.orgId()))
                .andExpect(jsonPath("$.note").value(nullValue()));
        String second = market.request(buyer);
        api.get("/api/admin/buyers", api.admin()).andExpect(jsonPath("$[?(@.id=='" + second + "')].attempt").value(hasItem(2)));
        market.approve(buyer);
        api.get(marketOf(buyer), seller.who()).andExpect(status().isOk());
        api.get("/api/me", seller.who()).andExpect(jsonPath("$.memberships.length()").value(2));
    }

    @Test
    void startingNeedsTheCurrentTermsAnAddressAndAnAccountAlready() throws Exception {
        Seller seller = market.seller();
        api.postJson("/api/me/buying", body("buyer-firm-2026-11-01"), seller.who()).andExpect(status().isConflict());
        api.postJson("/api/me/buying", "{\"name\":\"Sam buys\",\"version\":\"" + Consents.BUYER_TERMS_VERSION + "\"}", seller.who())
                .andExpect(status().isBadRequest());
        api.get("/api/me/buying", seller.who()).andExpect(jsonPath("$.status").value("NOT_STARTED"));

        JwtRequestPostProcessor nobody = api.user("auth0|nobody-" + UUID.randomUUID(), List.of());
        api.postJson("/api/me/buying", body(Consents.BUYER_TERMS_VERSION), nobody).andExpect(status().isForbidden());
    }

    @Test
    void aDealerTheAdminInvitedNeedsNoApproval() throws Exception {
        Buyer dealer = market.buyer();
        api.get("/api/me/buying", dealer.who()).andExpect(jsonPath("$.status").value("APPROVED"));
        api.get(marketOf(dealer), dealer.who()).andExpect(status().isOk());
    }

    @Test
    void everyApprovedBuyerSeesANewListingAtOnce() throws Exception {
        Buyer dealer = market.buyer();
        Buyer collector = market.approve(market.startBuying(market.seller()));
        Posted card = market.posted(market.seller(), "Same Time Eevee");

        for (Buyer b : List.of(dealer, collector)) {
            api.get(marketOf(b), b.who()).andExpect(jsonPath("$[?(@.listingId=='" + card.listingId() + "')]").isNotEmpty());
        }
    }

    @Test
    void aSellerWhoBuysNeverSeesOrTakesTheirOwnCards() throws Exception {
        Seller seller = market.seller();
        Buyer buyer = market.approve(market.startBuying(seller));
        Posted own = market.posted(seller, "Own Charizard");

        api.get(marketOf(buyer), seller.who()).andExpect(jsonPath("$[?(@.listingId=='" + own.listingId() + "')]").isEmpty());
        api.postJson(TestMarket.accept(buyer, own), "", seller.who())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You can't buy your own card."));
    }

    @Test
    void noMemberOfTheBuyerOrgMayBeInTheSellerOrg() throws Exception {
        // A dealer org with two people; one of them also sells.
        Buyer dealer = market.buyer();
        String partnerSubject = "auth0|partner-" + UUID.randomUUID();
        String token = TestApi.read(api.postJson("/api/admin/orgs/" + dealer.orgId() + "/invites", "{}", api.admin()), "$.token");
        api.postJson("/api/invites/" + token + "/accept", "", api.user(partnerSubject, List.of())).andExpect(status().isOk());
        Seller partner = market.seller(partnerSubject);
        Posted card = market.posted(partner, "Partner Blastoise");

        // The other member can't take it for the shop either.
        api.get(marketOf(dealer), dealer.who()).andExpect(jsonPath("$[?(@.listingId=='" + card.listingId() + "')]").isEmpty());
        api.postJson(TestMarket.accept(dealer, card), "", dealer.who())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You can't buy your own card."));

        Buyer stranger = market.buyer();
        api.postJson(TestMarket.accept(stranger, card), "", stranger.who()).andExpect(status().isOk());
    }

    @Test
    void theAdminCantBuyACardOfAnOrgTheyBelongToOnABuyersBehalf() throws Exception {
        String adminSubject = "auth0|admin-sells-" + UUID.randomUUID();
        JwtRequestPostProcessor admin = api.user(adminSubject, List.of("ADMIN"));
        Seller adminsOwn = market.seller(adminSubject);
        Posted card = market.posted(adminsOwn, "Admin Mew");
        Buyer stranger = market.buyer();

        api.postJson(TestMarket.accept(stranger, card), "", admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You can't buy your own card."));
        api.postJson(TestMarket.accept(stranger, card), "", stranger.who()).andExpect(status().isOk());
    }
}

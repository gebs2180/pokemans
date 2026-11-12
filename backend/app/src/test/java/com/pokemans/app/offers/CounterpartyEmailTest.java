package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Zach, 2026-11-12: after a deal neither side sees the other's email, only the org name. The admin still sees both.
 * The views are separate types ({@code Party} has no email, {@code AdminParty} does), and this checks the JSON the
 * routes actually send.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class CounterpartyEmailTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void neitherSideSeesTheOthersEmailButTheAdminSeesBoth() throws Exception {
        TestApi api = new TestApi(mvc, security);
        TestMarket market = new TestMarket(api);
        Seller seller = market.seller("auth0|email-seller-" + UUID.randomUUID());
        Posted card = market.posted(seller, "Private Pidgeot");
        Buyer buyer = market.buyer();
        String sellerEmail = "seller-" + UUID.randomUUID() + "@example.com";
        String buyerEmail = "buyer-" + UUID.randomUUID() + "@example.com";
        setEmail(seller.orgId(), sellerEmail);
        setEmail(buyer.orgId(), buyerEmail);

        String accepted = api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String buyerDeals = api.get("/api/orgs/" + buyer.orgId() + "/deals", buyer.who()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sellerView = api.get("/api/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/sale", seller.who())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // The org name is still there, so each side knows who it dealt with.
        assertThat(sellerView).contains("\"orgName\"");
        assertThat(buyerDeals).contains("\"orgName\"");
        for (String body : new String[] {accepted, buyerDeals, sellerView}) {
            assertThat(body).doesNotContain(sellerEmail).doesNotContain(buyerEmail).doesNotContain("\"email\"");
        }

        String admin = api.get("/api/admin/offers", api.admin()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(admin).contains(buyerEmail);
    }

    /** Sets the email of everyone in the org, as the login would have copied it. */
    private void setEmail(String orgId, String email) {
        jdbc.update("update app_user set email = ? where id in (select user_id from membership where org_id = ?::uuid)", email, orgId);
    }
}

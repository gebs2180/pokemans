package com.pokemans.app.offers;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * Deep review 2026-11-09: B1 step 1, an offer below the card's recent raw near-mint sales is flagged (never refused);
 * B3, an offer whose price moved more than 10% on today's prices can't be posted or taken, and is voided by a new row.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class OfferStalenessTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcTemplate jdbc;

    private TestApi api;
    private TestMarket market;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
        market = new TestMarket(api);
    }

    private void rawNearMint(String catalog, int price, int daysAgo) throws Exception {
        api.postJson("/api/admin/catalog-cards/" + catalog + "/comps", "{\"rawCondition\":\"NEAR_MINT\",\"price\":" + price
                + ",\"soldOn\":\"" + TODAY.minusDays(daysAgo) + "\",\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\"}", api.admin())
                .andExpect(status().isCreated());
    }

    /** Eight fresh PSA 9 and 10 sales at three times the test ladder's prices, then a refresh: the offer's price jumps. */
    private void priceJumps(String catalog) throws Exception {
        for (int grade : new int[] {9, 10}) {
            for (int i = 0; i < 8; i++) {
                api.postJson("/api/admin/catalog-cards/" + catalog + "/comps", "{\"gradingCompany\":\"PSA\",\"grade\":" + grade
                        + ",\"price\":" + (grade == 10 ? 600 : 360) + ",\"soldOn\":\"" + TODAY.minusDays(1)
                        + "\",\"listingType\":\"AUCTION\",\"marketplace\":\"eBay\"}", api.admin()).andExpect(status().isCreated());
            }
        }
        api.postJson("/api/admin/catalog-cards/" + catalog + "/prices/refresh", "", api.admin()).andExpect(status().isOk());
    }

    private String sale(Seller seller, Posted card) {
        return "/api/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/sale";
    }

    private String listings(Seller seller, Posted card) {
        return "/api/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/listings";
    }

    // B1 step 1: the raw sanity band

    @Test
    void aQuoteBelowRecentRawSalesIsFlaggedButStillOffered() throws Exception {
        Seller seller = market.seller();
        Posted card = market.offered(seller, "Raw Heavy Gengar", catalog -> {
            rawNearMint(catalog, 650, 40);
            rawNearMint(catalog, 500, 10);
            rawNearMint(catalog, 20, 120); // outside the 90 days: doesn't count
        });

        api.get(sale(seller, card), seller.who())
                .andExpect(jsonPath("$.offer.id").value(card.offerId()))
                .andExpect(jsonPath("$.offer.rawSales.below").value(true))
                .andExpect(jsonPath("$.offer.rawSales.lowest").value(500.0))
                .andExpect(jsonPath("$.offer.rawSales.sales").value(2))
                .andExpect(jsonPath("$.offer.rawSales.since").value(TODAY.minusDays(90).toString()));
        api.get("/api/admin/cards/" + card.cardId() + "/offer-preview", api.admin())
                .andExpect(jsonPath("$.offered").value(true))
                .andExpect(jsonPath("$.rawSales.below").value(true))
                .andExpect(jsonPath("$.rawSales.lowest").value(500.0))
                // The lower middle of $500 and $650: never an average of the two.
                .andExpect(jsonPath("$.rawMedian").value(500.0))
                .andExpect(jsonPath("$.fmvFromTen").isNumber());
        org.assertj.core.api.Assertions.assertThat(new java.math.BigDecimal(TestApi.read(
                api.get("/api/admin/cards/" + card.cardId() + "/offer-preview", api.admin()), "$.fmvFromTen").toString()))
                .isPositive();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select below_raw_sales from card_offer where id = ?::uuid", Boolean.class, card.offerId())).isTrue();
        // Flagged against the $500 sale through the offer's own haircut, which is under the sale itself.
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select raw_nm_net_low > price and raw_nm_net_low < raw_nm_low from card_offer where id = ?::uuid", Boolean.class,
                card.offerId())).isTrue();

        // A flag only: the offer can be posted and sold, and buyers never see the check.
        api.postJson(listings(seller, card), "{\"offerId\":\"" + card.offerId() + "\"}", seller.who()).andExpect(status().isCreated());
        String listingId = TestApi.read(api.get(sale(seller, card), seller.who()), "$.listing.id");
        Buyer buyer = market.buyer();
        api.get("/api/orgs/" + buyer.orgId() + "/market", buyer.who())
                .andExpect(jsonPath("$[?(@.listingId=='" + listingId + "')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.listingId=='" + listingId + "')].offer.rawSales", everyItem(nullValue())));
        api.postJson(TestMarket.accept(buyer, new Posted(card.cardId(), card.offerId(), listingId)), "", buyer.who())
                .andExpect(status().isOk());
    }

    @Test
    void aQuoteAtOrAboveRawSalesOrWithNoneIsNotFlagged() throws Exception {
        Seller seller = market.seller();
        Posted cheapRaw = market.offered(seller, "Cheap Raw Oddish", catalog -> rawNearMint(catalog, 20, 5));
        api.get(sale(seller, cheapRaw), seller.who())
                .andExpect(jsonPath("$.offer.rawSales.below").value(false))
                .andExpect(jsonPath("$.offer.rawSales.lowest").value(20.0));

        Posted noRaw = market.offered(seller, "Graded Only Bellsprout");
        api.get(sale(seller, noRaw), seller.who())
                .andExpect(jsonPath("$.offer.rawSales.below").value(false))
                .andExpect(jsonPath("$.offer.rawSales.lowest").value(nullValue()))
                .andExpect(jsonPath("$.offer.rawSales.sales").value(0));
    }

    // B3: the price at post and at accept

    @Test
    void anOfferWhosePriceMovedCantBePostedAndIsVoidedByANewRow() throws Exception {
        Seller seller = market.seller();
        AtomicReference<String> catalog = new AtomicReference<>();
        Posted card = market.offered(seller, "Spiking Umbreon", catalog::set);

        priceJumps(catalog.get());

        api.postJson(listings(seller, card), "{\"offerId\":\"" + card.offerId() + "\"}", seller.who())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(OfferService.PRICE_CHANGED))
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
        api.get(sale(seller, card), seller.who())
                .andExpect(jsonPath("$.listing").value(nullValue()))
                .andExpect(jsonPath("$.offer.voided.reason").value(OfferService.PRICE_CHANGED));
        // The offer row is untouched; the void is its own row, which commits although the post was refused.
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from card_offer_void where offer_id = ?::uuid", Integer.class, card.offerId())).isEqualTo(1);
        // Both prices are kept, so the direction of the move can be counted: up, here.
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select v.repriced_was = o.price and v.repriced_now > v.repriced_was from card_offer_void v"
                        + " join card_offer o on o.id = v.offer_id where v.offer_id = ?::uuid", Boolean.class, card.offerId())).isTrue();

        // A new offer on today's prices posts.
        String fresh = TestApi.read(api.postJson("/api/orgs/" + seller.orgId() + "/cards/" + card.cardId() + "/offers", "", seller.who())
                .andExpect(status().isCreated()), "$.id");
        api.postJson(listings(seller, card), "{\"offerId\":\"" + fresh + "\"}", seller.who()).andExpect(status().isCreated());
    }

    @Test
    void anOfferWhosePriceMovedCantBeTakenAndComesOffTheMarket() throws Exception {
        Seller seller = market.seller();
        AtomicReference<String> catalog = new AtomicReference<>();
        Posted card = market.posted(seller, "Spiking Espeon", catalog::set);
        Buyer buyer = market.buyer();

        priceJumps(catalog.get());

        api.postJson(TestMarket.accept(buyer, card), "", buyer.who())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(OfferService.PRICE_CHANGED))
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
        api.get(sale(seller, card), seller.who())
                .andExpect(jsonPath("$.listing.status").value("VOIDED"))
                .andExpect(jsonPath("$.offer.voided.reason").value(OfferService.PRICE_CHANGED));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from listing_event where listing_id = ?::uuid and type = 'VOIDED' and note = ?", Integer.class,
                UUID.fromString(card.listingId()), OfferService.PRICE_CHANGED)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select v.repriced_now > v.repriced_was from card_offer_void v where v.offer_id = ?::uuid", Boolean.class,
                UUID.fromString(card.offerId()))).isTrue();
        api.get("/api/orgs/" + buyer.orgId() + "/market", buyer.who())
                .andExpect(jsonPath("$[?(@.listingId=='" + card.listingId() + "')]").isEmpty());

        Buyer late = market.buyer();
        api.postJson(TestMarket.accept(late, card), "", late.who())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This card is no longer for sale."));
    }

    @Test
    void anOfferWhosePriceHeldIsPostedAndTaken() throws Exception {
        Seller seller = market.seller();
        AtomicReference<String> catalog = new AtomicReference<>();
        Posted card = market.posted(seller, "Steady Vaporeon", catalog::set);
        // A refresh on the same sales: the price table is new, the price isn't.
        api.postJson("/api/admin/catalog-cards/" + catalog.get() + "/prices/refresh", "", api.admin()).andExpect(status().isOk());

        Buyer buyer = market.buyer();
        api.postJson(TestMarket.accept(buyer, card), "", buyer.who()).andExpect(status().isOk());
        api.get(sale(seller, card), seller.who())
                .andExpect(jsonPath("$.listing.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.offer.voided").value(nullValue()));
    }

    @Test
    void nobodyBuysTheirOwnCardEvenAfterThePriceMoved() throws Exception {
        String subject = "auth0|both-sides-" + UUID.randomUUID();
        Seller seller = market.seller(subject);
        Buyer sameLogin = market.buyer(subject);
        AtomicReference<String> catalog = new AtomicReference<>();
        Posted card = market.posted(seller, "Wash Trade Jolteon", catalog::set);

        priceJumps(catalog.get());

        // The self-dealing refusal comes first and leaves the listing as it was.
        api.postJson(TestMarket.accept(sameLogin, card), "", sameLogin.who())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You can't buy your own card."));
        api.get(sale(seller, card), seller.who())
                .andExpect(jsonPath("$.listing.status").value("OPEN"))
                .andExpect(jsonPath("$.offer.voided").value(nullValue()));
    }
}

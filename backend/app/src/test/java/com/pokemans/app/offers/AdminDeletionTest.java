package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.TestcontainersConfiguration;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The admin's hard delete of a test or junk card or org (admin.AdminDeletionService); here for the fake shipping provider. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class AdminDeletionTest {

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
    private TestMarket market;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
        market = new TestMarket(api);
    }

    private ResultActions deleteCard(String orgId, String cardId, String confirm, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(delete("/api/admin/orgs/" + orgId + "/cards/" + cardId).param("confirm", confirm).with(who));
    }

    private ResultActions deleteOrg(String orgId, String confirm, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(delete("/api/admin/orgs/" + orgId).param("confirm", confirm).with(who));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String orgName(String orgId) {
        return jdbc.queryForObject("select name from org where id = ?::uuid", String.class, orgId);
    }

    /** Every row in any table with a foreign key to one of these tables whose value is one of the ids. */
    private int leftPointingAt(String table, List<UUID> ids) {
        List<String[]> columns = jdbc.query("""
                select kcu.table_name, kcu.column_name from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu on kcu.constraint_name = tc.constraint_name
                join information_schema.constraint_column_usage cu on cu.constraint_name = tc.constraint_name
                where tc.constraint_type = 'FOREIGN KEY' and cu.table_name = ?""",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, table);
        assertThat(columns).isNotEmpty();
        int left = 0;
        for (String[] c : columns) {
            left += count("select count(*) from " + c[0] + " where " + c[1] + " = any (?)", (Object) ids.toArray(UUID[]::new));
        }
        return left;
    }

    @Test
    void aCardGoesWithEverythingAboutItAndItsPhotos() throws Exception {
        Seller seller = market.seller();
        Posted offered = market.offered(seller, "Junk Charizard");
        api.uploadPhoto(seller.orgId(), offered.cardId(), "FRONT", TestImages.goodFront(), seller.who()).andExpect(status().isOk());
        UUID card = UUID.fromString(offered.cardId());
        List<UUID> photos = jdbc.queryForList("select id from photo where card_item_id = ?", UUID.class, card);
        List<String> keys = jdbc.queryForList("select storage_key from photo where card_item_id = ?", String.class, card);
        assertThat(keys).isNotEmpty();
        assertThat(blobs.head(storage.photoBucket(), keys.getFirst())).isPresent();
        List<UUID> offers = jdbc.queryForList("select id from card_offer where card_item_id = ?", UUID.class, card);
        // The offer is live, so the card is held until it's voided.
        jdbc.update("insert into card_offer_void (id, offer_id, reason, voided_by, created_at) "
                + "select gen_random_uuid(), ?, 'test', (select id from app_user limit 1), now()", offers.getFirst());

        api.get("/api/admin/orgs/" + seller.orgId() + "/cards/" + card + "/deletion-preview", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Junk Charizard"))
                .andExpect(jsonPath("$.photos").value(photos.size()))
                .andExpect(jsonPath("$.rows.card_item").value(1))
                .andExpect(jsonPath("$.rows.card_offer").value(1))
                .andExpect(jsonPath("$.holds").isEmpty());
        assertThat(count("select count(*) from card_item where id = ?", card)).as("a preview deletes nothing").isEqualTo(1);

        deleteCard(seller.orgId(), offered.cardId(), "Junk Charizard", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.rows.photo").value(photos.size()))
                .andExpect(jsonPath("$.storageError").doesNotExist());

        assertThat(leftPointingAt("card_item", List.of(card))).isZero();
        assertThat(leftPointingAt("photo", photos)).isZero();
        assertThat(leftPointingAt("card_offer", offers)).isZero();
        assertThat(count("select count(*) from card_item where id = ?", card)).isZero();
        for (String key : keys) {
            assertThat(blobs.head(storage.photoBucket(), key)).as(key).isEmpty();
        }
        assertThat(count("select count(*) from org where id = ?::uuid", seller.orgId())).as("the org stays").isEqualTo(1);

        var audit = jdbc.queryForMap("select * from admin_deletion where target_id = ?", card);
        assertThat(audit.get("kind")).isEqualTo("CARD");
        assertThat(audit.get("target_name")).isEqualTo("Junk Charizard");
        assertThat(audit.get("deleted_by")).isNotNull();
        assertThat(audit.get("removed").toString()).contains("\"card_item\": 1");
        assertThat((Integer) audit.get("objects_deleted")).isPositive();
        assertThat(audit.get("storage_done_at")).isNotNull();
    }

    @Test
    void theNameMustBeTypedExactly() throws Exception {
        Seller seller = market.seller();
        String card = api.card(seller.orgId(), "Typo Pikachu", seller.who());
        deleteCard(seller.orgId(), card, "Typo Pikachuu", api.admin()).andExpect(status().isBadRequest());
        assertThat(count("select count(*) from card_item where id = ?::uuid", card)).isEqualTo(1);
        assertThat(count("select count(*) from admin_deletion where target_id = ?::uuid", card)).isZero();
    }

    @Test
    void onlyTheAdminDeletes() throws Exception {
        String subject = "auth0|member-" + UUID.randomUUID();
        Seller seller = market.seller(subject);
        String card = api.card(seller.orgId(), "Mine Squirtle", seller.who());
        JwtRequestPostProcessor member = api.user(subject, List.of());
        deleteCard(seller.orgId(), card, "Mine Squirtle", member).andExpect(status().isForbidden());
        deleteOrg(seller.orgId(), orgName(seller.orgId()), member).andExpect(status().isForbidden());
        api.get("/api/admin/orgs/" + seller.orgId() + "/deletion-preview", member).andExpect(status().isForbidden());
        assertThat(count("select count(*) from card_item where id = ?::uuid", card)).isEqualTo(1);
    }

    @Test
    void liveOffersListingsAndDealsHoldTheCard() throws Exception {
        Seller seller = market.seller();
        Posted offered = market.offered(seller, "Offered Mew");
        Posted listed = market.posted(seller, "Listed Mewtwo");
        Posted taken = market.posted(seller, "Taken Lugia");
        Buyer buyer = market.buyer();
        api.postJson(TestMarket.accept(buyer, taken), "", buyer.who()).andExpect(status().isOk());

        deleteCard(seller.orgId(), offered.cardId(), "Offered Mew", api.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("void the offer first")));
        deleteCard(seller.orgId(), listed.cardId(), "Listed Mewtwo", api.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("on the market")));
        api.get("/api/admin/orgs/" + seller.orgId() + "/cards/" + taken.cardId() + "/deletion-preview", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds[0].reason").value("DEAL_IN_PROGRESS"));
        deleteCard(seller.orgId(), taken.cardId(), "Taken Lugia", api.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("deal isn't closed")));
        deleteOrg(seller.orgId(), orgName(seller.orgId()), api.admin()).andExpect(status().isConflict());
        // The buyer took a card from another org, so it's held too.
        deleteOrg(buyer.orgId(), orgName(buyer.orgId()), api.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("delete that card first")));

        assertThat(count("select count(*) from card_item where org_id = ?::uuid", seller.orgId())).isEqualTo(3);
        assertThat(count("select count(*) from admin_deletion where org_id = ?::uuid", seller.orgId())).isZero();

        // Once the admin cancels the deal, the card goes with the deal, and then the buyer can go too.
        api.postJson("/api/admin/deals/" + taken.listingId() + "/cancel", "{\"reason\":\"test data\"}", api.admin()).andExpect(status().isOk());
        UUID listing = UUID.fromString(taken.listingId());
        deleteCard(seller.orgId(), taken.cardId(), "Taken Lugia", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.rows.sale_listing").value(1))
                .andExpect(jsonPath("$.plan.rows.shipping_label").value(1));
        assertThat(leftPointingAt("sale_listing", List.of(listing))).isZero();
        deleteOrg(buyer.orgId(), orgName(buyer.orgId()), api.admin()).andExpect(status().isOk());
    }

    @Test
    void anOrgGoesWithItsCardsMembershipsAndInvitesButNotItsPeople() throws Exception {
        String subject = "auth0|junk-" + UUID.randomUUID();
        Seller seller = market.seller(subject);
        String card = api.card(seller.orgId(), "Junk Eevee", seller.who());
        api.uploadPhoto(seller.orgId(), card, "FRONT", TestImages.goodFront(), seller.who()).andExpect(status().isOk());
        api.postJson("/api/admin/orgs/" + seller.orgId() + "/invites", "{}", api.admin()).andExpect(status().isCreated());
        List<String> keys = jdbc.queryForList("select storage_key from photo where card_item_id = ?::uuid", String.class, card);
        UUID org = UUID.fromString(seller.orgId());
        String name = orgName(seller.orgId());

        api.get("/api/admin/orgs/" + org + "/deletion-preview", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards").value(1))
                .andExpect(jsonPath("$.rows.membership").value(1))
                .andExpect(jsonPath("$.rows.invite").value(2));

        deleteOrg(seller.orgId(), name, api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.rows.org").value(1));

        assertThat(leftPointingAt("org", List.of(org))).isZero();
        assertThat(count("select count(*) from org where id = ?", org)).isZero();
        assertThat(count("select count(*) from app_user where subject = ?", subject)).as("the person stays").isEqualTo(1);
        for (String key : keys) {
            assertThat(blobs.head(storage.photoBucket(), key)).as(key).isEmpty();
        }
        var audit = jdbc.queryForMap("select * from admin_deletion where target_id = ?", org);
        assertThat(audit.get("kind")).isEqualTo("ORG");
        assertThat(audit.get("org_name")).isEqualTo(name);
    }
}

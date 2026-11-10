package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokemans.app.TestApi;
import com.pokemans.app.TestMarket;
import com.pokemans.app.TestMarket.Buyer;
import com.pokemans.app.TestMarket.Posted;
import com.pokemans.app.TestMarket.Seller;
import com.pokemans.app.identity.SecurityProperties;
import com.pokemans.app.intake.TestImages;
import com.pokemans.app.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The data-deletion procedure (docs/data-deletion.md, review 2026-11 #44 / D12): deploy/forget-org.sql run through psql,
 * as an admin runs it on the server.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeShippingProvider.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class ForgetOrgScriptTest {

    private static final Path SCRIPT = Path.of("../../deploy/forget-org.sql");

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

    private ExecResult forget(String orgId, boolean apply) throws Exception {
        String database = jdbc.queryForObject("select current_database()", String.class);
        ExecResult result = TestcontainersConfiguration.psql(database, SCRIPT, "org_id=" + orgId, "apply=" + apply);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void anOrgIsForgottenExceptTheDealABuyerReliesOn() throws Exception {
        String subject = "auth0|forget-" + UUID.randomUUID();
        Seller seller = market.seller(subject);
        Posted taken = market.posted(seller, "Taken Charizard");
        Buyer buyer = market.buyer();
        api.postJson(TestMarket.accept(buyer, taken), "", buyer.who()).andExpect(status().isOk());
        Posted listed = market.posted(seller, "Listed Blastoise");
        String photographed = api.card(seller.orgId(), "Photographed Venusaur", seller.who());
        api.uploadPhoto(seller.orgId(), photographed, "FRONT", TestImages.goodFront(), seller.who()).andExpect(status().isOk());
        String org = seller.orgId();
        int buyerRows = count("select count(*) from ship_address where org_id = ?::uuid", buyer.orgId());

        // A dry run says what it would do and changes nothing.
        ExecResult dry = forget(org, false);
        assertThat(dry.getStdout()).contains("Dry run", "orgs/" + org + "/cards/" + listed.cardId() + "/",
                "orgs/" + org + "/cards/" + photographed + "/", subject);
        assertThat(dry.getStdout()).doesNotContain("orgs/" + org + "/cards/" + taken.cardId() + "/");
        assertThat(count("select count(*) from card_item where org_id = ?::uuid", org)).isEqualTo(3);
        assertThat(count("select count(*) from app_user where subject = ?", subject)).isOne();

        ExecResult applied = forget(org, true);
        assertThat(applied.getStdout()).contains("Committed.");

        // Gone: the cards nobody took, with their photos, offers and listing.
        assertThat(count("select count(*) from card_item where id in (?::uuid, ?::uuid)", listed.cardId(), photographed)).isZero();
        assertThat(count("select count(*) from photo where card_item_id = ?::uuid", photographed)).isZero();
        assertThat(count("select count(*) from card_offer where id = ?::uuid", listed.offerId())).isZero();
        assertThat(count("select count(*) from sale_listing where id = ?::uuid", listed.listingId())).isZero();
        // Kept: the taken card's deal and both ends of its label.
        assertThat(count("select count(*) from card_item where id = ?::uuid", taken.cardId())).isOne();
        assertThat(count("select count(*) from shipping_label where listing_id = ?::uuid", taken.listingId())).isOne();
        assertThat(count("select count(*) from ship_address where org_id = ?::uuid", buyer.orgId())).isEqualTo(buyerRows);
        assertThat(count("select count(*) from ship_address where org_id = ?::uuid", org)).isOne();
        // Nobody can sign in to it, and nothing names the collector.
        assertThat(count("select count(*) from membership where org_id = ?::uuid", org)).isZero();
        assertThat(count("select count(*) from invite where org_id = ?::uuid", org)).isZero();
        assertThat(jdbc.queryForObject("select name from org where id = ?::uuid", String.class, org)).startsWith("Deleted org ");
        assertThat(jdbc.queryForList("select name from collection where org_id = ?::uuid", String.class, org))
                .containsOnly("Deleted on request");
        assertThat(count("select count(*) from app_user where subject = ?", subject)).isZero();
        assertThat(count("select count(*) from app_user where subject like 'deleted|%' and email is null")).isPositive();
        // The buyer's org is untouched.
        assertThat(count("select count(*) from membership where org_id = ?::uuid", buyer.orgId())).isOne();
    }

    @Test
    void anOrgWithNothingKeptIsDeletedOutright() throws Exception {
        String subject = "auth0|forget-" + UUID.randomUUID();
        Seller seller = market.seller(subject);
        market.posted(seller, "Listed Mew");

        forget(seller.orgId(), true);

        assertThat(count("select count(*) from org where id = ?::uuid", seller.orgId())).isZero();
        assertThat(count("select count(*) from consent_record where org_id = ?::uuid", seller.orgId())).isZero();
    }

    @Test
    void anUnknownOrgChangesNothing() throws Exception {
        ExecResult result = forget(UUID.randomUUID().toString(), true);
        assertThat(result.getStdout()).contains("No org with that id");
    }

    @Test
    void everyTableThatPointsAtACardOrAnOrgIsHandled() throws Exception {
        // A new table holding card or org data must be added to the script, or a request would leave it behind
        // (or fail on its foreign key mid-way).
        String script = Files.readString(SCRIPT);
        List<String> tables = jdbc.queryForList("""
                select distinct tc.table_name from information_schema.table_constraints tc
                join information_schema.constraint_column_usage cu on cu.constraint_name = tc.constraint_name
                where tc.constraint_type = 'FOREIGN KEY' and cu.table_name in ('card_item', 'org', 'photo', 'card_offer', 'sale_listing')
                """, String.class);
        assertThat(tables).isNotEmpty();
        for (String table : tables) {
            if (List.of("shipping_label", "label_refund").contains(table)) {
                // Only a taken card has these, and a taken card is kept: the script reads them, never deletes them.
                continue;
            }
            assertThat(script).as(table).containsPattern("(delete from|update) " + table + "\\b");
        }
        assertThat(script).contains("join shipping_label s on s.listing_id = l.id");
    }
}

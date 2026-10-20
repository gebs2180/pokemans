package com.pokemans.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Evidence tables refuse updates in the database itself, not only by convention in the code. */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AppendOnlyTablesTest {

    private static final List<String> APPEND_ONLY = List.of("sold_comp", "price_snapshot", "price_stats",
            "provider_prediction", "consent_record", "grade_outcome", "outcome_verification", "card_identification",
            "authenticity_verification", "authenticity_verification_photo", "custody_record", "valuation_run", "card_valuation",
            "report", "card_offer", "sale_listing", "listing_event", "ship_address", "shipping_label");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyEvidenceTableHasTheUpdateGuard() {
        List<String> guarded = jdbc.queryForList("""
                select c.relname from pg_trigger t
                join pg_class c on c.oid = t.tgrelid
                join pg_proc p on p.oid = t.tgfoid
                where p.proname = 'refuse_update' and not t.tgisinternal""", String.class);
        assertThat(guarded).containsAll(APPEND_ONLY);
    }
}

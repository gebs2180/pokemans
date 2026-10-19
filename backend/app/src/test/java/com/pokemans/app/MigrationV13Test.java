package com.pokemans.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V13 applies to a database that already has duplicate open prediction requests (as a local
 * database could): the older duplicates are closed with a reason, then the unique index goes on.
 * Runs the migrations into a schema of its own, stopping at V11 to plant the duplicates.
 */
@SpringBootTest(properties = "pokemans.condition.worker.enabled=false")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class MigrationV13Test {

    private static final String SCHEMA = "v13_upgrade";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration").target(target).load();
    }

    @Test
    void closesOlderDuplicateOpenRequestsBeforeAddingTheIndex() {
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
        flyway("11").migrate();

        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID collection = UUID.randomUUID();
        UUID card = UUID.randomUUID();
        jdbc.update("insert into " + SCHEMA + ".org (id, name, type, created_at) values (?, 'Org', 'COLLECTOR', now())", org);
        jdbc.update("insert into " + SCHEMA + ".app_user (id, subject, created_at) values (?, ?, now())", user, "auth0|" + user);
        jdbc.update("insert into " + SCHEMA + ".collection (id, org_id, name, created_by, created_at) values (?, ?, 'Binder', ?, now())",
                collection, org, user);
        jdbc.update("""
                insert into %s.card_item (id, org_id, collection_id, label, is_lot, quantity, created_by, created_at)
                values (?, ?, ?, 'Card', false, 1, ?, now())""".formatted(SCHEMA), card, org, collection, user);

        // Three open requests for one provider (oldest first), one open for another, one finished.
        UUID oldest = request(org, card, "XIMILAR", "PENDING", "3 hours");
        UUID older = request(org, card, "XIMILAR", "SUBMITTED", "2 hours");
        UUID newest = request(org, card, "XIMILAR", "PENDING", "1 hour");
        UUID other = request(org, card, "CARDGRADE", "SUBMITTED", "3 hours");
        UUID done = request(org, card, "XIMILAR", "DONE", "4 hours");

        flyway("latest").migrate();

        assertThat(status(newest)).containsEntry("status", "PENDING").containsEntry("last_error", null);
        assertThat(status(other)).containsEntry("status", "SUBMITTED");
        assertThat(status(done)).containsEntry("status", "DONE");
        for (UUID closed : List.of(oldest, older)) {
            assertThat(status(closed)).containsEntry("status", "FAILED");
            assertThat((String) status(closed).get("last_error")).contains("V13").contains("newer open request");
            assertThat(status(closed).get("finished_at")).isNotNull();
        }
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname = ? and indexname = 'prediction_request_open_idx'",
                Integer.class, SCHEMA)).isEqualTo(1);

        jdbc.execute("drop schema " + SCHEMA + " cascade");
    }

    private UUID request(UUID org, UUID card, String provider, String status, String age) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into %s.prediction_request (id, org_id, card_item_id, provider, status, photo_ids, next_attempt_at, created_at)
                values (?, ?, ?, ?, ?, '[]'::jsonb, now(), now() - ?::interval)""".formatted(SCHEMA), id, org, card, provider, status, age);
        return id;
    }

    private Map<String, Object> status(UUID id) {
        return jdbc.queryForMap("select status, last_error, finished_at from " + SCHEMA + ".prediction_request where id = ?", id);
    }
}

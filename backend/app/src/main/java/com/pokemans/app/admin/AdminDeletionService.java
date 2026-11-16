package com.pokemans.app.admin;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.offers.TradeHolds;
import com.pokemans.app.offers.TradeHolds.Hold;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The admin's hard delete of a card or a whole org, for test and junk data (2026-11-15). "Store everything" holds for
 * real data, so this is narrow: admin only (by path), confirmed by typing the name, refused while a live offer, a
 * listing or an unclosed deal is in play ({@link TradeHolds}), and audited in {@code admin_deletion}, which keeps no
 * foreign key to what it deleted.
 *
 * <p>The rows go through {@code forget_cards()}, the same path as a data-deletion request (deploy/forget-org.sql), in
 * one transaction. The photo objects go after it commits: a failure there leaves the rows gone and the audit row saying
 * which folders are left, rather than rows pointing at missing photos. Users are never deleted, here or in Auth0: an
 * org's memberships go, its people's accounts stay.
 */
@Service
public class AdminDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AdminDeletionService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public enum Kind { CARD, ORG }

    /**
     * What a delete removes, or would: rows per table, the photo folders and report files, and what holds it, if
     * anything. A preview is the delete itself, rolled back, so its counts are the ones the delete will have.
     */
    public record Plan(Kind kind, UUID id, String name, UUID orgId, String orgName, Map<String, Long> rows, int photos,
                       int cards, List<String> storage, List<Hold> holds) {
    }

    /** A done delete, with how the photo bucket went. */
    public record Done(UUID deletionId, Plan plan, int objectsDeleted, String storageError) {
    }

    private record Target(Kind kind, UUID id, String name, UUID orgId, String orgName, List<UUID> cards) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TradeHolds holds;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final CurrentUser currentUser;

    AdminDeletionService(JdbcTemplate jdbc, TransactionTemplate transactions, TradeHolds holds, BlobStore blobs, StorageProperties storage,
                         CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.holds = holds;
        this.blobs = blobs;
        this.storage = storage;
        this.currentUser = currentUser;
    }

    public Plan previewCard(UUID orgId, UUID cardId) {
        return preview(() -> card(orgId, cardId));
    }

    public Plan previewOrg(UUID orgId) {
        return preview(() -> org(orgId));
    }

    public Done deleteCard(UUID orgId, UUID cardId, String confirm) {
        return delete(() -> card(orgId, cardId), confirm);
    }

    public Done deleteOrg(UUID orgId, String confirm) {
        return delete(() -> org(orgId), confirm);
    }

    private Plan preview(java.util.function.Supplier<Target> find) {
        return transactions.execute(tx -> {
            Plan plan = run(find.get());
            tx.setRollbackOnly();
            return plan;
        });
    }

    private Done delete(java.util.function.Supplier<Target> find, String confirm) {
        UUID deletionId = UUID.randomUUID();
        Plan plan = transactions.execute(tx -> {
            Target target = find.get();
            if (confirm == null || !confirm.strip().equals(target.name().strip())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Type the " + (target.kind() == Kind.CARD ? "card's" : "org's") + " name exactly to confirm: " + target.name());
            }
            Plan done = run(target);
            if (!done.holds().isEmpty()) {
                tx.setRollbackOnly();
                return done;
            }
            jdbc.update("""
                    insert into admin_deletion (id, kind, target_id, target_name, org_id, org_name, deleted_by, removed, storage_prefixes,
                                                created_at)
                    values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)""",
                    deletionId, done.kind().name(), done.id(), done.name(), done.orgId(), done.orgName(), currentUser.id(),
                    JSON.writeValueAsString(done.rows()), JSON.writeValueAsString(done.storage()), Timestamp.from(Instant.now()));
            return done;
        });
        if (!plan.holds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, refusal(plan));
        }
        log.info("Admin {} hard-deleted {} {} ({}): {}", currentUser.id(), plan.kind(), plan.id(), plan.name(), plan.rows());

        int objects = 0;
        String error = null;
        for (String key : plan.storage()) {
            try {
                if (key.endsWith("/")) {
                    objects += blobs.deletePrefix(storage.photoBucket(), key);
                } else {
                    blobs.delete(storage.photoBucket(), key);
                    objects++;
                }
            } catch (RuntimeException e) {
                log.error("Hard delete {}: couldn't delete {} from the photo bucket", deletionId, key, e);
                error = (error == null ? "" : error + "; ") + key + ": " + e.getMessage();
            }
        }
        jdbc.update("update admin_deletion set objects_deleted = ?, storage_error = ?, storage_done_at = ? where id = ?",
                objects, error, Timestamp.from(Instant.now()), deletionId);
        return new Done(deletionId, plan, objects, error);
    }

    private static String refusal(Plan plan) {
        StringBuilder s = new StringBuilder("Nothing was deleted: ")
                .append(plan.kind() == Kind.CARD ? "this card has" : "this org has")
                .append(" a trade still in play.");
        plan.holds().stream().map(Hold::message).distinct().limit(5).forEach(m -> s.append(' ').append(m));
        return s.toString();
    }

    /** Locks, checks what holds the target, and deletes it inside the caller's transaction. Deletes nothing if anything holds it. */
    private Plan run(Target target) {
        holds.lock(target.cards());
        List<Hold> found = new ArrayList<>(holds.onCards(target.cards()));
        if (target.kind() == Kind.ORG) {
            found.addAll(holds.asBuyer(target.id()));
        }
        Integer photos = target.cards().isEmpty() ? 0 : jdbc.queryForObject("select count(*) from photo where card_item_id = any (?)",
                Integer.class, (Object) target.cards().toArray(UUID[]::new));
        List<String> keys = storageKeys(target);
        if (!found.isEmpty()) {
            return new Plan(target.kind(), target.id(), target.name(), target.orgId(), target.orgName(), Map.of(), photos,
                    target.cards().size(), keys, found);
        }
        Map<String, Long> rows = new LinkedHashMap<>();
        jdbc.query("select deleted_from, deleted_rows from forget_cards(?)", rs -> {
            rows.merge(rs.getString(1), rs.getLong(2), Long::sum);
        }, (Object) target.cards().toArray(UUID[]::new));
        if (target.kind() == Kind.ORG) {
            deleteOrgRows(target.id(), rows);
        }
        rows.values().removeIf(n -> n == 0);
        return new Plan(target.kind(), target.id(), target.name(), target.orgId(), target.orgName(), rows, photos, target.cards().size(),
                keys, List.of());
    }

    /** The org's own rows once its cards are gone, children first. Its people's accounts stay; only their memberships go. */
    private void deleteOrgRows(UUID orgId, Map<String, Long> rows) {
        String[][] steps = {
                {"card_valuation", "delete from card_valuation where org_id = ?"},
                {"report", "delete from report where org_id = ?"},
                {"valuation_run", "delete from valuation_run where org_id = ?"},
                {"custody_record", "delete from custody_record where org_id = ?"},
                {"collection", "delete from collection where org_id = ?"},
                {"submission_batch", "delete from submission_batch where org_id = ?"},
                {"ship_address", "delete from ship_address where org_id = ?"},
                {"consent_record", "delete from consent_record where org_id = ?"},
                {"invite", "delete from invite where org_id = ?"},
                {"membership", "delete from membership where org_id = ?"},
                {"org", "delete from org where id = ?"},
        };
        for (String[] step : steps) {
            rows.merge(step[0], (long) jdbc.update(step[1], orgId), Long::sum);
        }
    }

    /** The card folders (every photo, crop, view and exact copy) and, for an org, its report files. */
    private List<String> storageKeys(Target target) {
        Set<String> keys = new LinkedHashSet<>();
        for (UUID card : target.cards()) {
            keys.add("orgs/" + target.orgId() + "/cards/" + card + "/");
        }
        if (!target.cards().isEmpty()) {
            // A photo's own folder, in case one was ever filed under another prefix.
            keys.addAll(jdbc.queryForList("""
                    select distinct regexp_replace(storage_key, '[^/]+$', '') from photo where card_item_id = any (?)""",
                    String.class, (Object) target.cards().toArray(UUID[]::new)));
        }
        if (target.kind() == Kind.ORG) {
            keys.addAll(jdbc.queryForList("select storage_key from report where org_id = ? order by storage_key", String.class, target.id()));
        }
        keys.removeIf(k -> k.isBlank() || k.equals("/"));
        return List.copyOf(keys);
    }

    private Target card(UUID orgId, UUID cardId) {
        List<Target> found = jdbc.query("""
                select coalesce(c.label, 'New card'), o.name from card_item c join org o on o.id = c.org_id where c.id = ? and c.org_id = ? for update of c""",
                (rs, i) -> new Target(Kind.CARD, cardId, rs.getString(1), orgId, rs.getString(2), List.of(cardId)), cardId, orgId);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such card in this org.");
        }
        return found.getFirst();
    }

    private Target org(UUID orgId) {
        List<String> name = jdbc.queryForList("select name from org where id = ? for update", String.class, orgId);
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such org.");
        }
        List<UUID> cards = jdbc.queryForList("select id from card_item where org_id = ? order by id", UUID.class, orgId);
        return new Target(Kind.ORG, orgId, name.getFirst(), orgId, name.getFirst(), cards);
    }
}

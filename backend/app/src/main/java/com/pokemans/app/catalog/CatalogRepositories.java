package com.pokemans.app.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface CatalogCardRepository extends JpaRepository<CatalogCard, UUID> {

    Optional<CatalogCard> findByIdentityKey(String identityKey);
}

interface CardIdentificationRepository extends JpaRepository<CardIdentification, UUID> {

    List<CardIdentification> findByCardItemIdOrderByCreatedAtDesc(UUID cardItemId);

    List<CardIdentification> findByCardItemIdInAndStatusOrderByCreatedAtDesc(Collection<UUID> cardItemIds,
                                                                            CardIdentification.Status status);

    List<CardIdentification> findByReadingIdOrderByConfidenceDesc(UUID readingId);

    /** Catalog cards that are some physical card's current confirmed identity. */
    @Query(nativeQuery = true, value = """
            select distinct catalog_card_id from (
                select distinct on (card_item_id) catalog_card_id from card_identification
                where status = 'CONFIRMED'
                order by card_item_id, created_at desc) current_identity""")
    List<UUID> findCurrentCatalogCardIds();
}

interface CardReadingRepository extends JpaRepository<CardReading, UUID> {

    /**
     * Locks up to {@code limit} pending readings that are due, oldest first, skipping rows another
     * worker has locked. Call in a transaction that then leases them with {@link #lease}.
     */
    @Query(nativeQuery = true, value = """
            select id from card_reading
            where status = 'PENDING' and next_attempt_at <= :now
            order by next_attempt_at
            limit :limit
            for update skip locked""")
    List<UUID> lockDue(Instant now, int limit);

    /** Locks one pending reading if it is due and no other worker holds it; as {@link #lockDue}. */
    @Query(nativeQuery = true, value = """
            select id from card_reading
            where id = :id and status = 'PENDING' and next_attempt_at <= :now
            for update skip locked""")
    Optional<UUID> lockDueById(UUID id, Instant now);

    /** Moves the next attempt to {@code until}: no other worker sees these as due until then. */
    @Modifying
    @Query("update CardReading r set r.nextAttemptAt = :until where r.id in :ids")
    int lease(Collection<UUID> ids, Instant until);

    List<CardReading> findByCardItemIdOrderByCreatedAtDesc(UUID cardItemId);

    boolean existsByCardItemId(UUID cardItemId);

    /** Takes a Postgres advisory lock until the transaction ends; false if another transaction holds it. */
    @Query(nativeQuery = true, value = "select pg_try_advisory_xact_lock(:key)")
    boolean tryTransactionLock(long key);
}

package com.pokemans.app.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CatalogCardRepository extends JpaRepository<CatalogCard, UUID> {

    Optional<CatalogCard> findByIdentityKey(String identityKey);
}

interface CardIdentificationRepository extends JpaRepository<CardIdentification, UUID> {

    List<CardIdentification> findByCardItemIdOrderByCreatedAtDesc(UUID cardItemId);

    /** Latest confirmed identification per card, for the given cards. */
    @Query(nativeQuery = true, value = """
            select distinct on (card_item_id) * from card_identification
            where card_item_id in (:cardItemIds) and status = 'CONFIRMED'
            order by card_item_id, created_at desc""")
    List<CardIdentification> findCurrent(Collection<UUID> cardItemIds);

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

    @Query("""
            select r from CardReading r
            where r.status = com.pokemans.app.catalog.CardReading.Status.PENDING and r.nextAttemptAt <= :now
            order by r.nextAttemptAt""")
    List<CardReading> findDue(Instant now);

    List<CardReading> findByCardItemIdOrderByCreatedAtDesc(UUID cardItemId);
}

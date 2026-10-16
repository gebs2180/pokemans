package com.pokemans.app.catalog;

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
}

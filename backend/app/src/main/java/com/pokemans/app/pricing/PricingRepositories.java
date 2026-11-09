package com.pokemans.app.pricing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SoldCompRepository extends JpaRepository<SoldComp, UUID> {

    boolean existsBySourceAndExternalSaleId(String source, String externalSaleId);

    boolean existsBySourceAndExternalSaleIdStartingWith(String source, String prefix);

    List<SoldComp> findBySoldOnBetween(LocalDate from, LocalDate to);

    List<SoldComp> findByCatalogCardIdOrderBySoldOnDescCreatedAtDesc(UUID catalogCardId);

    List<SoldComp> findByCatalogCardIdAndRawConditionAndSoldOnGreaterThanEqual(UUID catalogCardId, PriceSource.RawCondition rawCondition,
                                                                             LocalDate since);

    @Query("select distinct c.catalogCardId from SoldComp c where c.soldOn between :from and :to")
    List<UUID> findCatalogCardIdsSoldBetween(LocalDate from, LocalDate to);
}

interface PriceSnapshotRepository extends JpaRepository<PriceSnapshot, UUID> {

    List<PriceSnapshot> findBySnapshotSetId(UUID snapshotSetId);

    /** The newest snapshot row for a card and grading company; its set is that company's current price table. */
    Optional<PriceSnapshot> findFirstByCatalogCardIdAndGradingCompanyOrderByAsOfDescCreatedAtDesc(
            UUID catalogCardId, PriceSource.GradingCompany gradingCompany);

    @Query("select distinct s.gradingCompany from PriceSnapshot s where s.catalogCardId = :catalogCardId")
    List<PriceSource.GradingCompany> findGradingCompaniesByCatalogCardId(UUID catalogCardId);
}

interface PriceStatsRepository extends JpaRepository<PriceStats, UUID> {

    Optional<PriceStats> findBySnapshotSetId(UUID snapshotSetId);
}

interface CompIngestRunRepository extends JpaRepository<CompIngestRun, UUID> {
}

interface PokeTraceSuggestionRepository extends JpaRepository<PokeTraceSuggestion, UUID> {

    Optional<PokeTraceSuggestion> findFirstByCatalogCardIdOrderByCreatedAtDesc(UUID catalogCardId);

    boolean existsByCatalogCardId(UUID catalogCardId);

    /** The newest suggestion per catalog card. */
    @Query(nativeQuery = true, value = """
            select distinct on (catalog_card_id) * from poketrace_suggestion
            order by catalog_card_id, created_at desc, id desc""")
    List<PokeTraceSuggestion> findLatestPerCard();
}

interface PokeTraceCardRepository extends JpaRepository<PokeTraceCard, UUID> {

    /** The newest mapping row for the card; it counts, whether it maps or unmaps. */
    Optional<PokeTraceCard> findFirstByCatalogCardIdOrderByCreatedAtDesc(UUID catalogCardId);
}

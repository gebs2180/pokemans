package com.pokemans.app.valuation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ValuationRunRepository extends JpaRepository<ValuationRun, UUID> {

    List<ValuationRun> findByOrgIdAndCollectionIdOrderByCreatedAtDesc(UUID orgId, UUID collectionId);
}

interface CardValuationRecordRepository extends JpaRepository<CardValuationRecord, UUID> {

    List<CardValuationRecord> findByRunIdOrderByPositionAsc(UUID runId);
}

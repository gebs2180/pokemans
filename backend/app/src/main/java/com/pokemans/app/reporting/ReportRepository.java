package com.pokemans.app.reporting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReportRepository extends JpaRepository<Report, UUID> {

    List<Report> findByOrgIdAndCollectionIdOrderByIssuedAtDesc(UUID orgId, UUID collectionId);

    List<Report> findByRunIdOrderByIssuedAtDesc(UUID runId);

    Optional<Report> findByIdAndOrgId(UUID id, UUID orgId);
}

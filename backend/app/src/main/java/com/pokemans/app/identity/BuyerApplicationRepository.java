package com.pokemans.app.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface BuyerApplicationRepository extends JpaRepository<BuyerApplication, UUID> {

    Optional<BuyerApplication> findFirstByOrgIdOrderByAttemptDesc(UUID orgId);

    Optional<BuyerApplication> findFirstByUserIdOrderByAttemptDesc(UUID userId);

    /** Requests nobody has answered yet, oldest first. */
    @Query("select a from BuyerApplication a where not exists (select 1 from BuyerDecision d where d.applicationId = a.id) order by a.createdAt")
    List<BuyerApplication> findUndecided();

    /** The last requests answered, newest answer first, for the admin's record. */
    @Query("select a from BuyerApplication a, BuyerDecision d where d.applicationId = a.id order by d.decidedAt desc limit 50")
    List<BuyerApplication> findRecentlyDecided();
}

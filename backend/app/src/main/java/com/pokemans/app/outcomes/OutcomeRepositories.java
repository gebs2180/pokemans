package com.pokemans.app.outcomes;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface GradeOutcomeRepository extends JpaRepository<GradeOutcome, UUID> {

    List<GradeOutcome> findByCardItemIdOrderByCreatedAtDesc(UUID cardItemId);

    List<GradeOutcome> findByCardItemIdInOrderByCreatedAtDesc(Collection<UUID> cardItemIds);

    List<GradeOutcome> findAllByOrderByCreatedAtAsc();

    boolean existsByGraderAndCertNumber(Grader grader, String certNumber);
}

interface OutcomeVerificationRepository extends JpaRepository<OutcomeVerification, UUID> {

    List<OutcomeVerification> findByOutcomeIdInOrderByCreatedAtDesc(Collection<UUID> outcomeIds);
}

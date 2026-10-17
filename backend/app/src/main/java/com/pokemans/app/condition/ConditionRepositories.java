package com.pokemans.app.condition;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PredictionRequestRepository extends JpaRepository<PredictionRequest, UUID> {

    /** Open requests whose next attempt is due, oldest first. */
    @Query("""
            select r from PredictionRequest r
            where r.status in (com.pokemans.app.condition.PredictionRequest.Status.PENDING,
                               com.pokemans.app.condition.PredictionRequest.Status.SUBMITTED)
              and r.nextAttemptAt <= :now
            order by r.nextAttemptAt""")
    List<PredictionRequest> findDue(Instant now);

    List<PredictionRequest> findByCardItemIdOrderByCreatedAtDesc(UUID cardItemId);

    List<PredictionRequest> findByCardItemIdIn(Collection<UUID> cardItemIds);
}

interface ProviderPredictionRepository extends JpaRepository<ProviderPrediction, UUID> {

    List<ProviderPrediction> findByCardItemIdOrderByCreatedAtDesc(UUID cardItemId);
}

interface ConditionAssessmentRepository extends JpaRepository<ConditionAssessment, UUID> {

    Optional<ConditionAssessment> findByCardItemIdAndIsCurrentTrue(UUID cardItemId);

    List<ConditionAssessment> findByCardItemIdOrderByVersionDesc(UUID cardItemId);

    List<ConditionAssessment> findByCardItemIdInAndIsCurrentTrue(Collection<UUID> cardItemIds);
}

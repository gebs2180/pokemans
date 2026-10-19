package com.pokemans.app.condition;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PredictionRequestRepository extends JpaRepository<PredictionRequest, UUID> {

    /**
     * Locks up to {@code limit} open requests that are due, oldest first, skipping rows another
     * worker has locked. Call in a transaction that then leases them with {@link #lease}.
     */
    @Query(nativeQuery = true, value = """
            select id from prediction_request
            where status in ('PENDING', 'SUBMITTED') and next_attempt_at <= :now
            order by next_attempt_at
            limit :limit
            for update skip locked""")
    List<UUID> lockDue(Instant now, int limit);

    /** Moves the next attempt to {@code until}: no other worker sees these as due until then. */
    @Modifying
    @Query("update PredictionRequest r set r.nextAttemptAt = :until where r.id in :ids")
    int lease(Collection<UUID> ids, Instant until);

    boolean existsByCardItemId(UUID cardItemId);

    /** Takes a Postgres advisory lock until the transaction ends; false if another transaction holds it. */
    @Query(nativeQuery = true, value = "select pg_try_advisory_xact_lock(:key)")
    boolean tryTransactionLock(long key);

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

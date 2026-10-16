package com.pokemans.app.intake;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every lookup is scoped by org id, so a wrong id from another org finds nothing. */
interface SubmissionBatchRepository extends JpaRepository<SubmissionBatch, UUID> {

    List<SubmissionBatch> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    Optional<SubmissionBatch> findByIdAndOrgId(UUID id, UUID orgId);
}

interface CardCollectionRepository extends JpaRepository<CardCollection, UUID> {

    List<CardCollection> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    Optional<CardCollection> findByIdAndOrgId(UUID id, UUID orgId);
}

interface CardItemRepository extends JpaRepository<CardItem, UUID> {

    List<CardItem> findByBatchIdOrderByCreatedAtAsc(UUID batchId);

    List<CardItem> findByCollectionIdOrderByCreatedAtAsc(UUID collectionId);

    Optional<CardItem> findByIdAndOrgId(UUID id, UUID orgId);

    long countByBatchId(UUID batchId);

    long countByCollectionId(UUID collectionId);
}

interface PhotoRepository extends JpaRepository<Photo, UUID> {

    List<Photo> findByCardItemIdInOrderByCreatedAtAsc(List<UUID> cardItemIds);

    List<Photo> findByCardItemIdOrderByCreatedAtAsc(UUID cardItemId);

    Optional<Photo> findByIdAndOrgId(UUID id, UUID orgId);

    boolean existsByOrgIdAndSha256AndCardItemIdNot(UUID orgId, String sha256, UUID cardItemId);
}

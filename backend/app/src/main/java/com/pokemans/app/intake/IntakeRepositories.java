package com.pokemans.app.intake;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    List<CardItem> findByOrgIdAndCollectionIdNotNullOrderByCreatedAtAsc(UUID orgId);

    Optional<CardItem> findByIdAndOrgId(UUID id, UUID orgId);

    /** Row-locked: serialises photo confirms on one card, so finishing its capture is seen exactly once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CardItem> findLockedByIdAndOrgId(UUID id, UUID orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CardItem> findLockedById(UUID id);

    long countByBatchId(UUID batchId);

    long countByCollectionId(UUID collectionId);

    /** Cards with a passing front, and a passing back unless they are a lot. */
    @Query(nativeQuery = true, value = """
            select c.* from card_item c
            where exists (select 1 from photo p where p.card_item_id = c.id and p.side = 'FRONT' and p.status = 'PASSED')
              and (c.is_lot or exists (select 1 from photo p where p.card_item_id = c.id and p.side = 'BACK' and p.status = 'PASSED'))
            order by c.created_at""")
    List<CardItem> findCaptureComplete();

    /** Capture-complete cards whose newest passing photo was checked in {@code [since, before)}. */
    @Query(nativeQuery = true, value = """
            select c.* from card_item c
            where exists (select 1 from photo p where p.card_item_id = c.id and p.side = 'FRONT' and p.status = 'PASSED')
              and (c.is_lot or exists (select 1 from photo p where p.card_item_id = c.id and p.side = 'BACK' and p.status = 'PASSED'))
              and (select max(p.checked_at) from photo p where p.card_item_id = c.id and p.status = 'PASSED') >= :since
              and (select max(p.checked_at) from photo p where p.card_item_id = c.id and p.status = 'PASSED') < :before
            order by c.created_at""")
    List<CardItem> findCaptureCompletedBetween(Instant since, Instant before);
}

interface PhotoRepository extends JpaRepository<Photo, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Photo> findLockedByIdAndOrgId(UUID id, UUID orgId);

    List<Photo> findByCardItemIdInOrderByCreatedAtAsc(List<UUID> cardItemIds);

    List<Photo> findByCardItemIdOrderByCreatedAtAsc(UUID cardItemId);

    Optional<Photo> findByIdAndOrgId(UUID id, UUID orgId);

    long countByCardItemId(UUID cardItemId);

    long countByOrgIdAndCreatedAtAfter(UUID orgId, Instant since);

    boolean existsByOrgIdAndSha256AndCardItemIdNot(UUID orgId, String sha256, UUID cardItemId);

    /** The same image on another side of the same card: the front uploaded again as the back. */
    boolean existsByCardItemIdAndSha256AndSideNot(UUID cardItemId, String sha256, Photo.Side side);

    /** A photo's card and {@link ImageHash}, for finding lookalikes. */
    interface HashedPhoto {
        UUID getCardItemId();

        String getDhash();
    }

    /** The hashes of one side's photos on the org's other cards; compared in Java (a few thousand rows at most). */
    @Query("select p.cardItemId as cardItemId, p.dhash as dhash from Photo p where p.orgId = :orgId and p.side = :side"
            + " and p.cardItemId <> :cardItemId and p.dhash is not null")
    List<HashedPhoto> findHashes(UUID orgId, Photo.Side side, UUID cardItemId);

    /** Uploaded originals not yet checked for location metadata, oldest first (the one-off backfill). */
    @Query("select p.id from Photo p where p.status <> com.pokemans.app.intake.Photo.Status.AWAITING_UPLOAD"
            + " and p.locationCheckedAt is null order by p.createdAt")
    List<UUID> findIdsWithLocationUnchecked(org.springframework.data.domain.Limit limit);

    long countByStatusNotAndLocationCheckedAtIsNull(Photo.Status status);

    /**
     * Photos with a card found but no wide crop or no surface crop yet, oldest first (made before those were kept).
     * Bounded to what the backfill can make: a photo it fails on stays in the list and the next run tries it again.
     */
    @Query("select p.id from Photo p where p.cropKey is not null and p.cardCorners is not null"
            + " and (p.wideCropKey is null or p.surfaceKey is null) order by p.createdAt")
    List<UUID> findIdsWithoutWideCrop(org.springframework.data.domain.Limit limit);

    @Query("select count(p) from Photo p where p.cropKey is not null and p.cardCorners is not null"
            + " and (p.wideCropKey is null or p.surfaceKey is null)")
    long countWithoutWideCrop();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Photo p where p.id = :id")
    Optional<Photo> findLockedById(UUID id);

    /** Passed BACK photos with a surface crop not yet checked against reference {@code version}, oldest first. */
    @Query("select p.id from Photo p where p.side = com.pokemans.app.intake.Photo.Side.BACK"
            + " and p.status = com.pokemans.app.intake.Photo.Status.PASSED and p.surfaceKey is not null"
            + " and (p.cleanBackReferenceVersion is null or p.cleanBackReferenceVersion < :version) order by p.createdAt")
    List<UUID> findIdsWithoutCleanBack(int version, org.springframework.data.domain.Limit limit);

    @Query("select count(p) from Photo p where p.side = com.pokemans.app.intake.Photo.Side.BACK"
            + " and p.status = com.pokemans.app.intake.Photo.Status.PASSED and p.surfaceKey is not null"
            + " and (p.cleanBackReferenceVersion is null or p.cleanBackReferenceVersion < :version)")
    long countWithoutCleanBack(int version);

    /**
     * Candidates for an automatic reference back: passed BACK photos with a surface crop, the sharpest and least glary
     * first. The service keeps one per card.
     */
    @Query("select p from Photo p where p.side = com.pokemans.app.intake.Photo.Side.BACK"
            + " and p.status = com.pokemans.app.intake.Photo.Status.PASSED and p.surfaceKey is not null"
            + " order by coalesce(p.cardGlareFraction, 1) asc, coalesce(p.cardBlurScore, 0) desc, p.createdAt asc")
    List<Photo> findReferenceCandidates(org.springframework.data.domain.Limit limit);
}

interface BackReferenceRepository extends JpaRepository<BackReference, Integer> {

    Optional<BackReference> findFirstByOrderByVersionDesc();
}

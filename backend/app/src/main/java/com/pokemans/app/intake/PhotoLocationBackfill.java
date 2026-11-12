package com.pokemans.app.intake;

import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-off, run by the admin after the deploy that started removing location at upload (privacy review 2026-11-12):
 * rewrites the originals uploaded before it without their location and device metadata, keeping pixels, orientation
 * and capture time ({@link ExactCopy#withoutLocation}). Each photo is checked in its own transaction, logged, and
 * recorded on its row ({@code location_checked_at}, and the stored object's hash and size when rewritten), so a run
 * can stop anywhere and the next one carries on. Backups taken before this still hold the old originals until they
 * expire.
 */
@Service
class PhotoLocationBackfill {

    private static final Logger log = LoggerFactory.getLogger(PhotoLocationBackfill.class);

    /**
     * @param checked   photos looked at in this run
     * @param rewritten originals rewritten without their location
     * @param clean     originals that had nothing to remove (or that we can't copy, logged)
     * @param missing   photos whose object isn't in the bucket (logged): recorded as checked, there being nothing to strip
     * @param failed    photos that failed (logged): left unchecked, so the next run tries again
     * @param remaining uploaded photos still unchecked after this run
     */
    record Result(int checked, int rewritten, int clean, int missing, int failed, long remaining) {
    }

    private final PhotoRepository photos;
    private final PhotoService photoService;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final TransactionTemplate transaction;

    PhotoLocationBackfill(PhotoRepository photos, PhotoService photoService, BlobStore blobs, StorageProperties storage,
                          PlatformTransactionManager transactions) {
        this.photos = photos;
        this.photoService = photoService;
        this.blobs = blobs;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactions);
    }

    Result run(int limit) {
        List<UUID> ids = photos.findIdsWithLocationUnchecked(Limit.of(Math.max(1, Math.min(limit, 1000))));
        int rewritten = 0;
        int clean = 0;
        int missing = 0;
        int failed = 0;
        for (UUID id : ids) {
            try {
                Outcome outcome = transaction.execute(status -> one(id));
                switch (outcome) {
                    case REWRITTEN -> rewritten++;
                    case CLEAN -> clean++;
                    case MISSING -> missing++;
                    case SKIPPED -> { }
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Location backfill: photo {} failed", id, e);
            }
        }
        long remaining = photos.countByStatusNotAndLocationCheckedAtIsNull(Photo.Status.AWAITING_UPLOAD);
        Result result = new Result(ids.size(), rewritten, clean, missing, failed, remaining);
        log.info("Location backfill: {}", result);
        return result;
    }

    private enum Outcome { REWRITTEN, CLEAN, MISSING, SKIPPED }

    private Outcome one(UUID id) {
        Photo photo = photos.findLockedById(id).orElse(null);
        if (photo == null || photo.getLocationCheckedAt() != null) {
            return Outcome.SKIPPED;
        }
        if (blobs.head(storage.photoBucket(), photo.getStorageKey()).isEmpty()) {
            log.warn("Location backfill: photo {} has no object at {}", id, photo.getStorageKey());
            photo.locationChecked(null, null);
            return Outcome.MISSING;
        }
        byte[] bytes = blobs.read(storage.photoBucket(), photo.getStorageKey());
        boolean rewritten = photoService.removeLocation(photo, bytes);
        log.info("Location backfill: photo {} {}", id, rewritten ? "rewritten without location" : "had nothing to remove");
        return rewritten ? Outcome.REWRITTEN : Outcome.CLEAN;
    }
}

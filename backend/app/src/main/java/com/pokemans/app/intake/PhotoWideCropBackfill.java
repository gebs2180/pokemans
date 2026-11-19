package com.pokemans.app.intake;

import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.ImageGuard;
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
 * One-off, run by the admin after the deploy that started keeping wide crops (2026-11-18): makes the wide card crop
 * ({@link CardDetector#wideCrop}) and the full-resolution surface crop with its local-contrast version
 * ({@link CardDetector#surface}, since 2026-11-18) for photos checked before, from the stored original and the corners found then. Until a
 * photo has one, the surface view shows its tight crop. Then makes clean backs ({@link CleanBackService}) for BACK
 * photos not yet checked against the latest reference back: run it again after each reference rebuild. Each photo runs
 * in its own transaction, so a run can stop anywhere and the next one carries on.
 */
@Service
class PhotoWideCropBackfill {

    private static final Logger log = LoggerFactory.getLogger(PhotoWideCropBackfill.class);

    /**
     * @param checked          photos looked at for wide crops in this run
     * @param made             wide crops made
     * @param failed           photos that failed or whose original is missing or doesn't decode (logged): the next run tries again
     * @param remaining        photos still without a wide crop after this run
     * @param referenceVersion the reference back clean backs are made against; null when none is built yet
     * @param cleanChecked     BACK photos checked against it in this run
     * @param cleanMade        clean backs made
     * @param cleanRefused     backs that didn't align well enough (another design, blur): recorded with why, no image
     * @param cleanFailed      backs that failed or whose surface crop is missing (logged): the next run tries again
     * @param cleanRemaining   BACK photos with a surface crop not yet checked against it
     */
    record Result(int checked, int made, int failed, long remaining, Integer referenceVersion, int cleanChecked, int cleanMade,
                  int cleanRefused, int cleanFailed, long cleanRemaining) {
    }

    private final PhotoRepository photos;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final CleanBackService cleanBacks;
    private final TransactionTemplate transaction;

    PhotoWideCropBackfill(PhotoRepository photos, BlobStore blobs, StorageProperties storage, CleanBackService cleanBacks,
                          PlatformTransactionManager transactions) {
        this.photos = photos;
        this.blobs = blobs;
        this.storage = storage;
        this.cleanBacks = cleanBacks;
        this.transaction = new TransactionTemplate(transactions);
    }

    Result run(int limit) {
        Limit bounded = Limit.of(Math.max(1, Math.min(limit, 1000)));
        List<UUID> ids = photos.findIdsWithoutWideCrop(bounded);
        int made = 0;
        int failed = 0;
        for (UUID id : ids) {
            try {
                Boolean ok = transaction.execute(status -> one(id));
                if (Boolean.TRUE.equals(ok)) {
                    made++;
                } else {
                    failed++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Wide crop backfill: photo {} failed", id, e);
            }
        }

        Integer version = cleanBacks.currentVersion();
        int cleanMade = 0;
        int cleanRefused = 0;
        int cleanFailed = 0;
        List<UUID> backs = version == null ? List.of() : photos.findIdsWithoutCleanBack(version, bounded);
        for (UUID id : backs) {
            try {
                Boolean ok = transaction.execute(status -> clean(id, version));
                if (ok == null) {
                    cleanFailed++;
                } else if (ok) {
                    cleanMade++;
                } else {
                    cleanRefused++;
                }
            } catch (RuntimeException e) {
                cleanFailed++;
                log.warn("Clean back backfill: photo {} failed", id, e);
            }
        }
        long cleanRemaining = photos.countWithoutCleanBack(version == null ? Integer.MAX_VALUE : version);
        Result result = new Result(ids.size(), made, failed, photos.countWithoutWideCrop(), version, backs.size(), cleanMade,
                cleanRefused, cleanFailed, cleanRemaining);
        log.info("Wide crop backfill: {}", result);
        return result;
    }

    /** True if a clean back was made, false if refused (recorded), null if it failed and should be tried again. */
    private Boolean clean(UUID id, int version) {
        Photo photo = photos.findLockedById(id).orElse(null);
        if (photo == null || photo.getCleanBackReferenceVersion() != null && photo.getCleanBackReferenceVersion() >= version) {
            return photo != null && photo.getSurfaceCleanKey() != null;
        }
        Boolean made = cleanBacks.makeFromStored(photo);
        if (made == null) {
            log.warn("Clean back backfill: photo {} has no surface crop at {}", id, photo.getSurfaceKey());
        }
        return made;
    }

    private boolean one(UUID id) {
        Photo photo = photos.findLockedById(id).orElse(null);
        if (photo == null || photo.getWideCropKey() != null && photo.getSurfaceKey() != null) {
            return true;
        }
        if (blobs.head(storage.photoBucket(), photo.getStorageKey()).isEmpty()) {
            log.warn("Wide crop backfill: photo {} has no object at {}", id, photo.getStorageKey());
            return false;
        }
        byte[] bytes = blobs.read(storage.photoBucket(), photo.getStorageKey());
        boolean decodable = ImageGuard.safeHeader(bytes).isPresent();
        if (photo.getWideCropKey() == null) {
            byte[] wide = decodable ? CardDetector.wideCrop(bytes, photo.getCardCorners()) : null;
            if (wide == null) {
                log.warn("Wide crop backfill: photo {} doesn't decode", id);
                return false;
            }
            String key = photo.wideCropStorageKey();
            blobs.putIfAbsent(storage.photoBucket(), key, wide, "image/jpeg");
            photo.wideCropStored(key);
        }
        if (photo.getSurfaceKey() == null) {
            CardDetector.Surface surface = decodable ? CardDetector.surface(bytes, photo.getCardCorners()) : null;
            if (surface == null) {
                log.warn("Wide crop backfill: photo {} doesn't decode for the surface crop", id);
                return false;
            }
            blobs.putIfAbsent(storage.photoBucket(), photo.surfaceStorageKey(), surface.hiResJpeg(), "image/jpeg");
            blobs.putIfAbsent(storage.photoBucket(), photo.surfaceDetailStorageKey(), surface.detailJpeg(), "image/jpeg");
            photo.surfaceStored(photo.surfaceStorageKey(), photo.surfaceDetailStorageKey());
        }
        return true;
    }
}

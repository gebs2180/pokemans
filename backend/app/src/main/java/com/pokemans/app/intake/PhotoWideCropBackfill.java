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
 * ({@link CardDetector#wideCrop}) for photos checked before, from the stored original and the corners found then. Until a
 * photo has one, the surface view shows its tight crop. Each photo runs in its own transaction, so a run can stop
 * anywhere and the next one carries on.
 */
@Service
class PhotoWideCropBackfill {

    private static final Logger log = LoggerFactory.getLogger(PhotoWideCropBackfill.class);

    /**
     * @param checked   photos looked at in this run
     * @param made      wide crops made
     * @param failed    photos that failed or whose original is missing or doesn't decode (logged): the next run tries again
     * @param remaining photos still without a wide crop after this run
     */
    record Result(int checked, int made, int failed, long remaining) {
    }

    private final PhotoRepository photos;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final TransactionTemplate transaction;

    PhotoWideCropBackfill(PhotoRepository photos, BlobStore blobs, StorageProperties storage, PlatformTransactionManager transactions) {
        this.photos = photos;
        this.blobs = blobs;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactions);
    }

    Result run(int limit) {
        List<UUID> ids = photos.findIdsWithoutWideCrop(Limit.of(Math.max(1, Math.min(limit, 1000))));
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
        Result result = new Result(ids.size(), made, failed, photos.countByCropKeyIsNotNullAndCardCornersIsNotNullAndWideCropKeyIsNull());
        log.info("Wide crop backfill: {}", result);
        return result;
    }

    private boolean one(UUID id) {
        Photo photo = photos.findLockedById(id).orElse(null);
        if (photo == null || photo.getWideCropKey() != null) {
            return true;
        }
        if (blobs.head(storage.photoBucket(), photo.getStorageKey()).isEmpty()) {
            log.warn("Wide crop backfill: photo {} has no object at {}", id, photo.getStorageKey());
            return false;
        }
        byte[] bytes = blobs.read(storage.photoBucket(), photo.getStorageKey());
        byte[] wide = ImageGuard.safeHeader(bytes).isEmpty() ? null : CardDetector.wideCrop(bytes, photo.getCardCorners());
        if (wide == null) {
            log.warn("Wide crop backfill: photo {} doesn't decode", id);
            return false;
        }
        String key = photo.wideCropStorageKey();
        blobs.putIfAbsent(storage.photoBucket(), key, wide, "image/jpeg");
        photo.wideCropStored(key);
        return true;
    }
}

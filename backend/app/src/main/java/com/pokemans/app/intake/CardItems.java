package com.pokemans.app.intake;

import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What other modules may know about cards and their photos: read-only, and without the intake
 * entities. Org access is checked by {@link #requireAccessible}; the other lookups are for admin
 * and background work, where the caller has already established who may see what.
 */
@Service
public class CardItems {

    /** The longest card name kept, as a typed one is limited to. */
    public static final int MAX_LABEL = 300;

    /** A card item as other modules see it. */
    public record CardItemRef(UUID id, UUID orgId, String label, boolean lot, int quantity, Instant createdAt) {
    }

    /**
     * A passing photo.
     *
     * @param side      FRONT, BACK or DETAIL
     * @param cropKey   the card crop next to it, or null
     * @param wideCropKey the card crop with a little background past each edge, or null
     * @param surfaceKey the full-resolution wide crop, or null
     * @param surfaceDetailKey its local-contrast version, or null
     */
    public record PhotoRef(UUID id, UUID cardItemId, String side, String storageKey, String contentType, String cropKey,
                           Instant capturedAt, Instant uploadedAt, String sha256, String wideCropKey, String surfaceKey,
                           String surfaceDetailKey, String surfaceCleanKey, UUID lookalikeCardId) {

        public PhotoRef(UUID id, UUID cardItemId, String side, String storageKey, String contentType, String cropKey,
                        Instant capturedAt, Instant uploadedAt, String sha256, String wideCropKey, String surfaceKey,
                        String surfaceDetailKey, String surfaceCleanKey) {
            this(id, cardItemId, side, storageKey, contentType, cropKey, capturedAt, uploadedAt, sha256, wideCropKey, surfaceKey,
                    surfaceDetailKey, surfaceCleanKey, null);
        }

        /** A photo without a surface crop (made before they were kept). */
        public PhotoRef(UUID id, UUID cardItemId, String side, String storageKey, String contentType, String cropKey,
                        Instant capturedAt, Instant uploadedAt, String sha256, String wideCropKey) {
            this(id, cardItemId, side, storageKey, contentType, cropKey, capturedAt, uploadedAt, sha256, wideCropKey, null, null, null);
        }
    }

    /**
     * The stored object to show someone other than the photo's owner (a buyer): the card crop, else the
     * copy made without the upload's metadata. Never the upload itself, whose EXIF can say where the
     * seller lives. Both are written when a photo passes.
     */
    public static String shownKey(String storageKey, String cropKey) {
        return cropKey != null ? cropKey : Photo.viewStorageKey(storageKey);
    }

    /**
     * The stored object a buyer opens full size: the photo exactly as uploaded, without its metadata. Written when a photo
     * passes, and by {@link #ensureExactCopy} for photos that passed before these copies were kept.
     */
    public static String exactKey(String storageKey) {
        return Photo.exactStorageKey(storageKey);
    }

    /** A collection as other modules see it. */
    public record CollectionRef(UUID id, UUID orgId, String name) {
    }

    /** Published when a card's required photos have all passed (front and back, or the front of a lot). */
    public record CaptureCompleted(UUID orgId, UUID cardItemId) {
    }

    /** Published when a passing front or back replaces one on a card whose capture was already complete. */
    public record PhotoRetaken(UUID orgId, UUID cardItemId) {
    }

    private final IntakeService intake;
    private final CardItemRepository cards;
    private final PhotoRepository photos;
    private final CardCollectionRepository collections;
    private final BlobStore blobs;
    private final StorageProperties storage;

    CardItems(IntakeService intake, CardItemRepository cards, PhotoRepository photos, CardCollectionRepository collections,
              BlobStore blobs, StorageProperties storage) {
        this.intake = intake;
        this.cards = cards;
        this.photos = photos;
        this.collections = collections;
        this.blobs = blobs;
        this.storage = storage;
    }

    /** Writes the photo's {@link #exactKey exact copy} if it isn't stored yet. */
    public void ensureExactCopy(String storageKey, String contentType) {
        if (blobs.head(storage.photoBucket(), exactKey(storageKey)).isEmpty()) {
            storeExactCopy(blobs, storage.photoBucket(), storageKey, contentType, blobs.read(storage.photoBucket(), storageKey));
        }
    }

    /**
     * Stores the exact copy of an upload. One that can't be copied as is (it never should, past the image guard) gets a
     * re-encoded copy instead, so the key always holds a photo without the upload's metadata.
     */
    static void storeExactCopy(BlobStore blobs, String bucket, String storageKey, String contentType, byte[] upload) {
        byte[] exact = ExactCopy.of(upload);
        if (exact != null) {
            blobs.putIfAbsent(bucket, exactKey(storageKey), exact, contentType);
            return;
        }
        byte[] reencoded = CardDetector.withoutMetadata(upload);
        if (reencoded != null) {
            blobs.putIfAbsent(bucket, exactKey(storageKey), reencoded, "image/jpeg");
        }
    }

    /** The org's collection, else 404. No membership check: the caller has done it (or is admin). */
    @Transactional(readOnly = true)
    public CollectionRef requireCollection(UUID orgId, UUID collectionId) {
        return collections.findByIdAndOrgId(collectionId, orgId)
                .map(c -> new CollectionRef(c.getId(), c.getOrgId(), c.getName()))
                .orElseThrow(() -> IntakeService.notFound("Collection"));
    }

    /** The collection's cards, oldest first. No access check. */
    @Transactional(readOnly = true)
    public List<CardItemRef> inCollection(UUID collectionId) {
        return cards.findByCollectionIdAndArchivedAtIsNullOrderByCreatedAtAsc(collectionId).stream().map(CardItems::ref).toList();
    }

    /** The cards in all of the org's collections, oldest first. No access check. */
    @Transactional(readOnly = true)
    public List<CardItemRef> inCollectionsOf(UUID orgId) {
        return cards.findByOrgIdAndCollectionIdNotNullAndArchivedAtIsNullOrderByCreatedAtAsc(orgId).stream().map(CardItems::ref).toList();
    }

    /** The card if the caller may see its org, else 404. */
    @Transactional(readOnly = true)
    public CardItemRef requireAccessible(UUID orgId, UUID cardId) {
        return ref(intake.requireCard(orgId, cardId));
    }

    /** No access check: for admin and background callers. */
    @Transactional(readOnly = true)
    public CardItemRef get(UUID cardId) {
        return cards.findById(cardId).map(CardItems::ref).orElseThrow(() -> IntakeService.notFound("Card"));
    }

    /**
     * Names a card that has no name yet; a card already named keeps its name, so a later identification never renames
     * what the collector called it. Lots are never renamed. No access check. True if the card took the name.
     */
    @Transactional
    public boolean nameIfUnnamed(UUID cardId, String label) {
        CardItem card = cards.findById(cardId).orElseThrow(() -> IntakeService.notFound("Card"));
        if (card.isNamed() || card.isLot() || label == null || label.isBlank()) {
            return false;
        }
        card.name(trimmed(label));
        return true;
    }

    /**
     * Names a card from what its collector says it is, replacing an earlier name. Lots are never renamed. No access
     * check: the caller has established that the collector may change this card.
     */
    @Transactional
    public void rename(UUID cardId, String label) {
        CardItem card = cards.findById(cardId).orElseThrow(() -> IntakeService.notFound("Card"));
        if (!card.isLot() && label != null && !label.isBlank()) {
            card.name(trimmed(label));
        }
    }

    /**
     * Takes the card off its collector's list and the review queue. Its photos and predictions stay. No access check:
     * the caller has established that the collector may, and that no offer or listing depends on the card.
     */
    @Transactional
    public void archive(UUID cardId, UUID by) {
        CardItem card = cards.findLockedById(cardId).orElseThrow(() -> IntakeService.notFound("Card"));
        if (!card.isArchived()) {
            card.archive(by);
        }
    }

    /** A label as card_item keeps it: trimmed, and at most the 300 characters a typed one may have. */
    private static String trimmed(String label) {
        String t = label.trim();
        return t.length() > MAX_LABEL ? t.substring(0, MAX_LABEL) : t;
    }

    /** Passing photos of a card, oldest first. No access check. */
    @Transactional(readOnly = true)
    public List<PhotoRef> passedPhotos(UUID cardId) {
        return photos.findByCardItemIdOrderByCreatedAtAsc(cardId).stream()
                .filter(p -> p.getStatus() == Photo.Status.PASSED)
                .map(CardItems::ref)
                .toList();
    }

    /** Front and back, if every try so far failed the checks: the seller has to take them again. No access check. */
    @Transactional(readOnly = true)
    public List<String> sidesToRetake(UUID cardId) {
        List<Photo> attempts = photos.findByCardItemIdOrderByCreatedAtAsc(cardId);
        return Stream.of(Photo.Side.FRONT, Photo.Side.BACK)
                .filter(side -> attempts.stream().noneMatch(p -> p.getSide() == side && p.getStatus() == Photo.Status.PASSED))
                .filter(side -> attempts.stream().anyMatch(p -> p.getSide() == side && p.getStatus() == Photo.Status.RETAKE))
                .map(Photo.Side::name)
                .toList();
    }

    private static PhotoRef ref(Photo p) {
        return new PhotoRef(p.getId(), p.getCardItemId(), p.getSide().name(), p.getStorageKey(), p.getContentType(),
                p.getCropKey(), p.getCapturedAt(), p.getUploadedAt(), p.getSha256(), p.getWideCropKey(),
                p.getSurfaceKey(), p.getSurfaceDetailKey(), p.getSurfaceCleanKey(), p.getLookalikeCardId());
    }

    /**
     * Each side's newest passing photo, front, back then close-up: what buyers and reviewers are shown.
     * Earlier passing photos are kept, but superseded. No access check.
     */
    @Transactional(readOnly = true)
    public List<PhotoRef> currentPhotos(UUID cardId) {
        return IntakeViews.current(photos.findByCardItemIdOrderByCreatedAtAsc(cardId)).stream().map(CardItems::ref).toList();
    }

    /**
     * Takes the card's row lock until the transaction ends: the lock photo confirms hold, so a caller
     * that freezes the photos (posting it for sale) can't interleave with one. No access check.
     */
    @Transactional
    public void lockForCapture(UUID cardId) {
        cards.findLockedById(cardId).orElseThrow(() -> IntakeService.notFound("Card"));
    }

    /** Who added the card. No access check. */
    @Transactional(readOnly = true)
    public UUID addedBy(UUID cardId) {
        return cards.findById(cardId).map(CardItem::getCreatedBy).orElseThrow(() -> IntakeService.notFound("Card"));
    }

    /** Every card, across orgs, whose capture is complete, oldest first. No access check. */
    @Transactional(readOnly = true)
    public List<CardItemRef> captureComplete() {
        return cards.findCaptureComplete().stream().map(CardItems::ref).toList();
    }

    /**
     * Cards whose capture completed (as far as photo check times tell) in {@code [since, before)},
     * oldest first. For sweeps that catch a lost {@link CaptureCompleted}. No access check.
     */
    @Transactional(readOnly = true)
    public List<CardItemRef> captureCompletedBetween(Instant since, Instant before) {
        return cards.findCaptureCompletedBetween(since, before).stream().map(CardItems::ref).toList();
    }

    private static CardItemRef ref(CardItem c) {
        return new CardItemRef(c.getId(), c.getOrgId(), c.getLabel(), c.isLot(), c.getQuantity(), c.getCreatedAt());
    }
}

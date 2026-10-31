package com.pokemans.app.intake;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What other modules may know about cards and their photos: read-only, and without the intake
 * entities. Org access is checked by {@link #requireAccessible}; the other lookups are for admin
 * and background work, where the caller has already established who may see what.
 */
@Service
public class CardItems {

    /** A card item as other modules see it. */
    public record CardItemRef(UUID id, UUID orgId, String label, boolean lot, int quantity, Instant createdAt) {
    }

    /**
     * A passing photo.
     *
     * @param side      FRONT, BACK or DETAIL
     * @param cropKey   the card crop next to it, or null
     */
    public record PhotoRef(UUID id, UUID cardItemId, String side, String storageKey, String contentType, String cropKey,
                           Instant capturedAt, Instant uploadedAt, String sha256) {
    }

    /**
     * The stored object to show someone other than the photo's owner (a buyer): the card crop, else the
     * copy made without the upload's metadata. Never the upload itself, whose EXIF can say where the
     * seller lives. Both are written when a photo passes.
     */
    public static String shownKey(String storageKey, String cropKey) {
        return cropKey != null ? cropKey : Photo.viewStorageKey(storageKey);
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

    CardItems(IntakeService intake, CardItemRepository cards, PhotoRepository photos, CardCollectionRepository collections) {
        this.intake = intake;
        this.cards = cards;
        this.photos = photos;
        this.collections = collections;
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
        return cards.findByCollectionIdOrderByCreatedAtAsc(collectionId).stream().map(CardItems::ref).toList();
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

    /** Passing photos of a card, oldest first. No access check. */
    @Transactional(readOnly = true)
    public List<PhotoRef> passedPhotos(UUID cardId) {
        return photos.findByCardItemIdOrderByCreatedAtAsc(cardId).stream()
                .filter(p -> p.getStatus() == Photo.Status.PASSED)
                .map(CardItems::ref)
                .toList();
    }

    private static PhotoRef ref(Photo p) {
        return new PhotoRef(p.getId(), p.getCardItemId(), p.getSide().name(), p.getStorageKey(), p.getContentType(),
                p.getCropKey(), p.getCapturedAt(), p.getUploadedAt(), p.getSha256());
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

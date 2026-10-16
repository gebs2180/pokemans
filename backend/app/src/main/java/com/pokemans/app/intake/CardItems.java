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
                           Instant capturedAt, Instant uploadedAt) {
    }

    /** Published when a card's required photos have all passed (front and back, or the front of a lot). */
    public record CaptureCompleted(UUID orgId, UUID cardItemId) {
    }

    private final IntakeService intake;
    private final CardItemRepository cards;
    private final PhotoRepository photos;

    CardItems(IntakeService intake, CardItemRepository cards, PhotoRepository photos) {
        this.intake = intake;
        this.cards = cards;
        this.photos = photos;
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
                .map(p -> new PhotoRef(p.getId(), p.getCardItemId(), p.getSide().name(), p.getStorageKey(), p.getContentType(),
                        p.getCropKey(), p.getCapturedAt(), p.getUploadedAt()))
                .toList();
    }

    /** Every card, across orgs, whose capture is complete, oldest first. No access check. */
    @Transactional(readOnly = true)
    public List<CardItemRef> captureComplete() {
        return cards.findCaptureComplete().stream().map(CardItems::ref).toList();
    }

    private static CardItemRef ref(CardItem c) {
        return new CardItemRef(c.getId(), c.getOrgId(), c.getLabel(), c.isLot(), c.getQuantity(), c.getCreatedAt());
    }
}

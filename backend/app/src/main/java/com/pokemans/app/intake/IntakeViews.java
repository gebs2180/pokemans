package com.pokemans.app.intake;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** JSON shapes of the intake API. */
final class IntakeViews {

    private IntakeViews() {
    }

    /** A batch (partner) or collection (collector). */
    record ContainerSummary(UUID id, String name, Instant createdAt, long cards) {
    }

    record ContainerDetail(UUID id, String name, Instant createdAt, List<CardView> cards) {
    }

    /**
     * @param captureComplete true once the required sides have a passing photo (front and back for
     *                        a single card, front for a lot)
     * @param missingSides    required sides without a passing photo yet
     * @param photosFrozen    why the photos can't change any more (the card is posted or sold), or null
     */
    record CardView(UUID id, String label, boolean lot, int quantity, boolean captureComplete,
                    List<Photo.Side> missingSides, List<PhotoView> photos, String photosFrozen) {
    }

    /**
     * @param card where the whole card was found and how it measured, or null (no card found, a lot,
     *             a detail close-up, or checked before card detection existed)
     * @param current true for the side's newest passing photo: the one buyers and reviewers see
     * @param url     a short-lived link to the card crop (else the photo) for a thumbnail; null in lists
     */
    record PhotoView(UUID id, Photo.Side side, Photo.Status status, List<ReasonView> retakeReasons,
                     Integer width, Integer height, Double blurScore, Double glareFraction, CardFit card,
                     Instant capturedAt, Instant uploadedAt, boolean current, URI url) {
    }

    record CardFit(double coverage, double aspect, double blurScore, double glareFraction) {
    }

    record ReasonView(RetakeReason code, String message) {
    }

    record UploadSlot(UUID photoId, URI url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    static PhotoView photo(Photo p) {
        return photo(p, false, null);
    }

    static PhotoView photo(Photo p, boolean current, URI url) {
        List<ReasonView> reasons = p.getRetakeReasons().stream().map(r -> new ReasonView(r, r.message())).toList();
        CardFit card = p.getCardCoverage() == null ? null
                : new CardFit(p.getCardCoverage(), p.getCardAspect(), p.getCardBlurScore(), p.getCardGlareFraction());
        return new PhotoView(p.getId(), p.getSide(), p.getStatus(), reasons, p.getWidth(), p.getHeight(),
                p.getBlurScore(), p.getGlareFraction(), card, p.getCapturedAt(), p.getUploadedAt(), current, url);
    }

    static CardView card(CardItem card, List<Photo> photos) {
        return card(card, photos, p -> null, null);
    }

    /**
     * @param link   a thumbnail link for an uploaded photo, or null
     * @param frozen why the photos can't change any more, or null
     */
    static CardView card(CardItem card, List<Photo> photos, Function<Photo, URI> link, String frozen) {
        List<Photo.Side> required = card.isLot() ? List.of(Photo.Side.FRONT) : List.of(Photo.Side.FRONT, Photo.Side.BACK);
        List<Photo.Side> missing = required.stream()
                .filter(side -> photos.stream().noneMatch(p -> p.getSide() == side && p.getStatus() == Photo.Status.PASSED))
                .toList();
        List<Photo> current = current(photos);
        return new CardView(card.getId(), card.getLabel(), card.isLot(), card.getQuantity(), missing.isEmpty(), missing,
                photos.stream().map(p -> photo(p, current.contains(p), p.getStatus() == Photo.Status.AWAITING_UPLOAD ? null : link.apply(p)))
                        .toList(), frozen);
    }

    /** Each side's newest passing photo, front, back then close-up: what buyers and reviewers are shown. */
    static List<Photo> current(List<Photo> oldestFirst) {
        Map<Photo.Side, Photo> latest = new java.util.EnumMap<>(Photo.Side.class);
        oldestFirst.stream().filter(p -> p.getStatus() == Photo.Status.PASSED).forEach(p -> latest.put(p.getSide(), p));
        return List.copyOf(latest.values());
    }
}

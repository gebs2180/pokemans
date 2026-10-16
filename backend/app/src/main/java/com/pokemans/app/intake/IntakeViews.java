package com.pokemans.app.intake;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
     */
    record CardView(UUID id, String label, boolean lot, int quantity, boolean captureComplete,
                    List<Photo.Side> missingSides, List<PhotoView> photos) {
    }

    record PhotoView(UUID id, Photo.Side side, Photo.Status status, List<ReasonView> retakeReasons,
                     Integer width, Integer height, Double blurScore, Double glareFraction,
                     Instant capturedAt, Instant uploadedAt) {
    }

    record ReasonView(RetakeReason code, String message) {
    }

    record UploadSlot(UUID photoId, URI url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    static PhotoView photo(Photo p) {
        List<ReasonView> reasons = p.getRetakeReasons().stream().map(r -> new ReasonView(r, r.message())).toList();
        return new PhotoView(p.getId(), p.getSide(), p.getStatus(), reasons, p.getWidth(), p.getHeight(),
                p.getBlurScore(), p.getGlareFraction(), p.getCapturedAt(), p.getUploadedAt());
    }

    static CardView card(CardItem card, List<Photo> photos) {
        List<Photo.Side> required = card.isLot() ? List.of(Photo.Side.FRONT) : List.of(Photo.Side.FRONT, Photo.Side.BACK);
        List<Photo.Side> missing = required.stream()
                .filter(side -> photos.stream().noneMatch(p -> p.getSide() == side && p.getStatus() == Photo.Status.PASSED))
                .toList();
        return new CardView(card.getId(), card.getLabel(), card.isLot(), card.getQuantity(), missing.isEmpty(), missing,
                photos.stream().map(IntakeViews::photo).toList());
    }
}

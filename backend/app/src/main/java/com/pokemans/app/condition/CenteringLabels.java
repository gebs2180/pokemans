package com.pokemans.app.condition;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.intake.CardOutline;
import com.pokemans.app.intake.QualityProperties;
import com.pokemans.app.storage.BlobStore;
import com.pokemans.app.storage.StorageProperties;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hand labels of the printed frame, for scoring the centering measurer. A label is drawn on the same
 * straightened card the measurer reads, so a width means the same thing to both.
 */
@Service
class CenteringLabels {

    /** How hard the contrast copy raises local contrast: faint frames show at this, foil isn't blown out. */
    private static final double CONTRAST_LIMIT = 4;
    private static final Size CONTRAST_TILES = new Size(16, 22);
    private static final int JPEG_QUALITY = 92;
    private static final String DATA_URI = "data:image/jpeg;base64,";

    /**
     * One photo straightened for labelling, as data URIs.
     *
     * @param contrast the same card with local contrast raised, for faint frames
     * @param refused why it couldn't be straightened: the images are null then
     */
    record Straightened(UUID photoId, int pxPerMm, int width, int height, String image, String contrast, String refused) {
    }

    /**
     * The measurer's reading of one photo, in mm like a label: shown only after the label is saved.
     *
     * @param refused why it gave no reading, null when it did; the widths are null then
     */
    record SystemReading(Double left, Double right, Double top, Double bottom, String refused) {
    }

    /**
     * What the queue knows about one face.
     *
     * @param refused true when the latest centering measurement refused it, null if it never ran. Only
     *                this and not the numbers: lines that start on the measurer's answer would bias the label
     */
    record FaceStatus(CenteringLabel label, Boolean refused) {
    }

    private final CenteringLabelRepository labels;
    private final ProviderPredictionRepository predictions;
    private final CardItems cardItems;
    private final CurrentUser currentUser;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final CardOutline outline;

    CenteringLabels(CenteringLabelRepository labels, ProviderPredictionRepository predictions, CardItems cardItems,
                    CurrentUser currentUser, BlobStore blobs, StorageProperties storage, QualityProperties quality) {
        this.labels = labels;
        this.predictions = predictions;
        this.cardItems = cardItems;
        this.currentUser = currentUser;
        this.blobs = blobs;
        this.storage = storage;
        this.outline = new CardOutline(quality);
    }

    /** The photos a face can be labelled on: what the measurer reads, so passing fronts or backs. */
    static List<CardItems.PhotoRef> faces(List<CardItems.PhotoRef> photos, CenteringLabel.Face face) {
        return photos.stream().filter(p -> p.side().equals(face.name())).toList();
    }

    /** No transaction: reading the photo and straightening it hold no connection. */
    Straightened straighten(UUID cardId, UUID photoId) {
        CardItems.PhotoRef photo = requireFacePhoto(cardId, photoId);
        Mat[] card = new Mat[1];
        CenteringMeasurer measurer = new CenteringMeasurer(outline, m -> card[0] = m.clone());
        CenteringMeasurer.Result result = measurer.measure(blobs.read(storage.photoBucket(), photo.storageKey()));
        if (card[0] == null) {
            String reason = result instanceof CenteringMeasurer.Unmeasured u ? u.reason() : "the card wasn't straightened";
            return new Straightened(photo.id(), CenteringMeasurer.PX_PER_MM, CenteringMeasurer.CARD_WIDTH, CenteringMeasurer.CARD_HEIGHT,
                    null, null, reason);
        }
        try {
            return new Straightened(photo.id(), CenteringMeasurer.PX_PER_MM, card[0].cols(), card[0].rows(), dataUri(card[0]),
                    dataUri(raisedContrast(card[0])), null);
        } finally {
            card[0].release();
        }
    }

    /**
     * What the measurer reads on a photo now, for the reveal after a blind label. Measured fresh rather than
     * read from the stored prediction, which may be of other photos. No transaction, like straightening.
     */
    SystemReading measure(UUID cardId, UUID photoId) {
        CardItems.PhotoRef photo = requireFacePhoto(cardId, photoId);
        CenteringMeasurer.Result result = new CenteringMeasurer(outline)
                .measure(blobs.read(storage.photoBucket(), photo.storageKey()));
        if (result instanceof CenteringMeasurer.Measured m) {
            double px = CenteringMeasurer.PX_PER_MM;
            return new SystemReading(m.left() / px, m.right() / px, m.top() / px, m.bottom() / px, null);
        }
        return new SystemReading(null, null, null, null, ((CenteringMeasurer.Unmeasured) result).reason());
    }

    @Transactional
    CenteringLabel save(CardItems.CardItemRef card, CenteringLabel.Face face, UUID photoId, Double left, Double right, Double top,
                        Double bottom, boolean hardCard) {
        CardItems.PhotoRef photo = requireFacePhoto(card.id(), photoId);
        if (!photo.side().equals(face.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That photo isn't of the card's " + face.name().toLowerCase());
        }
        return labels.save(new CenteringLabel(card.orgId(), card.id(), face, photo.id(), left, right, top, bottom, hardCard, currentUser.id()));
    }

    /** Each card's newest label per face, and whether the latest measurement refused it. */
    @Transactional(readOnly = true)
    Map<UUID, Map<CenteringLabel.Face, FaceStatus>> statuses(Collection<UUID> cardIds) {
        if (cardIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CenteringLabel>> byCard = labels.findByCardItemIdInOrderByCreatedAtDesc(cardIds).stream()
                .collect(Collectors.groupingBy(CenteringLabel::getCardItemId));
        Map<UUID, ProviderPrediction> measured = predictions
                .findByCardItemIdInAndProviderOrderByCreatedAtDesc(cardIds, CenteringPreGradeProvider.NAME).stream()
                .collect(Collectors.toMap(ProviderPrediction::getCardItemId, p -> p, (newer, older) -> newer));
        return cardIds.stream().collect(Collectors.toMap(id -> id, id -> Stream.of(CenteringLabel.Face.values())
                .collect(Collectors.toMap(face -> face, face -> new FaceStatus(
                        byCard.getOrDefault(id, List.of()).stream().filter(l -> l.getFace() == face).findFirst().orElse(null),
                        refused(measured.get(id), face))))));
    }

    private static Boolean refused(ProviderPrediction prediction, CenteringLabel.Face face) {
        if (prediction == null) {
            return null;
        }
        // A refusal of both faces is stored as a failed answer.
        if (!prediction.isSucceeded()) {
            return true;
        }
        Object side = prediction.getDetails().get(face == CenteringLabel.Face.FRONT ? "frontCentering" : "backCentering");
        return side instanceof Map<?, ?> m ? m.containsKey("unmeasured") : null;
    }

    private CardItems.PhotoRef requireFacePhoto(UUID cardId, UUID photoId) {
        return cardItems.passedPhotos(cardId).stream()
                .filter(p -> p.id().equals(photoId) && !p.side().equals("DETAIL")).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"));
    }

    /** Local contrast on lightness only, so the frame's colour stays what it is. */
    private static Mat raisedContrast(Mat bgr) {
        Mat lab = new Mat();
        List<Mat> channels = new ArrayList<>();
        try {
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab);
            Core.split(lab, channels);
            CLAHE clahe = Imgproc.createCLAHE(CONTRAST_LIMIT, CONTRAST_TILES);
            clahe.apply(channels.getFirst(), channels.getFirst());
            Core.merge(channels, lab);
            Mat out = new Mat();
            Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2BGR);
            return out;
        } finally {
            lab.release();
            channels.forEach(Mat::release);
        }
    }

    private static String dataUri(Mat bgr) {
        MatOfByte jpeg = new MatOfByte();
        MatOfInt options = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY);
        try {
            Imgcodecs.imencode(".jpg", bgr, jpeg, options);
            return DATA_URI + Base64.getEncoder().encodeToString(jpeg.toArray());
        } finally {
            jpeg.release();
            options.release();
        }
    }
}

package com.pokemans.app.condition;

import com.pokemans.app.intake.CardOutline;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import nu.pattern.OpenCV;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * The pictures {@link ClaudeVisionPreGradeProvider} sends for one side of a card: the card straightened
 * with a thin margin of background around it, its four corners close up, and its four edges as long
 * strips. Close-ups matter because one whole-card photo, scaled to what the model reads, shows a corner
 * at about 30 px: too few to see fraying or the first whitening on a back edge.
 *
 * <p>The outline is intake's detector (accurate to a few pixels of an 800 px copy), so every view keeps
 * {@link #MARGIN_MM} of background past the card's edge: the true edge is always in the picture. Pure:
 * no Spring, the same photo always gives the same views.
 */
final class ClaudeVisionViews {

    /** Straightened at this many pixels per millimetre; higher than the photo gives is upscaling, which only costs tokens. */
    static final int PX_PER_MM = 32;
    static final int MARGIN_MM = 3;
    static final int CARD_W_MM = 63;
    static final int CARD_H_MM = 88;
    /** A corner close-up's side, from the canvas corner: margin plus 11 mm of card. */
    static final int CORNER_MM = 14;
    /** An edge strip's depth: margin plus 4 mm of card, where whitening and chipping show. */
    static final int STRIP_MM = 7;
    /** Long-edge and area limits of what the model reads at full resolution; larger is scaled down by the API anyway. */
    static final int MAX_LONG_EDGE = 2576;
    static final double MAX_PIXELS = 3_700_000;
    private static final int JPEG_QUALITY = 90;

    static {
        OpenCV.loadLocally();
    }

    /** One picture and the label the prompt gives it. */
    record View(String label, byte[] jpeg) {
    }

    private final CardOutline outline;

    ClaudeVisionViews(CardOutline outline) {
        this.outline = outline;
    }

    /**
     * The views of one side, whole card first, then corners (top-left, top-right, bottom-right,
     * bottom-left) and edges (top, right, bottom, left), each labelled with {@code side}. Empty when the
     * photo doesn't decode or no whole card is found in it.
     */
    Optional<List<View>> of(String side, byte[] photo) {
        MatOfByte encoded = new MatOfByte(photo);
        Mat image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        List<Mat> owned = new ArrayList<>(List.of(encoded, image));
        try {
            if (image.empty()) {
                return Optional.empty();
            }
            Optional<Point[]> quad = outline.find(image);
            if (quad.isEmpty()) {
                return Optional.empty();
            }
            Mat card = straighten(image, quad.get(), owned);
            return Optional.of(views(side, card, owned));
        } finally {
            owned.forEach(Mat::release);
        }
    }

    /**
     * The photo as it is, scaled to what the model reads: sent instead of the crops when no card outline
     * is found, so the side is still graded (the details say so). Empty when it doesn't decode.
     */
    static Optional<View> whole(String side, byte[] photo) {
        MatOfByte encoded = new MatOfByte(photo);
        Mat image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        List<Mat> owned = new ArrayList<>(List.of(encoded, image));
        try {
            return image.empty() ? Optional.empty() : Optional.of(new View(side + " — whole photo (card outline not found)", jpeg(image, owned)));
        } finally {
            owned.forEach(Mat::release);
        }
    }

    /** Package-private for tests: the views of a card already straightened onto the canvas. */
    static List<View> views(String side, Mat card, List<Mat> owned) {
        int w = card.cols();
        int h = card.rows();
        int c = CORNER_MM * PX_PER_MM;
        int s = STRIP_MM * PX_PER_MM;
        List<View> views = new ArrayList<>();
        views.add(new View(side + " — whole card", jpeg(card, owned)));
        views.add(new View(side + " — top-left corner", crop(card, new Rect(0, 0, c, c), owned)));
        views.add(new View(side + " — top-right corner", crop(card, new Rect(w - c, 0, c, c), owned)));
        views.add(new View(side + " — bottom-right corner", crop(card, new Rect(w - c, h - c, c, c), owned)));
        views.add(new View(side + " — bottom-left corner", crop(card, new Rect(0, h - c, c, c), owned)));
        views.add(new View(side + " — top edge", crop(card, new Rect(0, 0, w, s), owned)));
        views.add(new View(side + " — right edge", crop(card, new Rect(w - s, 0, s, h), owned)));
        views.add(new View(side + " — bottom edge", crop(card, new Rect(0, h - s, w, s), owned)));
        views.add(new View(side + " — left edge", crop(card, new Rect(0, 0, s, h), owned)));
        return views;
    }

    private static byte[] crop(Mat card, Rect area, List<Mat> owned) {
        Mat view = card.submat(area);
        owned.add(view);
        return jpeg(view, owned);
    }

    /** The card upright on a canvas of the card plus {@link #MARGIN_MM} all round. */
    private static Mat straighten(Mat image, Point[] quad, List<Mat> owned) {
        int m = MARGIN_MM * PX_PER_MM;
        int cw = CARD_W_MM * PX_PER_MM;
        int ch = CARD_H_MM * PX_PER_MM;
        Point[] targets = {new Point(m, m), new Point(m + cw - 1, m), new Point(m + cw - 1, m + ch - 1), new Point(m, m + ch - 1)};
        MatOfPoint2f from = new MatOfPoint2f(quad);
        MatOfPoint2f to = new MatOfPoint2f(targets);
        Mat transform = Imgproc.getPerspectiveTransform(from, to);
        Mat out = new Mat();
        owned.addAll(List.of(from, to, transform, out));
        Imgproc.warpPerspective(image, out, transform, new Size(cw + 2 * m, ch + 2 * m), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE);
        return out;
    }

    /** A JPEG, scaled down only if it is past what the model reads at full resolution. */
    private static byte[] jpeg(Mat view, List<Mat> owned) {
        double scale = Math.min(1.0, Math.min((double) MAX_LONG_EDGE / Math.max(view.cols(), view.rows()),
                Math.sqrt(MAX_PIXELS / ((double) view.cols() * view.rows()))));
        Mat sized = view;
        if (scale < 1.0) {
            sized = new Mat();
            owned.add(sized);
            Imgproc.resize(view, sized, new Size(Math.round(view.cols() * scale), Math.round(view.rows() * scale)), 0, 0, Imgproc.INTER_AREA);
        }
        MatOfByte out = new MatOfByte();
        MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY);
        owned.addAll(List.of(out, params));
        Imgcodecs.imencode(".jpg", sized, out, params);
        return out.toArray();
    }
}

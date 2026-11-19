package com.pokemans.app.intake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nu.pattern.OpenCV;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * The surface view's "Clean back": a card back with the standard printed back design subtracted, so only physical marks
 * are left (dents, scratches, whitening, roller lines, print defects). Every English back carries the same print, and its
 * shading inside the Poké Ball reads as dents to a reviewer; taking it away removes that confusion.
 *
 * <p>The reference back is the per-pixel median of several of our own BACK surface crops, aligned to one of them: a mark
 * on one card is outvoted by the others. A photo is aligned to it with a feature-based homography (ORB keypoints, ratio
 * test, RANSAC), its brightness and contrast matched to the reference by least squares inside the card, and the
 * difference drawn around mid-grey like the Relief emboss. Lighting gradients are taken out with a wide blur of the
 * difference. The result is warped back into the photo's own surface frame, so a defect mark placed on it lands where it
 * does on the photo. Past the card's outline it shows the photo's own fine detail, so edges and corners stay visible.
 *
 * <p>A photo that doesn't align well, or whose print doesn't correlate with the reference (a Japanese back, another
 * design, a blurred photo), gets no image: never a faked one. The scores are kept either way, for later authenticity
 * work; nothing here flags a card or changes a grade, price or eligibility.
 *
 * <p>All work is in greyscale at a fixed frame: the surface crop's layout (the card plus
 * {@link CardDetector#WIDE_MARGIN} past each edge) with the card {@link #CARD_WIDTH} px wide.
 */
final class CleanBack {

    static final int CARD_WIDTH = 1200;
    static final int CARD_HEIGHT = (int) Math.round(CARD_WIDTH / CardDetector.CARD_ASPECT);
    static final int MARGIN_X = (int) Math.round(CARD_WIDTH * CardDetector.WIDE_MARGIN);
    static final int MARGIN_Y = (int) Math.round(CARD_HEIGHT * CardDetector.WIDE_MARGIN);
    static final int WIDTH = CARD_WIDTH + 2 * MARGIN_X;
    static final int HEIGHT = CARD_HEIGHT + 2 * MARGIN_Y;

    /** Backs needed for a reference picked automatically: a median of five outvotes a mark on any two. */
    static final int MIN_REFERENCE_BACKS = 5;
    /** Backs needed when the admin chooses them (and vouches that they are clean English backs). */
    static final int MIN_SEEDED_BACKS = 3;
    /** Most candidates looked at for an automatic reference: picking the anchor compares every pair. */
    static final int MAX_REFERENCE_CANDIDATES = 12;

    /** RANSAC inliers below this, or under this share of the ratio-tested matches, is a poor alignment. */
    static final int MIN_INLIERS = 40;
    static final double MIN_INLIER_RATIO = 0.25;
    /**
     * The surface crop is already rectified from the card's corners, so the homography should move the card's corners
     * only a little: more than this share of the card's width means it locked onto something else.
     */
    static final double MAX_CORNER_SHIFT = 0.05;
    /** Correlation with the reference inside the card, after alignment, below which the print is another design. */
    static final double MIN_CORRELATION = 0.6;

    private static final int FEATURES = 4000;
    private static final float RATIO_TEST = 0.8f;
    private static final double RANSAC_PX = 3.0;
    /** Grey levels of the output per grey level of difference. */
    private static final double GAIN = 2.0;
    /** The difference's lighting gradient: blurred at this share of the card's width and taken away. */
    private static final double LOW_PASS_SHARE = 0.04;
    /** Past the card, the photo minus its blur at this share (as {@link CardDetector#enhance}'s high-pass layer). */
    private static final double OUTSIDE_HIGH_PASS_SHARE = 0.006;
    /** A light blur of both before differencing, so sub-pixel misalignment and JPEG noise don't outline the print. */
    private static final double PRE_BLUR_SIGMA = 1.0;
    /** The card's rounded corner, as a share of its width (about 3 mm of 63). */
    private static final double CORNER_RADIUS_SHARE = 0.05;

    static {
        OpenCV.loadLocally();
    }

    private CleanBack() {
    }

    /** Why a photo got no clean back. */
    enum Failure {
        /** The surface crop doesn't decode. */
        DOESNT_DECODE,
        /** Too few keypoints: a blank or badly blurred card. */
        TOO_FEW_FEATURES,
        /** Too few keypoints that match the reference's: another design, or blur. */
        TOO_FEW_MATCHES,
        /** RANSAC found no homography. */
        NO_HOMOGRAPHY,
        /** A homography, but too few matches agree with it. */
        POOR_ALIGNMENT,
        /** The homography moves the card's corners further than a rectified crop can be off. */
        IMPLAUSIBLE_WARP,
        /** Aligned, but the print doesn't correlate with the reference: a different back design. */
        DESIGN_MISMATCH
    }

    /**
     * How well a photo matched the reference. Null where the step wasn't reached.
     *
     * @param matches     keypoint matches past the ratio test
     * @param inliers     matches RANSAC kept
     * @param inlierRatio inliers as a share of matches
     * @param cornerShift furthest the homography moves a card corner, as a share of the card's width
     * @param correlation correlation of the aligned photo with the reference inside the card (1 is the same print)
     * @param residual    RMS of the clean difference inside the card, in grey levels at the reference's contrast: the
     *                    mismatch left after the print is taken away (marks, wear, or a print that differs)
     */
    record Scores(Integer matches, Integer inliers, Double inlierRatio, Double cornerShift, Double correlation, Double residual) {

        static final Scores NONE = new Scores(null, null, null, null, null, null);
    }

    /** @param jpeg the clean back, or null with the failure */
    record Outcome(byte[] jpeg, Scores scores, Failure failure) {
    }

    /** A reference back prepared for matching. Read-only once made, so it can be shared between threads. */
    static final class Reference {

        private final Mat grey;
        private final Features features;

        private Reference(Mat grey) {
            this.grey = grey;
            this.features = features(grey, DETECT_MASK);
        }

        /** From a stored reference image (PNG); null if it doesn't decode. */
        static Reference of(byte[] image) {
            Mat grey = frame(image);
            return grey == null ? null : new Reference(grey);
        }
    }

    /** The card inside the frame, rounded corners and all, a few pixels in from the outline. */
    private static final Mat CARD_MASK = cardMask(4);
    /** Where keypoints are looked for: well inside the outline, so the background past the card doesn't count. */
    private static final Mat DETECT_MASK = cardMask(14);

    /** A surface crop in the fixed frame, greyscale; null if it doesn't decode. */
    static Mat frame(byte[] image) {
        MatOfByte encoded = new MatOfByte(image);
        Mat decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
        encoded.release();
        if (decoded.empty()) {
            return null;
        }
        Mat out = new Mat();
        int interpolation = decoded.cols() > WIDTH ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR;
        Imgproc.resize(decoded, out, new Size(WIDTH, HEIGHT), 0, 0, interpolation);
        decoded.release();
        return out;
    }

    /**
     * The clean back of a BACK photo's surface crop against the reference. The image has the fixed frame's size and the
     * surface crop's layout.
     */
    static Outcome clean(byte[] surfaceJpeg, Reference reference) {
        List<Mat> owned = new ArrayList<>();
        try {
            Mat photo = frame(surfaceJpeg);
            if (photo == null) {
                return new Outcome(null, Scores.NONE, Failure.DOESNT_DECODE);
            }
            owned.add(photo);
            Features photoFeatures = features(photo, DETECT_MASK);
            owned.addAll(photoFeatures.mats());
            Alignment alignment = align(photoFeatures, reference.features);
            if (alignment.homography() != null) {
                owned.add(alignment.homography());
            }
            if (alignment.failure() != null) {
                return new Outcome(null, alignment.scores(null, null), alignment.failure());
            }
            Mat h = alignment.homography();

            Mat warped = track(owned, new Mat());
            Imgproc.warpPerspective(photo, warped, h, new Size(WIDTH, HEIGHT), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, new Scalar(0));
            Mat inside = track(owned, insideMask(h, owned));

            Comparison c = compare(warped, reference.grey, inside, owned);
            if (c == null || c.correlation() < MIN_CORRELATION) {
                Double correlation = c == null ? null : c.correlation();
                return new Outcome(null, alignment.scores(correlation, c == null ? null : c.residual()), Failure.DESIGN_MISMATCH);
            }

            // 128 + gain × difference in the reference's frame, then back into the photo's own frame.
            Mat cleanRef = track(owned, new Mat());
            c.difference().convertTo(cleanRef, CvType.CV_32F, GAIN, 128);
            Mat cleanPhoto = track(owned, new Mat());
            Imgproc.warpPerspective(cleanRef, cleanPhoto, h, new Size(WIDTH, HEIGHT), Imgproc.INTER_LINEAR | Imgproc.WARP_INVERSE_MAP,
                    Core.BORDER_CONSTANT, new Scalar(128));
            Mat weight = track(owned, new Mat());
            Imgproc.warpPerspective(c.weight(), weight, h, new Size(WIDTH, HEIGHT), Imgproc.INTER_LINEAR | Imgproc.WARP_INVERSE_MAP,
                    Core.BORDER_CONSTANT, new Scalar(0));
            Imgproc.GaussianBlur(weight, weight, new Size(0, 0), 1.5);

            // Past the card: the photo's own fine detail around mid-grey, so the outline, edges and corners show.
            Mat photoF = track(owned, new Mat());
            photo.convertTo(photoF, CvType.CV_32F);
            Mat photoBlur = track(owned, new Mat());
            Imgproc.GaussianBlur(photoF, photoBlur, new Size(0, 0), Math.max(1.0, OUTSIDE_HIGH_PASS_SHARE * WIDTH));
            Mat outside = track(owned, new Mat());
            Core.subtract(photoF, photoBlur, outside);
            outside.convertTo(outside, CvType.CV_32F, GAIN, 128);

            // outside + weight × (clean − outside)
            Mat blend = track(owned, new Mat());
            Core.subtract(cleanPhoto, outside, blend);
            Core.multiply(blend, weight, blend);
            Core.add(blend, outside, blend);
            Mat out = track(owned, new Mat());
            blend.convertTo(out, CvType.CV_8U);
            return new Outcome(jpeg(out), alignment.scores(c.correlation(), c.residual()), null);
        } finally {
            owned.forEach(Mat::release);
        }
    }

    /**
     * @param png      the reference, greyscale PNG in the fixed frame; null when too few backs agreed
     * @param used     indexes of the surfaces in the median, the anchor first
     * @param rejected why each other surface was left out, by index
     */
    record Built(byte[] png, List<Integer> used, Map<Integer, String> rejected) {
    }

    /**
     * A reference back from several BACK surface crops: the one that aligns with the most others is the anchor, every
     * other that aligns with it and correlates with its print is warped onto it with its brightness and contrast matched,
     * and the per-pixel median is taken. Too few in agreement gives no image.
     *
     * @param minimum backs needed in the median, the anchor included
     */
    static Built buildReference(List<byte[]> surfaces, int minimum) {
        List<Mat> owned = new ArrayList<>();
        Map<Integer, String> rejected = new LinkedHashMap<>();
        try {
            int n = surfaces.size();
            Mat[] frames = new Mat[n];
            Features[] features = new Features[n];
            for (int i = 0; i < n; i++) {
                frames[i] = frame(surfaces.get(i));
                if (frames[i] == null) {
                    rejected.put(i, Failure.DOESNT_DECODE.name());
                    continue;
                }
                owned.add(frames[i]);
                features[i] = features(frames[i], DETECT_MASK);
                owned.addAll(features[i].mats());
            }

            // The anchor: the back that aligns with the most others (a Japanese back or a scratched one aligns with few).
            int[] agree = new int[n];
            long[] inliers = new long[n];
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (features[i] == null || features[j] == null) {
                        continue;
                    }
                    Alignment a = align(features[i], features[j]);
                    if (a.homography() != null) {
                        a.homography().release();
                    }
                    if (a.failure() == null) {
                        agree[i]++;
                        agree[j]++;
                        inliers[i] += a.inliers();
                        inliers[j] += a.inliers();
                    }
                }
            }
            int anchor = -1;
            for (int i = 0; i < n; i++) {
                if (features[i] != null && (anchor < 0 || agree[i] > agree[anchor] || agree[i] == agree[anchor] && inliers[i] > inliers[anchor])) {
                    anchor = i;
                }
            }
            if (anchor < 0) {
                return new Built(null, List.of(), rejected);
            }

            List<Integer> used = new ArrayList<>(List.of(anchor));
            List<byte[]> layers = new ArrayList<>();
            byte[] anchorPixels = new byte[WIDTH * HEIGHT];
            frames[anchor].get(0, 0, anchorPixels);
            layers.add(anchorPixels);
            for (int i = 0; i < n; i++) {
                if (i == anchor || features[i] == null) {
                    continue;
                }
                Alignment a = align(features[i], features[anchor]);
                if (a.homography() != null) {
                    owned.add(a.homography());
                }
                if (a.failure() != null) {
                    rejected.put(i, a.failure().name());
                    continue;
                }
                Mat warped = track(owned, new Mat());
                Imgproc.warpPerspective(frames[i], warped, a.homography(), new Size(WIDTH, HEIGHT), Imgproc.INTER_LINEAR,
                        Core.BORDER_REPLICATE);
                Mat inside = track(owned, insideMask(a.homography(), owned));
                Comparison c = compare(warped, frames[anchor], inside, owned);
                if (c == null || c.correlation() < MIN_CORRELATION) {
                    rejected.put(i, Failure.DESIGN_MISMATCH.name());
                    continue;
                }
                // Brightness and contrast matched to the anchor: (photo − b) / a.
                Mat matched = track(owned, new Mat());
                warped.convertTo(matched, CvType.CV_8U, 1 / c.slope(), -c.intercept() / c.slope());
                byte[] pixels = new byte[WIDTH * HEIGHT];
                matched.get(0, 0, pixels);
                layers.add(pixels);
                used.add(i);
            }
            if (used.size() < minimum) {
                return new Built(null, used, rejected);
            }
            return new Built(png(median(layers)), used, rejected);
        } finally {
            owned.forEach(Mat::release);
        }
    }

    private static Mat median(List<byte[]> layers) {
        int k = layers.size();
        int size = WIDTH * HEIGHT;
        byte[] out = new byte[size];
        int[] values = new int[k];
        for (int p = 0; p < size; p++) {
            for (int i = 0; i < k; i++) {
                values[i] = layers.get(i)[p] & 0xFF;
            }
            Arrays.sort(values);
            int m = k % 2 == 1 ? values[k / 2] : (values[k / 2 - 1] + values[k / 2] + 1) / 2;
            out[p] = (byte) m;
        }
        Mat mat = new Mat(HEIGHT, WIDTH, CvType.CV_8U);
        mat.put(0, 0, out);
        return mat;
    }

    private record Features(MatOfKeyPoint keypoints, Mat descriptors) {

        List<Mat> mats() {
            return List.of(keypoints, descriptors);
        }
    }

    private static Features features(Mat grey, Mat mask) {
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        Mat descriptors = new Mat();
        ORB.create(FEATURES).detectAndCompute(grey, mask, keypoints, descriptors);
        return new Features(keypoints, descriptors);
    }

    /** @param homography photo to reference, or null; set even when the alignment failed past RANSAC */
    private record Alignment(Mat homography, Integer matches, Integer inliers, Double inlierRatio, Double cornerShift, Failure failure) {

        Scores scores(Double correlation, Double residual) {
            return new Scores(matches, inliers, inlierRatio, cornerShift, correlation, residual);
        }
    }

    private static Alignment align(Features photo, Features reference) {
        if (photo.descriptors().rows() < MIN_INLIERS || reference.descriptors().rows() < MIN_INLIERS) {
            return new Alignment(null, null, null, null, null, Failure.TOO_FEW_FEATURES);
        }
        List<MatOfDMatch> knn = new ArrayList<>();
        BFMatcher.create(Core.NORM_HAMMING, false).knnMatch(photo.descriptors(), reference.descriptors(), knn, 2);
        KeyPoint[] from = photo.keypoints().toArray();
        KeyPoint[] to = reference.keypoints().toArray();
        List<Point> src = new ArrayList<>();
        List<Point> dst = new ArrayList<>();
        for (MatOfDMatch pair : knn) {
            DMatch[] d = pair.toArray();
            if (d.length >= 2 && d[0].distance < RATIO_TEST * d[1].distance) {
                src.add(from[d[0].queryIdx].pt);
                dst.add(to[d[0].trainIdx].pt);
            }
            pair.release();
        }
        int matches = src.size();
        if (matches < MIN_INLIERS) {
            return new Alignment(null, matches, null, null, null, Failure.TOO_FEW_MATCHES);
        }
        MatOfPoint2f srcMat = new MatOfPoint2f(src.toArray(Point[]::new));
        MatOfPoint2f dstMat = new MatOfPoint2f(dst.toArray(Point[]::new));
        Mat inlierMask = new Mat();
        try {
            Mat h = Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, RANSAC_PX, inlierMask, 2000, 0.995);
            if (h.empty()) {
                h.release();
                return new Alignment(null, matches, null, null, null, Failure.NO_HOMOGRAPHY);
            }
            int inliers = Core.countNonZero(inlierMask);
            double ratio = (double) inliers / matches;
            double shift = cornerShift(h);
            Failure failure = inliers < MIN_INLIERS || ratio < MIN_INLIER_RATIO ? Failure.POOR_ALIGNMENT
                    : shift > MAX_CORNER_SHIFT ? Failure.IMPLAUSIBLE_WARP : null;
            return new Alignment(h, matches, inliers, ratio, shift, failure);
        } finally {
            srcMat.release();
            dstMat.release();
            inlierMask.release();
        }
    }

    /** Furthest the homography moves one of the card's corners, as a share of the card's width. */
    private static double cornerShift(Mat h) {
        Point[] corners = {new Point(MARGIN_X, MARGIN_Y), new Point(MARGIN_X + CARD_WIDTH, MARGIN_Y),
                new Point(MARGIN_X + CARD_WIDTH, MARGIN_Y + CARD_HEIGHT), new Point(MARGIN_X, MARGIN_Y + CARD_HEIGHT)};
        MatOfPoint2f in = new MatOfPoint2f(corners);
        MatOfPoint2f out = new MatOfPoint2f();
        try {
            Core.perspectiveTransform(in, out, h);
            Point[] moved = out.toArray();
            double max = 0;
            for (int i = 0; i < 4; i++) {
                max = Math.max(max, Math.hypot(moved[i].x - corners[i].x, moved[i].y - corners[i].y));
            }
            return max / CARD_WIDTH;
        } finally {
            in.release();
            out.release();
        }
    }

    /** The card mask where the warped photo has pixels: none past the photo's own frame. */
    private static Mat insideMask(Mat h, List<Mat> owned) {
        Mat ones = track(owned, new Mat(HEIGHT, WIDTH, CvType.CV_8U, new Scalar(255)));
        Mat valid = track(owned, new Mat());
        Imgproc.warpPerspective(ones, valid, h, new Size(WIDTH, HEIGHT), Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, new Scalar(0));
        Imgproc.erode(valid, valid, track(owned, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5))));
        Mat inside = new Mat();
        Core.bitwise_and(valid, CARD_MASK, inside);
        return inside;
    }

    /**
     * An aligned photo against the reference inside the mask.
     *
     * @param slope      photo ≈ slope × reference + intercept, by least squares
     * @param difference (photo − intercept) / slope − reference, with its lighting gradient taken out, 0 outside the mask
     *                   (CV_32F)
     * @param weight     the mask as 0 or 1 (CV_32F)
     * @param residual   RMS of the difference inside the mask
     */
    private record Comparison(double slope, double intercept, double correlation, double residual, Mat difference, Mat weight) {
    }

    /** Null if the mask is (nearly) empty or the reference or photo is flat there. */
    private static Comparison compare(Mat photo, Mat reference, Mat mask, List<Mat> owned) {
        Mat y = track(owned, new Mat());
        Mat x = track(owned, new Mat());
        photo.convertTo(y, CvType.CV_32F);
        reference.convertTo(x, CvType.CV_32F);
        Imgproc.GaussianBlur(y, y, new Size(0, 0), PRE_BLUR_SIGMA);
        Imgproc.GaussianBlur(x, x, new Size(0, 0), PRE_BLUR_SIGMA);
        int size = WIDTH * HEIGHT;
        float[] ys = new float[size];
        float[] xs = new float[size];
        byte[] ms = new byte[size];
        y.get(0, 0, ys);
        x.get(0, 0, xs);
        mask.get(0, 0, ms);

        double n = 0;
        double sx = 0;
        double sy = 0;
        for (int p = 0; p < size; p++) {
            if (ms[p] != 0) {
                n++;
                sx += xs[p];
                sy += ys[p];
            }
        }
        if (n < 0.25 * CARD_WIDTH * CARD_HEIGHT) {
            return null;
        }
        double mx = sx / n;
        double my = sy / n;
        double sxx = 0;
        double syy = 0;
        double sxy = 0;
        for (int p = 0; p < size; p++) {
            if (ms[p] != 0) {
                double dx = xs[p] - mx;
                double dy = ys[p] - my;
                sxx += dx * dx;
                syy += dy * dy;
                sxy += dx * dy;
            }
        }
        if (sxx < 1e-6 * n || syy < 1e-6 * n) {
            return null;
        }
        double correlation = sxy / Math.sqrt(sxx * syy);
        double slope = sxy / sxx;
        double intercept = my - slope * mx;
        if (slope <= 0.05) {
            return new Comparison(slope, intercept, correlation, Double.NaN, null, null);
        }

        float[] diff = new float[size];
        float[] weights = new float[size];
        for (int p = 0; p < size; p++) {
            if (ms[p] != 0) {
                diff[p] = (float) ((ys[p] - intercept) / slope - xs[p]);
                weights[p] = 1;
            }
        }
        Mat d = track(owned, new Mat(HEIGHT, WIDTH, CvType.CV_32F));
        d.put(0, 0, diff);
        Mat w = track(owned, new Mat(HEIGHT, WIDTH, CvType.CV_32F));
        w.put(0, 0, weights);

        // Lighting: the difference's blur inside the mask only (normalised by the mask's own blur), taken away.
        double sigma = LOW_PASS_SHARE * CARD_WIDTH;
        Mat dBlur = track(owned, new Mat());
        Mat wBlur = track(owned, new Mat());
        Imgproc.GaussianBlur(d, dBlur, new Size(0, 0), sigma);
        Imgproc.GaussianBlur(w, wBlur, new Size(0, 0), sigma);
        Core.max(wBlur, new Scalar(1e-3), wBlur);
        Mat lowPass = track(owned, new Mat());
        Core.divide(dBlur, wBlur, lowPass);
        Core.subtract(d, lowPass, d);
        Core.multiply(d, w, d);

        float[] cleaned = new float[size];
        d.get(0, 0, cleaned);
        double squares = 0;
        for (int p = 0; p < size; p++) {
            if (ms[p] != 0) {
                squares += (double) cleaned[p] * cleaned[p];
            }
        }
        return new Comparison(slope, intercept, correlation, Math.sqrt(squares / n), d, w);
    }

    /** The card's outline in the frame with rounded corners, {@code inset} px in from the edge. */
    private static Mat cardMask(int inset) {
        Mat mask = Mat.zeros(HEIGHT, WIDTH, CvType.CV_8U);
        int r = Math.max(1, (int) Math.round(CORNER_RADIUS_SHARE * CARD_WIDTH) - inset);
        int x0 = MARGIN_X + inset;
        int y0 = MARGIN_Y + inset;
        int x1 = MARGIN_X + CARD_WIDTH - 1 - inset;
        int y1 = MARGIN_Y + CARD_HEIGHT - 1 - inset;
        Scalar white = new Scalar(255);
        Imgproc.rectangle(mask, new Point(x0 + r, y0), new Point(x1 - r, y1), white, Imgproc.FILLED);
        Imgproc.rectangle(mask, new Point(x0, y0 + r), new Point(x1, y1 - r), white, Imgproc.FILLED);
        for (Point c : new Point[] {new Point(x0 + r, y0 + r), new Point(x1 - r, y0 + r), new Point(x1 - r, y1 - r), new Point(x0 + r, y1 - r)}) {
            Imgproc.circle(mask, c, r, white, Imgproc.FILLED);
        }
        return mask;
    }

    private static byte[] jpeg(Mat image) {
        MatOfByte out = new MatOfByte();
        try {
            Imgcodecs.imencode(".jpg", image, out, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 90));
            return out.toArray();
        } finally {
            out.release();
        }
    }

    private static byte[] png(Mat image) {
        MatOfByte out = new MatOfByte();
        try {
            Imgcodecs.imencode(".png", image, out);
            return out.toArray();
        } finally {
            out.release();
            image.release();
        }
    }

    private static <T extends Mat> T track(List<Mat> owned, T mat) {
        owned.add(mat);
        return mat;
    }
}

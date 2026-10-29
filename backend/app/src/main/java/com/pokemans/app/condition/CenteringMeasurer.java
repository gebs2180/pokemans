package com.pokemans.app.condition;

import com.pokemans.app.intake.CardOutline;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import nu.pattern.OpenCV;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Measures a card's centering from one photo: the width of each printed border, left/right and
 * top/bottom, as PSA measures it. Pure: no Spring, the same photo always gives the same answer.
 *
 * <ol>
 *   <li>The card's outline from intake's detector, which is only accurate to a few pixels of an 800 px
 *       copy, is used to straighten the card roughly, with a margin of background around it.</li>
 *   <li>Each outer edge is then found again on that straightened copy, at many points along the side,
 *       as the strongest change away from the background colour, and a straight line is fitted
 *       through them. The four lines give exact corners, and the card is straightened again from
 *       those, at {@link #PX_PER_MM} pixels per millimetre. This removes the photo's tilt and
 *       perspective.</li>
 *   <li>On each side, the colour change from pixel to pixel going inward is averaged along the middle
 *       of the side. The border's inner edge (the yellow border meeting the card's frame on a Pokémon
 *       front, the navy border meeting the swirl on the back) is printed straight, so it adds up to a
 *       sharp peak, while art, foil sparkle and uneven lighting average out. The first strong peak
 *       after a plain stretch is the border's width. The side is done in eight pieces, most of which
 *       must agree, so a symbol printed over the border doesn't spoil it.</li>
 * </ol>
 *
 * Anything not clear-cut comes back {@link Unmeasured} with a reason instead of a guess: no plain
 * border with a clear inner edge (full-art and borderless cards, glare, sleeves), pieces of a side
 * that disagree, or edges that don't make a card-shaped outline. A border thinner than about 0.6 mm
 * can't be told from the card edge, so it comes back unmeasured too.
 */
final class CenteringMeasurer {

    static final int PX_PER_MM = 20;
    static final int CARD_WIDTH = 63 * PX_PER_MM;
    static final int CARD_HEIGHT = 88 * PX_PER_MM;

    /** Background kept around the card on the first, rough straightening: covers the outline's error. */
    private static final int MARGIN = 4 * PX_PER_MM;
    /** Where on each side the border is measured: corners are rounded and the cut wanders there. */
    private static final double OUTER_SPAN = 0.15;
    private static final double INNER_SPAN = 0.10;
    /** The inner edge is looked for from this far in from the card edge, past the blur of the edge itself. */
    private static final int INNER_FROM = 8;
    /**
     * Pieces each side is split into along its length for the inner edge. At least {@link #AGREEING}
     * must find the same edge: an evolution portrait or energy symbol printed over the border, or
     * the copyright line printed close to it, spoils the pieces it crosses, not the side.
     */
    private static final int SEGMENTS = 8;
    private static final int AGREEING = 5;
    /**
     * How far apart (0.2 mm) pieces may be and still agree: a card that isn't quite flat, or the phone
     * lens, bows the printed edge by about this much against the straight cut.
     */
    private static final double BOW_PX = 4;
    /**
     * Colour change is taken between pixels this far either side: wider than foil sparkle and print
     * dots, narrower than the gap between the border's edge and the next printed line.
     */
    private static final int STEP = 3;
    /** Weakest averaged colour change (Lab units over 2 × STEP px) that counts as a printed edge. */
    private static final double MIN_EDGE = 4;
    /** How many times the border's own median colour change its inner edge must reach (plus {@link #MIN_EDGE}). */
    private static final double PEAK_OVER_BORDER = 2;
    /** Widest border looked for, as a share of the card's width (a 90/10 card's wide border is about 5.5 mm). */
    private static final double MAX_BORDER = 0.20;
    /** Pixels (0.75 mm) each side is averaged over along its length before it is scanned. */
    private static final int SMOOTH_ALONG = 15;

    static {
        OpenCV.loadLocally();
    }

    sealed interface Result {
    }

    /**
     * Border widths in pixels of the straightened card ({@link #PX_PER_MM} per mm).
     *
     * @param spreadPx the most the agreeing pieces of any side differed by: how straight the inner edge was
     */
    record Measured(double left, double right, double top, double bottom, double spreadPx) implements Result {

        /** Left border's share of left + right, in percent: 55 means 55/45. */
        double leftShare() {
            return 100 * left / (left + right);
        }

        double topShare() {
            return 100 * top / (top + bottom);
        }

        /** The worse pair's larger share, 50 to 100: what PSA's tolerances are written against. */
        double worstShare() {
            double lr = Math.max(leftShare(), 100 - leftShare());
            double tb = Math.max(topShare(), 100 - topShare());
            return Math.max(lr, tb);
        }
    }

    record Unmeasured(String reason) implements Result {
    }

    private enum Side {
        LEFT, RIGHT, TOP, BOTTOM;

        String label() {
            return name().toLowerCase();
        }

        boolean vertical() {
            return this == LEFT || this == RIGHT;
        }
    }

    private final CardOutline outline;
    private final Consumer<Mat> straightened;

    CenteringMeasurer(CardOutline outline) {
        this(outline, card -> {
        });
    }

    /** @param straightened sees each exactly straightened card (BGR) before it is measured: for calibration */
    CenteringMeasurer(CardOutline outline, Consumer<Mat> straightened) {
        this.outline = outline;
        this.straightened = straightened;
    }

    Result measure(byte[] imageBytes) {
        Mat image = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
        try {
            if (image.empty()) {
                return new Unmeasured("the photo doesn't decode");
            }
            return outline.find(image).map(quad -> measure(image, quad))
                    .orElseGet(() -> new Unmeasured("no whole card found in the photo"));
        } finally {
            image.release();
        }
    }

    /** @param quad rough corners, TL, TR, BR, BL with the card upright */
    private Result measure(Mat image, Point[] quad) {
        List<Mat> owned = new ArrayList<>();
        try {
            int canvasWidth = CARD_WIDTH + 2 * MARGIN;
            int canvasHeight = CARD_HEIGHT + 2 * MARGIN;
            Mat rough = straighten(image, quad, roughTargets(), new Size(canvasWidth, canvasHeight), owned);
            Lab roughLab = Lab.of(rough, owned);

            double[][] lines = new double[4][];
            for (Side side : Side.values()) {
                Object line = outerEdge(roughLab, side);
                if (line instanceof String reason) {
                    return new Unmeasured(reason);
                }
                lines[side.ordinal()] = general(side, (double[]) line, canvasWidth, canvasHeight);
            }
            Point[] corners = {
                    intersect(lines[Side.LEFT.ordinal()], lines[Side.TOP.ordinal()]),
                    intersect(lines[Side.RIGHT.ordinal()], lines[Side.TOP.ordinal()]),
                    intersect(lines[Side.RIGHT.ordinal()], lines[Side.BOTTOM.ordinal()]),
                    intersect(lines[Side.LEFT.ordinal()], lines[Side.BOTTOM.ordinal()])};
            double width = (distance(corners[0], corners[1]) + distance(corners[3], corners[2])) / 2;
            double height = (distance(corners[0], corners[3]) + distance(corners[1], corners[2])) / 2;
            if (Math.abs(width / height / ((double) CARD_WIDTH / CARD_HEIGHT) - 1) > 0.03) {
                return new Unmeasured("the card's edges don't make a 63 × 88 mm outline (%.3f wide per high)".formatted(width / height));
            }

            // Back to the photo's pixels, and straighten again from the exact corners.
            Mat back = track(owned, new Mat());
            Core.invert(transform(quad, roughTargets(), owned), back);
            MatOfPoint2f exact = track(owned, new MatOfPoint2f());
            Core.perspectiveTransform(track(owned, new MatOfPoint2f(corners)), exact, back);
            Mat card = straighten(image, exact.toArray(), new Point[] {
                    new Point(0, 0), new Point(CARD_WIDTH - 1, 0),
                    new Point(CARD_WIDTH - 1, CARD_HEIGHT - 1), new Point(0, CARD_HEIGHT - 1)},
                    new Size(CARD_WIDTH, CARD_HEIGHT), owned);
            straightened.accept(card);
            Lab cardLab = Lab.of(card, owned);

            double[] widths = new double[4];
            double spread = 0;
            for (Side side : Side.values()) {
                Object border = innerEdge(cardLab, side);
                if (border instanceof String reason) {
                    return new Unmeasured(reason);
                }
                double[] found = (double[]) border;
                widths[side.ordinal()] = found[0];
                spread = Math.max(spread, found[1]);
            }
            double across = widths[0] + widths[1];
            double down = widths[2] + widths[3];
            if (across / down < 0.6 || across / down > 1 / 0.6) {
                return new Unmeasured("left + right (%.1f mm) and top + bottom (%.1f mm) borders are too different to be one printed frame"
                        .formatted(across / PX_PER_MM, down / PX_PER_MM));
            }
            return new Measured(widths[0], widths[1], widths[2], widths[3], spread);
        } finally {
            owned.forEach(Mat::release);
        }
    }

    private static Point[] roughTargets() {
        return new Point[] {
                new Point(MARGIN, MARGIN), new Point(MARGIN + CARD_WIDTH - 1, MARGIN),
                new Point(MARGIN + CARD_WIDTH - 1, MARGIN + CARD_HEIGHT - 1), new Point(MARGIN, MARGIN + CARD_HEIGHT - 1)};
    }

    private static Mat transform(Point[] from, Point[] to, List<Mat> owned) {
        return track(owned, Imgproc.getPerspectiveTransform(track(owned, new MatOfPoint2f(from)), track(owned, new MatOfPoint2f(to))));
    }

    private static Mat straighten(Mat image, Point[] from, Point[] to, Size size, List<Mat> owned) {
        Mat out = track(owned, new Mat());
        Imgproc.warpPerspective(image, out, transform(from, to, owned), size, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
        // A light blur so JPEG blocks and print dots don't read as edges.
        Imgproc.GaussianBlur(out, out, new Size(5, 5), 0);
        return out;
    }

    /**
     * Finds one outer edge on the roughly straightened copy, where the card is expected {@link #MARGIN}
     * pixels in from the canvas edge.
     *
     * @return the fitted line {a, b} with inward = a + b·along, or a reason it couldn't be found
     */
    private static Object outerEdge(Lab lab, Side side) {
        int length = side.vertical() ? CARD_HEIGHT : CARD_WIDTH;
        int from = MARGIN + (int) (OUTER_SPAN * length);
        int to = MARGIN + (int) ((1 - OUTER_SPAN) * length);
        int backgroundTo = MARGIN / 3;

        List<double[]> backgroundSamples = new ArrayList<>();
        for (int along = from; along < to; along += 4) {
            for (int inward = 1; inward < backgroundTo; inward++) {
                backgroundSamples.add(lab.at(side, along, inward));
            }
        }
        double[] background = median(backgroundSamples);
        double noise = percentile(backgroundSamples.stream().mapToDouble(v -> distance(v, background)).toArray(), 0.95);
        double threshold = Math.max(15, 2 * noise + 6);

        List<double[]> points = new ArrayList<>();
        int scanlines = 0;
        for (int along = from; along < to; along += 4) {
            scanlines++;
            int depth = 2 * MARGIN;
            double[] d = new double[depth];
            for (int inward = 0; inward < depth; inward++) {
                d[inward] = distance(lab.at(side, along, inward), background);
            }
            int first = firstSustained(d, backgroundTo, depth - 6, threshold, 6);
            if (first < 0) {
                continue;
            }
            // The edge is the steepest change near where the colour first leaves the background.
            int best = -1;
            double bestGradient = 0;
            for (int j = Math.max(1, first - 8); j <= Math.min(depth - 2, first + 8); j++) {
                double g = d[j + 1] - d[j - 1];
                if (g > bestGradient) {
                    bestGradient = g;
                    best = j;
                }
            }
            if (best < 0) {
                continue;
            }
            double offset = 0;
            if (best > 1 && best < depth - 2) {
                double gm = d[best] - d[best - 2];
                double gp = d[best + 2] - d[best];
                double denominator = gm - 2 * bestGradient + gp;
                offset = denominator == 0 ? 0 : Math.max(-0.5, Math.min(0.5, 0.5 * (gm - gp) / denominator));
            }
            points.add(new double[] {along, best + offset});
        }
        double[] line = robustLine(points);
        if (line == null || points.size() < 0.6 * scanlines || line[2] < 0.6 * scanlines) {
            return "the card's %s edge doesn't stand out from the background along its length (found on %d of %d lines, threshold %.0f)"
                    .formatted(side.label(), points.size(), scanlines, threshold);
        }
        if (line[3] > 1.5) {
            return "the card's %s edge isn't straight (%.1f px off a line)".formatted(side.label(), line[3]);
        }
        return new double[] {line[0], line[1]};
    }

    /**
     * Finds the border's inner edge on one side of the exactly straightened card. The colour change
     * across the side (how much each pixel differs from the next one inward) is averaged along the
     * middle of the side, in {@link #SEGMENTS} pieces. A printed edge runs straight along the side, so
     * it adds up to a sharp peak in every piece, while art, foil sparkle and lighting don't. The
     * border's inner edge is the first strong peak in from the card's edge; the pieces must agree.
     *
     * @return {width, spread between the agreeing pieces} in pixels, or a reason it couldn't be measured
     */
    private static Object innerEdge(Lab lab, Side side) {
        int length = side.vertical() ? CARD_HEIGHT : CARD_WIDTH;
        int from = (int) (INNER_SPAN * length);
        int to = (int) ((1 - INNER_SPAN) * length);
        int depth = (int) (MAX_BORDER * CARD_WIDTH);
        int piece = (to - from) / SEGMENTS;

        double[] positions = new double[SEGMENTS];
        for (int s = 0; s < SEGMENTS; s++) {
            double[] profile = new double[depth];
            int lines = 0;
            for (int along = from + s * piece; along < from + (s + 1) * piece; along += 2) {
                lines++;
                for (int inward = STEP; inward < depth - STEP; inward++) {
                    profile[inward] += distance(lab.at(side, along, inward + STEP), lab.at(side, along, inward - STEP));
                }
            }
            for (int inward = 0; inward < depth; inward++) {
                profile[inward] /= lines;
            }
            positions[s] = firstPeak(profile);
        }
        double[] found = Arrays.stream(positions).filter(p -> !Double.isNaN(p)).sorted().toArray();
        if (found.length < AGREEING) {
            return "no clear inner edge of the %s border (borderless or full-art card, glare or a sleeve; found in %d of %d pieces)"
                    .formatted(side.label(), found.length, SEGMENTS);
        }
        // The largest group of pieces within BOW_PX of one of them; two groups that size is no answer.
        double[] agreeing = new double[0];
        boolean tied = false;
        for (double centre : found) {
            double[] group = Arrays.stream(found).filter(p -> Math.abs(p - centre) <= BOW_PX).toArray();
            if (group.length > agreeing.length) {
                agreeing = group;
                tied = false;
            } else if (group.length == agreeing.length && group[0] > agreeing[agreeing.length - 1]) {
                tied = true;
            }
        }
        if (agreeing.length < AGREEING || tied) {
            return "the inner edge of the %s border isn't straight along the side (%d of %d pieces agree, from %.0f to %.0f px)"
                    .formatted(side.label(), agreeing.length, SEGMENTS, found[0], found[found.length - 1]);
        }
        return new double[] {percentile(agreeing, 0.5), agreeing[agreeing.length - 1] - agreeing[0]};
    }

    /**
     * The first clear peak of a side's averaged colour change, in from the card's edge: the highest
     * point within 6 px either way, standing well above the median of the border before it (foil and
     * print texture give a border some change of its own: a gold foil border's is about a third of
     * its inner edge's). Sub-pixel by fitting a parabola through the peak; NaN when there is none.
     */
    private static double firstPeak(double[] profile) {
        int start = INNER_FROM;
        for (int i = start + 5; i < profile.length - 7; i++) {
            boolean highest = true;
            for (int j = i - 6; j <= i + 6 && highest; j++) {
                highest = profile[j] <= profile[i];
            }
            if (!highest) {
                continue;
            }
            double floor = percentile(Arrays.copyOfRange(profile, start, i - 4), 0.5);
            if (profile[i] < Math.max(MIN_EDGE, PEAK_OVER_BORDER * floor + MIN_EDGE)) {
                continue;
            }
            double denominator = profile[i - 1] - 2 * profile[i] + profile[i + 1];
            double offset = denominator == 0 ? 0 : 0.5 * (profile[i - 1] - profile[i + 1]) / denominator;
            return i + Math.max(-0.5, Math.min(0.5, offset));
        }
        return Double.NaN;
    }

    /** First index in [from, to) where {@code run} values in a row exceed the threshold, or -1. */
    private static int firstSustained(double[] d, int from, int to, double threshold, int run) {
        int count = 0;
        for (int i = from; i < to && i < d.length; i++) {
            count = d[i] > threshold ? count + 1 : 0;
            if (count == run) {
                return i - run + 1;
            }
        }
        return -1;
    }

    /**
     * Least squares fit of inward = a + b·along, refitted twice without points more than 2 px off.
     *
     * @return {a, b, points kept, rms residual of those}, or null with fewer than 10 points
     */
    private static double[] robustLine(List<double[]> points) {
        List<double[]> kept = points;
        double[] fit = null;
        for (int round = 0; round < 3; round++) {
            if (kept.size() < 10) {
                return null;
            }
            double n = kept.size();
            double sx = 0;
            double sy = 0;
            double sxx = 0;
            double sxy = 0;
            for (double[] p : kept) {
                sx += p[0];
                sy += p[1];
                sxx += p[0] * p[0];
                sxy += p[0] * p[1];
            }
            double b = (n * sxy - sx * sy) / (n * sxx - sx * sx);
            double a = (sy - b * sx) / n;
            fit = new double[] {a, b};
            double[] line = fit;
            kept = points.stream().filter(p -> Math.abs(p[1] - (line[0] + line[1] * p[0])) <= 2).toList();
        }
        double[] line = fit;
        double rms = Math.sqrt(kept.stream().mapToDouble(p -> Math.pow(p[1] - (line[0] + line[1] * p[0]), 2)).average().orElse(99));
        return new double[] {fit[0], fit[1], kept.size(), rms};
    }

    /**
     * A side's fitted line, inward = a + b·along in the side's own terms, as A·x + B·y = C in canvas
     * pixels. Right and bottom count inward from the far edge of the canvas.
     */
    private static double[] general(Side side, double[] line, int width, int height) {
        double a = line[0];
        double b = line[1];
        return switch (side) {
            case LEFT -> new double[] {1, -b, a};
            case RIGHT -> new double[] {1, b, width - 1 - a};
            case TOP -> new double[] {-b, 1, a};
            case BOTTOM -> new double[] {b, 1, height - 1 - a};
        };
    }

    private static Point intersect(double[] p, double[] q) {
        double det = p[0] * q[1] - p[1] * q[0];
        return new Point((p[2] * q[1] - p[1] * q[2]) / det, (p[0] * q[2] - p[2] * q[0]) / det);
    }

    private static double[] median(List<double[]> samples) {
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            int channel = c;
            out[c] = percentile(samples.stream().mapToDouble(v -> v[channel]).sorted().toArray(), 0.5);
        }
        return out;
    }

    /** Linear-interpolated percentile of a copy. */
    private static double percentile(double[] values, double q) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double position = q * (sorted.length - 1);
        int low = (int) Math.floor(position);
        int high = Math.min(sorted.length - 1, low + 1);
        return sorted[low] + (position - low) * (sorted[high] - sorted[low]);
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static double distance(double[] a, double[] b) {
        double dl = a[0] - b[0];
        double da = a[1] - b[1];
        double db = a[2] - b[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    private static <T extends Mat> T track(List<Mat> owned, T mat) {
        owned.add(mat);
        return mat;
    }

    /**
     * A straightened image in 8-bit Lab, read by side: along the side, and inward from its edge. Each
     * side reads a copy averaged over {@link #SMOOTH_ALONG} pixels along it, which evens out foil
     * sparkle, print dots and cloth texture without softening the edges that run along the side.
     */
    private record Lab(byte[] alongY, byte[] alongX, int width, int height) {

        static Lab of(Mat bgr, List<Mat> owned) {
            Mat lab = track(owned, new Mat());
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab);
            return new Lab(smoothed(lab, new Size(1, SMOOTH_ALONG), owned), smoothed(lab, new Size(SMOOTH_ALONG, 1), owned),
                    lab.cols(), lab.rows());
        }

        private static byte[] smoothed(Mat lab, Size kernel, List<Mat> owned) {
            Mat out = track(owned, new Mat());
            Imgproc.blur(lab, out, kernel);
            byte[] pixels = new byte[out.cols() * out.rows() * 3];
            out.get(0, 0, pixels);
            return pixels;
        }

        /** {@code along} counts from the image's top or left edge; {@code inward} from the side's own edge. */
        double[] at(Side side, int along, int inward) {
            return switch (side) {
                case LEFT -> pixel(alongY, inward, along);
                case RIGHT -> pixel(alongY, width - 1 - inward, along);
                case TOP -> pixel(alongX, along, inward);
                case BOTTOM -> pixel(alongX, along, height - 1 - inward);
            };
        }

        private double[] pixel(byte[] pixels, int x, int y) {
            int cx = Math.max(0, Math.min(width - 1, x));
            int cy = Math.max(0, Math.min(height - 1, y));
            int i = (cy * width + cx) * 3;
            return new double[] {pixels[i] & 0xff, pixels[i + 1] & 0xff, pixels[i + 2] & 0xff};
        }
    }
}

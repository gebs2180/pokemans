package com.pokemans.app.intake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import nu.pattern.OpenCV;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Finds the whole card in a photo and measures it: the four corners, how much of the frame it
 * fills, a perspective-corrected crop, and sharpness and glare on the card itself. A photo where no
 * whole card is found (cut off, too close to the frame edge, no card at all) gives no detection.
 *
 * <p>Centering is not measured here. The corners are only accurate to a few pixels on real
 * backgrounds, which is enough for the crop but shifts border widths by several percent: the
 * condition module's centering measurer starts from them ({@link CardOutline}) and refines the edges
 * at full resolution.
 *
 * <p>Detection runs on a downscaled copy; the crop is taken from the full-resolution image. OpenCV
 * applies the EXIF orientation when decoding, so corners are in display orientation.
 */
class CardDetector {

    /** Standard card: 63 × 88 mm. */
    static final double CARD_ASPECT = 63.0 / 88.0;
    static final int CROP_WIDTH = 1000;
    static final int CROP_HEIGHT = (int) Math.round(CROP_WIDTH / CARD_ASPECT);

    static {
        OpenCV.loadLocally();
    }

    record Corner(double x, double y) {
    }

    /**
     * @param corners       top-left, top-right, bottom-right, bottom-left, in full-resolution pixels
     * @param coverage      card area as a share of the frame
     * @param aspect        measured width / height (a card is about 0.716)
     * @param blurScore     variance of the Laplacian on the card crop
     * @param glareFraction share of blown-out pixels on the card crop
     * @param cropJpeg      the perspective-corrected card, JPEG
     */
    record Detection(List<Corner> corners, double coverage, double aspect, double blurScore, double glareFraction,
                     byte[] cropJpeg) {
    }

    private final QualityProperties properties;

    CardDetector(QualityProperties properties) {
        this.properties = properties;
    }

    Optional<Detection> detect(byte[] imageBytes) {
        MatOfByte encoded = new MatOfByte(imageBytes);
        Mat full = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        List<Mat> owned = new ArrayList<>(List.of(encoded, full));
        try {
            if (full.empty()) {
                return Optional.empty();
            }
            Point[] fullQuad = locate(full, owned);
            if (fullQuad == null) {
                return Optional.empty();
            }
            double coverage = Imgproc.contourArea(new MatOfPoint2f(fullQuad)) / ((double) full.cols() * full.rows());
            Mat crop = warp(full, fullQuad, owned);
            Mat cropGray = track(owned, new Mat());
            Imgproc.cvtColor(crop, cropGray, Imgproc.COLOR_BGR2GRAY);

            return Optional.of(new Detection(
                    Arrays.stream(fullQuad).map(p -> new Corner(p.x, p.y)).toList(),
                    coverage,
                    aspect(fullQuad),
                    blurScore(cropGray, owned),
                    glareFraction(cropGray, properties.glareLuma(), owned),
                    jpeg(crop)));
        } finally {
            owned.forEach(Mat::release);
        }
    }

    /**
     * The card's corners (TL, TR, BR, BL, card upright) in full-resolution pixels, found on a
     * downscaled copy; null when there is no whole card. Accurate to a few downscaled pixels.
     */
    Point[] locate(Mat full) {
        List<Mat> owned = new ArrayList<>();
        try {
            return locate(full, owned);
        } finally {
            owned.forEach(Mat::release);
        }
    }

    private Point[] locate(Mat full, List<Mat> owned) {
        double scale = Math.min(1.0, (double) properties.cardAnalysisLongSidePx() / Math.max(full.cols(), full.rows()));
        Mat small = track(owned, new Mat());
        Imgproc.resize(full, small, new Size(Math.round(full.cols() * scale), Math.round(full.rows() * scale)), 0, 0, Imgproc.INTER_AREA);
        Point[] quad = findCard(small, owned);
        return quad == null ? null : Arrays.stream(quad).map(p -> new Point(p.x / scale, p.y / scale)).toArray(Point[]::new);
    }

    /**
     * The largest convex four-sided outline with a card's aspect ratio. Null when there is none, or
     * when the card sits too close to the frame edge to be sure it is whole: a card cut off along one
     * edge can still show a complete inner border, which looks like a card.
     */
    private Point[] findCard(Mat small, List<Mat> owned) {
        // Edges per colour channel plus saturation: a blue border on a dark cloth is a weak edge in
        // grayscale but a strong one in the blue and saturation channels. The median blur removes
        // fabric and wood-grain texture that would otherwise join up with the card outline.
        Mat blurred = track(owned, new Mat());
        Imgproc.medianBlur(small, blurred, 7);
        List<Mat> channels = new ArrayList<>();
        Core.split(blurred, channels);
        Mat hsv = track(owned, new Mat());
        Imgproc.cvtColor(blurred, hsv, Imgproc.COLOR_BGR2HSV);
        List<Mat> hsvChannels = new ArrayList<>();
        Core.split(hsv, hsvChannels);
        owned.addAll(channels);
        owned.addAll(hsvChannels);
        channels.add(hsvChannels.get(1));

        Point[] best = null;
        double bestArea = 0;
        for (double[] thresholds : new double[][] {{30, 90}, {60, 180}}) {
            Mat edges = track(owned, Mat.zeros(small.size(), CvType.CV_8U));
            for (Mat channel : channels) {
                Mat channelEdges = track(owned, new Mat());
                Imgproc.Canny(channel, channelEdges, thresholds[0], thresholds[1]);
                Core.bitwise_or(edges, channelEdges, edges);
            }
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE,
                    track(owned, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5))));

            List<MatOfPoint> contours = new ArrayList<>();
            Imgproc.findContours(edges, contours, track(owned, new Mat()), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            owned.addAll(contours);
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                if (area < 0.03 * small.total() || area <= bestArea) {
                    continue;
                }
                Point[] quad = quadrilateral(contour, owned);
                if (quad == null) {
                    continue;
                }
                double quadArea = Imgproc.contourArea(new MatOfPoint2f(quad));
                if (area / quadArea < 0.85 || Math.abs(aspect(quad) - CARD_ASPECT) > properties.maxCardAspectError()
                        || touchesFrame(quad, small.cols(), small.rows())) {
                    continue;
                }
                best = quad;
                bestArea = area;
            }
        }

        // Preferred: the card as the region that differs in colour from the background. It finds
        // outlines the edge pass misses or overshoots (sparkly holo borders, dark textured cloth, folds
        // in the cloth joining the card's edge), and tells a card running off the frame apart from a
        // whole one. The edge pass is the fallback when the background isn't plain enough.
        Region region = foreground(blurred, owned);
        if (region != null && region.quad() != null) {
            best = region.quad();
            bestArea = Imgproc.contourArea(new MatOfPoint2f(best));
        }
        if (best == null) {
            return null;
        }
        if (region != null && region.touchesFrame() && region.area() > 1.2 * bestArea && contains(region.contour(), best)) {
            return null; // what we found is inside a card that runs off the photo: an inner box, not the card
        }
        if (!clearOfFrameEdge(best, small.cols(), small.rows())) {
            return null;
        }
        return best;
    }

    /**
     * The largest region that differs from the background.
     *
     * @param quad         its card-shaped outline, or null if it isn't card-shaped or touches the frame
     * @param touchesFrame the region runs into the edge of the photo
     */
    private record Region(MatOfPoint contour, double area, Point[] quad, boolean touchesFrame) {
    }

    /**
     * Segments the card from the background by colour: the background colour is the median of a strip
     * around the photo's edge (the capture standard asks for a plain background), and a pixel is card
     * when its Lab distance from that colour is well above how much the background itself varies. A
     * fixed split such as Otsu's would separate bright from dark instead, and lose a navy border on
     * black cloth. The largest blob is taken.
     */
    private Region foreground(Mat blurred, List<Mat> owned) {
        Mat lab = track(owned, new Mat());
        Imgproc.cvtColor(blurred, lab, Imgproc.COLOR_BGR2Lab);
        int width = lab.cols();
        int height = lab.rows();
        byte[] pixels = new byte[width * height * 3];
        lab.get(0, 0, pixels);

        int strip = Math.max(2, (int) Math.round(0.03 * Math.min(width, height)));
        List<int[]> samples = new ArrayList<>();
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                if (x < strip || y < strip || x >= width - strip || y >= height - strip) {
                    int i = (y * width + x) * 3;
                    samples.add(new int[] {pixels[i] & 0xff, pixels[i + 1] & 0xff, pixels[i + 2] & 0xff});
                }
            }
        }
        int[] background = new int[3];
        for (int c = 0; c < 3; c++) {
            int channel = c;
            int[] values = samples.stream().mapToInt(v -> v[channel]).sorted().toArray();
            background[c] = values[values.length / 2];
        }

        byte[] distance = new byte[width * height];
        for (int p = 0; p < width * height; p++) {
            double dl = (pixels[p * 3] & 0xff) - background[0];
            double da = (pixels[p * 3 + 1] & 0xff) - background[1];
            double db = (pixels[p * 3 + 2] & 0xff) - background[2];
            distance[p] = (byte) Math.min(255, (int) Math.round(Math.sqrt(dl * dl + da * da + db * db) * 2));
        }
        int[] borderDistances = new int[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            int[] v = samples.get(i);
            double dl = v[0] - background[0];
            double da = v[1] - background[1];
            double db = v[2] - background[2];
            borderDistances[i] = (int) Math.min(255, Math.round(Math.sqrt(dl * dl + da * da + db * db) * 2));
        }
        Arrays.sort(borderDistances);
        double noise = borderDistances[(int) (0.95 * (borderDistances.length - 1))];
        double cut = Math.max(16, Math.min(120, noise * 1.5 + 6));
        Mat mask = track(owned, new Mat(height, width, CvType.CV_8U));
        mask.put(0, 0, distance);
        Imgproc.threshold(mask, mask, cut, 255, Imgproc.THRESH_BINARY);
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE,
                track(owned, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(15, 15))));
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN,
                track(owned, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5))));

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(mask, contours, track(owned, new Mat()), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        owned.addAll(contours);
        MatOfPoint largest = contours.stream().max((a, b) -> Double.compare(Imgproc.contourArea(a), Imgproc.contourArea(b))).orElse(null);
        if (largest == null || Imgproc.contourArea(largest) < 0.03 * width * height) {
            return null;
        }
        double area = Imgproc.contourArea(largest);
        boolean touches = Arrays.stream(largest.toArray()).anyMatch(p -> p.x < 2 || p.y < 2 || p.x > width - 3 || p.y > height - 3);
        Point[] quad = quadrilateral(largest, owned);
        if (quad != null) {
            double quadArea = Imgproc.contourArea(new MatOfPoint2f(quad));
            if (touches || area / quadArea < 0.85 || Math.abs(aspect(quad) - CARD_ASPECT) > properties.maxCardAspectError()) {
                quad = null;
            }
        }
        return new Region(largest, area, quad, touches);
    }

    /** Every corner of the quad lies inside (or on) the contour. */
    private static boolean contains(MatOfPoint contour, Point[] quad) {
        MatOfPoint2f outline = new MatOfPoint2f(contour.toArray());
        try {
            return Arrays.stream(quad).allMatch(p -> Imgproc.pointPolygonTest(outline, p, false) >= 0);
        } finally {
            outline.release();
        }
    }

    /** Ordered corners (TL, TR, BR, BL, card upright) if the outline simplifies to a convex quadrilateral. */
    private static Point[] quadrilateral(MatOfPoint contour, List<Mat> owned) {
        MatOfInt hullIdx = track(owned, new MatOfInt());
        Imgproc.convexHull(contour, hullIdx);
        Point[] points = contour.toArray();
        Point[] hull = Arrays.stream(hullIdx.toArray()).mapToObj(i -> points[i]).toArray(Point[]::new);
        MatOfPoint2f hull2f = track(owned, new MatOfPoint2f(hull));
        double perimeter = Imgproc.arcLength(hull2f, true);
        for (double epsilon = 0.01; epsilon <= 0.06; epsilon += 0.01) {
            MatOfPoint2f approx = track(owned, new MatOfPoint2f());
            Imgproc.approxPolyDP(hull2f, approx, epsilon * perimeter, true);
            if (approx.total() == 4) {
                return upright(approx.toArray());
            }
            if (approx.total() < 4) {
                return null;
            }
        }
        return null;
    }

    /**
     * TL, TR, BR, BL with the card's short side on top (a landscape card is turned upright): clockwise
     * around the centre, starting from the corner nearest the image's top-left.
     */
    private static Point[] upright(Point[] p) {
        double cx = Arrays.stream(p).mapToDouble(q -> q.x).average().orElseThrow();
        double cy = Arrays.stream(p).mapToDouble(q -> q.y).average().orElseThrow();
        Point[] clockwise = p.clone();
        Arrays.sort(clockwise, (a, b) -> Double.compare(Math.atan2(a.y - cy, a.x - cx), Math.atan2(b.y - cy, b.x - cx)));
        int first = 0;
        for (int i = 1; i < 4; i++) {
            if (clockwise[i].x + clockwise[i].y < clockwise[first].x + clockwise[first].y) {
                first = i;
            }
        }
        Point[] ordered = new Point[4];
        for (int i = 0; i < 4; i++) {
            ordered[i] = clockwise[(first + i) % 4];
        }
        if (distance(ordered[0], ordered[1]) > distance(ordered[0], ordered[3])) {
            ordered = new Point[] {ordered[1], ordered[2], ordered[3], ordered[0]};
        }
        return ordered;
    }

    /** A corner on (or within 2 px of) the frame edge: the outline runs off the photo, so it isn't a whole card. */
    private static boolean touchesFrame(Point[] quad, int width, int height) {
        for (Point p : quad) {
            if (p.x < 2 || p.y < 2 || p.x > width - 3 || p.y > height - 3) {
                return true;
            }
        }
        return false;
    }

    /** Mean width over mean height. */
    private static double aspect(Point[] q) {
        double width = (distance(q[0], q[1]) + distance(q[3], q[2])) / 2;
        double height = (distance(q[0], q[3]) + distance(q[1], q[2])) / 2;
        return height == 0 ? 0 : width / height;
    }

    /** The card, enlarged about its centre by the required margin, still fits inside the frame. */
    private boolean clearOfFrameEdge(Point[] quad, int width, int height) {
        double cx = Arrays.stream(quad).mapToDouble(p -> p.x).average().orElseThrow();
        double cy = Arrays.stream(quad).mapToDouble(p -> p.y).average().orElseThrow();
        double grow = 1 + 2 * properties.minCardMarginFraction();
        for (Point p : quad) {
            double x = cx + (p.x - cx) * grow;
            double y = cy + (p.y - cy) * grow;
            if (x < 0 || y < 0 || x > width - 1 || y > height - 1) {
                return false;
            }
        }
        return true;
    }

    private static Mat warp(Mat full, Point[] quad, List<Mat> owned) {
        Mat src = track(owned, new MatOfPoint2f(quad));
        Mat dst = track(owned, new MatOfPoint2f(
                new Point(0, 0), new Point(CROP_WIDTH, 0), new Point(CROP_WIDTH, CROP_HEIGHT), new Point(0, CROP_HEIGHT)));
        Mat transform = track(owned, Imgproc.getPerspectiveTransform(src, dst));
        Mat crop = track(owned, new Mat());
        Imgproc.warpPerspective(full, crop, transform, new Size(CROP_WIDTH, CROP_HEIGHT), Imgproc.INTER_AREA);
        return crop;
    }

    /** Laplacian variance inside the card, inset 3% so the card edge against the background doesn't count. */
    private static double blurScore(Mat cropGray, List<Mat> owned) {
        Mat inner = track(owned, inset(cropGray, 0.03));
        Mat laplacian = track(owned, new Mat());
        Imgproc.Laplacian(inner, laplacian, CvType.CV_64F);
        MatOfDouble mean = track(owned, new MatOfDouble());
        MatOfDouble stdDev = track(owned, new MatOfDouble());
        Core.meanStdDev(laplacian, mean, stdDev);
        double sd = stdDev.get(0, 0)[0];
        return sd * sd;
    }

    private static double glareFraction(Mat cropGray, int threshold, List<Mat> owned) {
        Mat inner = track(owned, inset(cropGray, 0.03));
        Mat bright = track(owned, new Mat());
        Imgproc.threshold(inner, bright, threshold - 1, 255, Imgproc.THRESH_BINARY);
        return (double) Core.countNonZero(bright) / inner.total();
    }

    private static Mat inset(Mat image, double fraction) {
        int dx = (int) Math.round(image.cols() * fraction);
        int dy = (int) Math.round(image.rows() * fraction);
        return image.submat(dy, image.rows() - dy, dx, image.cols() - dx);
    }

    /**
     * The photo decoded and written again as a JPEG, so none of the upload's metadata (EXIF, with the
     * place it was taken) comes along; the EXIF orientation is applied first. Null if it doesn't decode.
     * Call only on images {@link com.pokemans.app.storage.ImageGuard} let through.
     */
    static byte[] withoutMetadata(byte[] imageBytes) {
        MatOfByte encoded = new MatOfByte(imageBytes);
        Mat image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        try {
            return image.empty() ? null : jpeg(image);
        } finally {
            image.release();
            encoded.release();
        }
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

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static <T extends Mat> T track(List<Mat> owned, T mat) {
        owned.add(mat);
        return mat;
    }
}

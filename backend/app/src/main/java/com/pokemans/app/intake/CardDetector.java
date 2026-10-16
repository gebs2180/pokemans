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
 * <p>Centering is not measured here yet. The corners are only accurate to a few pixels on real
 * backgrounds, which is enough for the crop but shifts border widths by several percent. Centering
 * comes from the pre-grading providers until it can be validated against the controlled PSA set.
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
        Mat full = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
        List<Mat> owned = new ArrayList<>(List.of(full));
        try {
            if (full.empty()) {
                return Optional.empty();
            }
            double scale = Math.min(1.0, (double) properties.cardAnalysisLongSidePx() / Math.max(full.cols(), full.rows()));
            Mat small = track(owned, new Mat());
            Imgproc.resize(full, small, new Size(Math.round(full.cols() * scale), Math.round(full.rows() * scale)), 0, 0, Imgproc.INTER_AREA);

            Point[] quad = findCard(small, owned);
            if (quad == null) {
                return Optional.empty();
            }

            Point[] fullQuad = Arrays.stream(quad).map(p -> new Point(p.x / scale, p.y / scale)).toArray(Point[]::new);
            double coverage = Imgproc.contourArea(new MatOfPoint2f(quad)) / ((double) small.cols() * small.rows());
            Mat crop = warp(full, fullQuad, owned);
            Mat cropGray = track(owned, new Mat());
            Imgproc.cvtColor(crop, cropGray, Imgproc.COLOR_BGR2GRAY);

            return Optional.of(new Detection(
                    Arrays.stream(fullQuad).map(p -> new Corner(p.x, p.y)).toList(),
                    coverage,
                    aspect(quad),
                    blurScore(cropGray, owned),
                    glareFraction(cropGray, properties.glareLuma(), owned),
                    jpeg(crop)));
        } finally {
            owned.forEach(Mat::release);
        }
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
        if (best == null || !clearOfFrameEdge(best, small.cols(), small.rows())) {
            return null;
        }
        return best;
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

package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import nu.pattern.OpenCV;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * The clean back on synthetic backs: a made-up "printed design" (random shapes and text, never a real card back), copies
 * of it photographed slightly off-square with other lighting, and scratches drawn on.
 */
class CleanBackTest {

    static {
        OpenCV.loadLocally();
    }

    private static final int W = CleanBack.WIDTH;
    private static final int H = CleanBack.HEIGHT;
    private static final int X0 = CleanBack.MARGIN_X;
    private static final int Y0 = CleanBack.MARGIN_Y;
    private static final int CW = CleanBack.CARD_WIDTH;
    private static final int CH = CleanBack.CARD_HEIGHT;

    /** A scratch drawn on one copy, in its own surface frame. */
    record Scratch(Point from, Point to) {
    }

    private static Mat referenceDesign;
    private static CleanBack.Reference reference;
    /** The scratches on the copies the reference is built from: each on one copy only. */
    private static final List<Scratch> REFERENCE_SCRATCHES = List.of(
            new Scratch(new Point(X0 + 150, Y0 + 300), new Point(X0 + 450, Y0 + 340)),
            new Scratch(new Point(X0 + 700, Y0 + 1300), new Point(X0 + 1000, Y0 + 1250)),
            new Scratch(new Point(X0 + 200, Y0 + 1000), new Point(X0 + 260, Y0 + 1300)));

    @BeforeAll
    static void buildReference() {
        referenceDesign = design(42);
        List<byte[]> copies = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Scratch scratch = i < REFERENCE_SCRATCHES.size() ? REFERENCE_SCRATCHES.get(i) : null;
            copies.add(photograph(referenceDesign, i + 1, scratch));
        }
        CleanBack.Built built = CleanBack.buildReference(copies, CleanBack.MIN_REFERENCE_BACKS);
        assertThat(built.png()).as("rejected %s", built.rejected()).isNotNull();
        assertThat(built.used()).hasSize(5);
        reference = CleanBack.Reference.of(built.png());
        assertThat(reference).isNotNull();
    }

    @Test
    void theScratchSurvivesAndThePrintCancels() {
        Scratch scratch = new Scratch(new Point(X0 + 300, Y0 + 700), new Point(X0 + 900, Y0 + 760));
        byte[] photo = photograph(referenceDesign, 11, scratch);

        CleanBack.Outcome outcome = CleanBack.clean(photo, reference);

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.jpeg()).isNotNull();
        assertThat(outcome.scores().correlation()).isGreaterThan(0.9);
        assertThat(outcome.scores().inliers()).isGreaterThanOrEqualTo(CleanBack.MIN_INLIERS);
        Mat clean = decode(outcome.jpeg());
        assertThat(clean.size()).isEqualTo(new Size(W, H));

        // Along the scratch: far from mid-grey.
        Rect onScratch = new Rect(X0 + 500, Y0 + 715, 200, 30);
        assertThat(meanDeviation(clean, onScratch)).isGreaterThan(15);

        // Away from it, where the print is busy: close to mid-grey, and well below what a plain high-pass leaves.
        Rect printOnly = new Rect(X0 + 300, Y0 + 1050, 400, 150);
        double cleanDev = meanDeviation(clean, printOnly);
        double highPassDev = meanDeviation(highPass(decode(photo)), printOnly);
        assertThat(highPassDev).isGreaterThan(4);
        assertThat(cleanDev).as("clean %s vs high-pass %s", cleanDev, highPassDev).isLessThan(highPassDev * 0.35);
        assertThat(outcome.scores().residual()).isGreaterThan(0);
    }

    @Test
    void marksOnSingleReferenceCopiesAreOutvoted() {
        byte[] pristine = photograph(referenceDesign, 21, null);

        CleanBack.Outcome outcome = CleanBack.clean(pristine, reference);

        assertThat(outcome.jpeg()).isNotNull();
        Mat clean = decode(outcome.jpeg());
        for (Scratch s : REFERENCE_SCRATCHES) {
            Point mid = new Point((s.from().x + s.to().x) / 2, (s.from().y + s.to().y) / 2);
            Rect around = new Rect((int) mid.x - 40, (int) mid.y - 15, 80, 30);
            assertThat(meanDeviation(clean, around)).as("at %s", mid).isLessThan(6);
        }
    }

    @Test
    void anotherDesignGetsNoImage() {
        byte[] otherBack = photograph(design(7), 3, null);

        CleanBack.Outcome outcome = CleanBack.clean(otherBack, reference);

        assertThat(outcome.jpeg()).isNull();
        assertThat(outcome.failure()).isNotNull();
        assertThat(outcome.scores().matches()).isNotNull();
    }

    @Test
    void aBlankCardGetsNoImage() {
        Mat blank = new Mat(H, W, CvType.CV_8U, new Scalar(30));
        Imgproc.rectangle(blank, new Point(X0, Y0), new Point(X0 + CW, Y0 + CH), new Scalar(170), Imgproc.FILLED);

        CleanBack.Outcome outcome = CleanBack.clean(jpeg(blank), reference);

        assertThat(outcome.jpeg()).isNull();
        assertThat(outcome.failure()).isEqualTo(CleanBack.Failure.TOO_FEW_FEATURES);
    }

    @Test
    void garbageDoesntDecode() {
        CleanBack.Outcome outcome = CleanBack.clean(new byte[] {1, 2, 3}, reference);

        assertThat(outcome.failure()).isEqualTo(CleanBack.Failure.DOESNT_DECODE);
        assertThat(outcome.scores()).isEqualTo(CleanBack.Scores.NONE);
    }

    @Test
    void tooFewBacksBuildNoReference() {
        List<byte[]> two = List.of(photograph(referenceDesign, 31, null), photograph(referenceDesign, 32, null));

        CleanBack.Built built = CleanBack.buildReference(two, CleanBack.MIN_REFERENCE_BACKS);

        assertThat(built.png()).isNull();
        assertThat(built.used()).hasSize(2);
    }

    @Test
    void anOddBackIsLeftOutOfTheReference() {
        List<byte[]> copies = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            copies.add(photograph(referenceDesign, 40 + i, null));
        }
        copies.add(photograph(design(9), 50, null));

        CleanBack.Built built = CleanBack.buildReference(copies, CleanBack.MIN_SEEDED_BACKS);

        assertThat(built.png()).isNotNull();
        assertThat(built.used()).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(built.rejected()).containsOnlyKeys(3);
    }

    /** A made-up back design in the surface frame, greyscale: busy enough for keypoints, the same for the same seed. */
    static Mat design(long seed) {
        Random random = new Random(seed);
        Mat m = new Mat(H, W, CvType.CV_8U, new Scalar(35));
        Imgproc.rectangle(m, new Point(X0, Y0), new Point(X0 + CW, Y0 + CH), new Scalar(120), Imgproc.FILLED);
        for (int i = 0; i < 220; i++) {
            Point c = new Point(X0 + 30 + random.nextInt(CW - 60), Y0 + 30 + random.nextInt(CH - 60));
            Scalar colour = new Scalar(40 + random.nextInt(200));
            switch (random.nextInt(4)) {
                case 0 -> Imgproc.circle(m, c, 8 + random.nextInt(50), colour, random.nextBoolean() ? Imgproc.FILLED : 3);
                case 1 -> Imgproc.rectangle(m, c, new Point(c.x + 10 + random.nextInt(80), c.y + 10 + random.nextInt(80)), colour,
                        random.nextBoolean() ? Imgproc.FILLED : 2);
                case 2 -> Imgproc.line(m, c, new Point(c.x + random.nextInt(160) - 80, c.y + random.nextInt(160) - 80), colour, 2);
                default -> Imgproc.putText(m, Long.toString(Math.abs(random.nextLong()) % 100000, 36), c,
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.8 + random.nextDouble(), colour, 2);
            }
        }
        // Soft shading, like the printed gradients a reviewer can mistake for dents.
        Mat shading = new Mat();
        m.copyTo(shading);
        Imgproc.GaussianBlur(shading, shading, new Size(0, 0), 3);
        return shading;
    }

    /**
     * The design as a phone photo's surface crop would show it: a few pixels off-square, other brightness and contrast, a
     * lighting gradient, noise, and an optional scratch drawn on afterwards (so in the photo's own frame).
     */
    static byte[] photograph(Mat design, long seed, Scratch scratch) {
        Random random = new Random(seed);
        Point[] from = {new Point(0, 0), new Point(W, 0), new Point(W, H), new Point(0, H)};
        Point[] to = new Point[4];
        for (int i = 0; i < 4; i++) {
            to[i] = new Point(from[i].x + random.nextInt(17) - 8, from[i].y + random.nextInt(17) - 8);
        }
        Mat h = Imgproc.getPerspectiveTransform(new MatOfPoint2f(from), new MatOfPoint2f(to));
        Mat warped = new Mat();
        Imgproc.warpPerspective(design, warped, h, new Size(W, H), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);

        Mat f = new Mat();
        warped.convertTo(f, CvType.CV_32F, 0.75 + 0.5 * random.nextDouble(), random.nextInt(30) - 15);
        Mat ramp = new Mat(H, W, CvType.CV_32F);
        float[] values = new float[W * H];
        double gx = random.nextDouble() * 30 - 15;
        double gy = random.nextDouble() * 30 - 15;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                values[y * W + x] = (float) (gx * x / W + gy * y / H);
            }
        }
        ramp.put(0, 0, values);
        Core.add(f, ramp, f);
        Mat noise = new Mat(H, W, CvType.CV_32F);
        Core.setRNGSeed((int) seed);
        Core.randn(noise, 0, 2);
        Core.add(f, noise, f);
        Mat out = new Mat();
        f.convertTo(out, CvType.CV_8U);
        if (scratch != null) {
            Imgproc.line(out, scratch.from(), scratch.to(), new Scalar(235), 3);
        }
        return jpeg(out);
    }

    /** Mean distance from mid-grey in a box. */
    private static double meanDeviation(Mat grey, Rect box) {
        Mat roi = new Mat(grey, box);
        Mat f = new Mat();
        roi.convertTo(f, CvType.CV_32F, 1, -128);
        Core.absdiff(f, new Scalar(0), f);
        return Core.mean(f).val[0];
    }

    /** What the surface view would show without a reference: the photo minus its blur, around mid-grey. */
    private static Mat highPass(Mat grey) {
        Mat f = new Mat();
        grey.convertTo(f, CvType.CV_32F);
        Mat blur = new Mat();
        Imgproc.GaussianBlur(f, blur, new Size(0, 0), 8);
        Core.subtract(f, blur, f);
        Mat out = new Mat();
        f.convertTo(out, CvType.CV_8U, 2, 128);
        return out;
    }

    private static Mat decode(byte[] bytes) {
        return Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_GRAYSCALE);
    }

    private static byte[] jpeg(Mat m) {
        MatOfByte out = new MatOfByte();
        Imgcodecs.imencode(".jpg", m, out, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92));
        return out.toArray();
    }
}

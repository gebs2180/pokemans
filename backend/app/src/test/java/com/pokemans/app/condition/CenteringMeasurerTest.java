package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pokemans.app.intake.CardOutline;
import com.pokemans.app.intake.QualityProperties;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

class CenteringMeasurerTest {

    static final QualityProperties QUALITY = new QualityProperties(1200, 1600, 1024, 100, 250, 0.03, 800, 0.05, 0.06, 0.25, 50);

    private static final int CARD_WIDTH = 1000;
    private static final int CARD_HEIGHT = (int) Math.round(CARD_WIDTH * 88.0 / 63.0);
    /** Within this many percentage points of the drawn ratio. */
    private static final double TOLERANCE = 1.0;

    private final CenteringMeasurer measurer = new CenteringMeasurer(new CardOutline(QUALITY));

    @Test
    void measuresACentredCardTurnedSlightly() {
        CenteringMeasurer.Result result = measurer.measure(png(photo(new Borders(48, 48, 48, 48), 2, true)));

        CenteringMeasurer.Measured m = measured(result);
        assertThat(m.leftShare()).isCloseTo(50, within(TOLERANCE));
        assertThat(m.topShare()).isCloseTo(50, within(TOLERANCE));
    }

    @Test
    void measuresAnOffCentreCardTurnedTheOtherWay() {
        // 35/65 left/right, 60/40 top/bottom, as drawn.
        CenteringMeasurer.Result result = measurer.measure(png(photo(new Borders(35, 65, 60, 40), -3, true)));

        CenteringMeasurer.Measured m = measured(result);
        assertThat(m.leftShare()).isCloseTo(35, within(TOLERANCE));
        assertThat(m.topShare()).isCloseTo(60, within(TOLERANCE));
        assertThat(m.worstShare()).isCloseTo(65, within(TOLERANCE));
        // 1000 px card drawn, measured at 20 px/mm on a 63 mm card: 35 px drawn is 35 × 1260/1000.
        assertThat(m.left()).isCloseTo(35 * 1.26, within(1.5));
    }

    @Test
    void measuresThroughAPerspectiveSkew() {
        BufferedImage flat = photo(new Borders(55, 45, 45, 55), 0, true);
        byte[] skewed = skew(flat);

        CenteringMeasurer.Measured m = measured(measurer.measure(skewed));

        assertThat(m.leftShare()).isCloseTo(55, within(TOLERANCE));
        assertThat(m.topShare()).isCloseTo(45, within(TOLERANCE));
    }

    @Test
    void aBorderlessCardIsUnmeasured() {
        BufferedImage image = background(1600, 2200);
        drawFullArt(image, 2);
        CenteringMeasurer.Result result = measurer.measure(png(image));

        // Either nothing stands out as an edge, or what does isn't the same edge along the side.
        assertThat(result).isInstanceOf(CenteringMeasurer.Unmeasured.class);
        assertThat(((CenteringMeasurer.Unmeasured) result).reason()).containsAnyOf("no clear inner edge", "isn't straight");
    }

    @Test
    void aBorderWithNothingInsideItIsUnmeasured() {
        CenteringMeasurer.Result result = measurer.measure(png(photo(new Borders(48, 48, 48, 48), 2, false)));

        assertThat(result).isInstanceOf(CenteringMeasurer.Unmeasured.class);
        assertThat(((CenteringMeasurer.Unmeasured) result).reason()).contains("no clear inner edge");
    }

    @Test
    void oneSideMayRestOnThreePiecesWhenTheFrameFits() {
        BufferedImage image = photo(new Borders(48, 48, 48, 48), 0, true);
        drawUnevenFrame(image, true, 0);

        CenteringMeasurer.Measured m = measured(measurer.measure(png(image)));

        assertThat(m.leftShare()).isCloseTo(50, within(TOLERANCE));
        assertThat(m.fewestPieces()).isEqualTo(3);
    }

    @Test
    void twoSidesOnThreePiecesAreUnmeasured() {
        BufferedImage image = photo(new Borders(48, 48, 48, 48), 0, true);
        drawUnevenFrame(image, true, 0);
        drawUnevenFrame(image, false, 0);

        assertThat(measurer.measure(png(image))).isInstanceOf(CenteringMeasurer.Unmeasured.class);
    }

    @Test
    void aSideOnThreePiecesThatDoesNotFitTheFrameIsUnmeasured() {
        BufferedImage image = photo(new Borders(48, 48, 48, 48), 0, true);
        drawUnevenFrame(image, true, 20);

        CenteringMeasurer.Result result = measurer.measure(png(image));

        assertThat(result).isInstanceOf(CenteringMeasurer.Unmeasured.class);
        assertThat(((CenteringMeasurer.Unmeasured) result).reason()).contains("right border isn't straight");
    }

    @Test
    void aPhotoWithNoCardIsUnmeasured() {
        CenteringMeasurer.Result result = measurer.measure(png(background(1600, 2200)));

        assertThat(result).isEqualTo(new CenteringMeasurer.Unmeasured("no whole card found in the photo"));
    }

    static CenteringMeasurer.Measured measured(CenteringMeasurer.Result result) {
        assertThat(result).isInstanceOf(CenteringMeasurer.Measured.class);
        return (CenteringMeasurer.Measured) result;
    }

    /** Border widths in pixels of the drawn 1000 px wide card. */
    record Borders(int left, int right, int top, int bottom) {
    }

    /**
     * A card on a dark speckled table: a yellow border of the given widths around patterned art,
     * turned by {@code degrees} about its centre. Without art, the border colour fills the card.
     */
    static BufferedImage photo(Borders borders, double degrees, boolean art) {
        BufferedImage image = background(1600, 2200);
        double cx = 800;
        double cy = 1100;
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setTransform(AffineTransform.getRotateInstance(Math.toRadians(degrees), cx, cy));
            int left = (int) Math.round(cx - CARD_WIDTH / 2.0);
            int top = (int) Math.round(cy - CARD_HEIGHT / 2.0);
            int artLeft = left + borders.left();
            int artTop = top + borders.top();
            int artRight = left + CARD_WIDTH - borders.right();
            int artBottom = top + CARD_HEIGHT - borders.bottom();
            g.setColor(new Color(0xF2C230));
            g.fillRoundRect(left, top, CARD_WIDTH, CARD_HEIGHT, 40, 40);
            if (art) {
                int cell = 16;
                for (int y = artTop; y < artBottom; y += cell) {
                    for (int x = artLeft; x < artRight; x += cell) {
                        boolean dark = (((x - artLeft) / cell) + ((y - artTop) / cell)) % 2 == 0;
                        g.setColor(dark ? new Color(0x9040C0) : new Color(0xC8D0E0));
                        g.fillRect(x, y, Math.min(cell, artRight - x), Math.min(cell, artBottom - y));
                    }
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * Widens the border of one side of an unturned {@link #photo} in steps over the upper five of the
     * side's eight pieces, so no two of them agree, and by {@code rest} over the lower three.
     */
    private static void drawUnevenFrame(BufferedImage image, boolean right, int rest) {
        int top = 1100 - CARD_HEIGHT / 2;
        int from = top + CARD_HEIGHT / 10;
        int piece = (CARD_HEIGHT * 8 / 10) / 8;
        int[] extras = {30, 65, 100, 135, 170, rest, rest, rest};
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(0xF2C230));
            for (int i = 0; i < extras.length; i++) {
                int x = right ? 800 + CARD_WIDTH / 2 - 48 - extras[i] : 800 - CARD_WIDTH / 2 + 48;
                g.fillRect(x, from + i * piece, extras[i], piece + 1);
            }
        } finally {
            g.dispose();
        }
    }

    /** A full-art card: overlapping shapes of many colours and sizes right up to the card's edge. */
    static void drawFullArt(BufferedImage image, double degrees) {
        double cx = 800;
        double cy = 1100;
        int left = (int) Math.round(cx - CARD_WIDTH / 2.0);
        int top = (int) Math.round(cy - CARD_HEIGHT / 2.0);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setTransform(AffineTransform.getRotateInstance(Math.toRadians(degrees), cx, cy));
            g.setClip(new java.awt.geom.RoundRectangle2D.Double(left, top, CARD_WIDTH, CARD_HEIGHT, 40, 40));
            g.setColor(new Color(0xE0A0C0));
            g.fillRect(left, top, CARD_WIDTH, CARD_HEIGHT);
            Random random = new Random(7);
            for (int i = 0; i < 400; i++) {
                g.setColor(new Color(80 + random.nextInt(176), 80 + random.nextInt(176), 80 + random.nextInt(176)));
                int size = 20 + random.nextInt(200);
                g.fillOval(left - 100 + random.nextInt(CARD_WIDTH + 100), top - 100 + random.nextInt(CARD_HEIGHT + 100), size,
                        size / 2 + random.nextInt(size));
            }
        } finally {
            g.dispose();
        }
    }

    static BufferedImage background(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = 35 + random.nextInt(12);
                image.setRGB(x, y, new Color(v, v, v + 4).getRGB());
            }
        }
        return image;
    }

    static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The photo as if taken from slightly off to one side and below: the top edge comes out shorter. */
    private static byte[] skew(BufferedImage image) {
        Mat src = Imgcodecs.imdecode(new MatOfByte(png(image)), Imgcodecs.IMREAD_COLOR);
        int w = src.cols();
        int h = src.rows();
        Mat transform = Imgproc.getPerspectiveTransform(
                new MatOfPoint2f(new Point(0, 0), new Point(w, 0), new Point(w, h), new Point(0, h)),
                new MatOfPoint2f(new Point(60, 40), new Point(w - 30, 0), new Point(w, h), new Point(0, h - 20)));
        Mat out = new Mat();
        Imgproc.warpPerspective(src, out, transform, new Size(w, h), Imgproc.INTER_LINEAR);
        MatOfByte bytes = new MatOfByte();
        Imgcodecs.imencode(".png", out, bytes);
        return bytes.toArray();
    }
}

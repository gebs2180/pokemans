package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;

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

class QualityCheckerTest {

    private final QualityChecker checker = new QualityChecker(defaults());

    static QualityProperties defaults() {
        return new QualityProperties(1200, 1600, 1024, 100, 250, 0.03, 800, 0.05, 0.06, 0.25, 50);
    }

    /** A dark, lightly speckled table, like a cloth or desk. */
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

    /**
     * Draws a 63 × 88 card (yellow border, sharp patterned art) of the given width, centred on
     * (cx, cy) and turned by a few degrees, the way a hand-held photo comes out.
     */
    static BufferedImage withCard(BufferedImage image, int cardWidth, double cx, double cy, double degrees) {
        int cardHeight = (int) Math.round(cardWidth * 88.0 / 63.0);
        int border = (int) Math.round(cardWidth * 0.05);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setTransform(AffineTransform.getRotateInstance(Math.toRadians(degrees), cx, cy));
            int left = (int) Math.round(cx - cardWidth / 2.0);
            int top = (int) Math.round(cy - cardHeight / 2.0);
            g.setColor(new Color(0xF2C230));
            g.fillRoundRect(left, top, cardWidth, cardHeight, border, border);
            int cell = Math.max(4, cardWidth / 60);
            for (int y = top + border; y < top + cardHeight - border; y += cell) {
                for (int x = left + border; x < left + cardWidth - border; x += cell) {
                    boolean dark = (((x - left) / cell) + ((y - top) / cell)) % 2 == 0;
                    g.setColor(dark ? new Color(0x3050A0) : new Color(0xC8D0E0));
                    g.fillRect(x, y, Math.min(cell, left + cardWidth - border - x), Math.min(cell, top + cardHeight - border - y));
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    static BufferedImage goodPhoto() {
        return withCard(background(1600, 2200), 900, 800, 1100, 4);
    }

    /** Box blur by shrinking and enlarging again. */
    static BufferedImage blurred(BufferedImage image, int factor) {
        BufferedImage small = new BufferedImage(image.getWidth() / factor, image.getHeight() / factor, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, small.getWidth(), small.getHeight(), null);
        g.dispose();
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(small, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        return out;
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

    @Test
    void wholeSharpCardPasses() {
        QualityChecker.Result result = checker.check(png(goodPhoto()));

        assertThat(result.reasons()).isEmpty();
        assertThat(result.width()).isEqualTo(1600);
        assertThat(result.height()).isEqualTo(2200);
        CardDetector.Detection card = result.card();
        assertThat(card).isNotNull();
        assertThat(card.aspect()).isBetween(0.69, 0.74);
        assertThat(card.coverage()).isBetween(0.30, 0.36);
        assertThat(card.blurScore()).isGreaterThan(50);
        assertThat(card.corners()).hasSize(4);
        assertThat(card.cropJpeg()).isNotEmpty();
    }

    @Test
    void halfACardIsNotFound() {
        BufferedImage image = withCard(background(1600, 2200), 900, 800, 300, 2); // top half off the frame

        assertThat(checker.check(png(image)).reasons()).containsExactly(RetakeReason.CARD_NOT_FOUND);
    }

    @Test
    void cardCutOffJustPastItsBorderIsNotFound() {
        // Only the outer border is cut off: the art box is whole and looks like a card on its own.
        BufferedImage image = withCard(background(1600, 2200), 900, 800, 1100, 0);
        image = image.getSubimage(0, 0, 1600 - (800 - 450) - 10, 2200); // right edge cuts the yellow border

        QualityChecker.Result result = checker.check(png(copy(image)));

        assertThat(result.reasons()).contains(RetakeReason.CARD_NOT_FOUND);
    }

    @Test
    void smallCardIsTooSmall() {
        BufferedImage image = withCard(background(1600, 2200), 500, 800, 1100, 3);

        QualityChecker.Result result = checker.check(png(image));

        assertThat(result.reasons()).containsExactly(RetakeReason.CARD_TOO_SMALL);
        assertThat(result.card().coverage()).isLessThan(0.25);
    }

    @Test
    void noCardIsNotFound() {
        assertThat(checker.check(png(background(1600, 2200))).reasons()).contains(RetakeReason.CARD_NOT_FOUND);
    }

    @Test
    void blurryCardNeedsRetake() {
        QualityChecker.Result result = checker.check(png(blurred(goodPhoto(), 6)));

        assertThat(result.card()).isNotNull();
        assertThat(result.reasons()).containsExactly(RetakeReason.BLURRY);
    }

    @Test
    void glareOnTheCardNeedsRetake() {
        BufferedImage image = goodPhoto();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillOval(600, 900, 300, 300); // about 7% of the card
        g.dispose();

        QualityChecker.Result result = checker.check(png(image));

        assertThat(result.reasons()).containsExactly(RetakeReason.GLARE);
        assertThat(result.card().glareFraction()).isBetween(0.04, 0.12);
    }

    @Test
    void smallPhotoNeedsRetake() {
        BufferedImage image = withCard(background(900, 1200), 500, 450, 600, 2);

        assertThat(checker.check(png(image)).reasons()).contains(RetakeReason.RESOLUTION_TOO_LOW);
    }

    @Test
    void aDecompressionBombIsUnreadableWithoutBeingDecoded() {
        // 30000 x 30000 claimed in a few dozen bytes: decoding it would need ~3.6 GB.
        assertThat(checker.check(com.pokemans.app.storage.ImageGuardTest.pngClaiming(30_000, 30_000)).reasons())
                .containsExactly(RetakeReason.UNREADABLE);
    }

    @Test
    void notAnImageIsUnreadable() {
        assertThat(checker.check("not an image".getBytes()).reasons()).containsExactly(RetakeReason.UNREADABLE);
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }
}

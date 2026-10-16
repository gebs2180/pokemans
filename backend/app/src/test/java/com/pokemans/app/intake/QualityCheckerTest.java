package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class QualityCheckerTest {

    private final QualityChecker checker = new QualityChecker(new QualityProperties(1200, 1600, 1024, 100, 250, 0.03));

    /** Fine checkerboard: lots of sharp edges, no blown-out pixels. */
    static BufferedImage sharp(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean dark = ((x / 8) + (y / 8)) % 2 == 0;
                image.setRGB(x, y, dark ? 0x202020 : 0xb0b0b0);
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

    @Test
    void sharpFullResolutionPhotoPasses() {
        QualityChecker.Result result = checker.check(png(sharp(1600, 2200)));

        assertThat(result.reasons()).isEmpty();
        assertThat(result.width()).isEqualTo(1600);
        assertThat(result.height()).isEqualTo(2200);
        assertThat(result.blurScore()).isGreaterThan(100);
        assertThat(result.glareFraction()).isZero();
    }

    @Test
    void smallPhotoNeedsRetake() {
        assertThat(checker.check(png(sharp(900, 1200))).reasons()).containsExactly(RetakeReason.RESOLUTION_TOO_LOW);
    }

    @Test
    void smoothPhotoIsBlurry() {
        BufferedImage image = new BufferedImage(1600, 2200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int v = 60 + (x * 120 / image.getWidth());
                image.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }

        QualityChecker.Result result = checker.check(png(image));

        assertThat(result.reasons()).containsExactly(RetakeReason.BLURRY);
        assertThat(result.blurScore()).isLessThan(1);
    }

    @Test
    void blownOutPatchIsGlare() {
        BufferedImage image = sharp(1600, 2200);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(200, 200, 600, 600); // about 10% of the frame
        g.dispose();

        QualityChecker.Result result = checker.check(png(image));

        assertThat(result.reasons()).containsExactly(RetakeReason.GLARE);
        assertThat(result.glareFraction()).isBetween(0.08, 0.12);
    }

    @Test
    void notAnImageIsUnreadable() {
        assertThat(checker.check("not an image".getBytes()).reasons()).containsExactly(RetakeReason.UNREADABLE);
    }
}

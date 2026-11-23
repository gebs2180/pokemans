package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

class ImageHashTest {

    /** A card on the usual table, with artwork of large shapes that differ by seed: a different card, shot the same way. */
    static BufferedImage card(long seed) {
        BufferedImage image = QualityCheckerTest.withCard(QualityCheckerTest.background(1600, 2200), 900, 800, 1100, 0);
        Random random = new Random(seed);
        Graphics2D g = image.createGraphics();
        try {
            for (int i = 0; i < 14; i++) {
                g.setColor(new Color(random.nextInt(0xFFFFFF)));
                int x = 400 + random.nextInt(700);
                int y = 520 + random.nextInt(1000);
                g.fillOval(x, y, 120 + random.nextInt(260), 120 + random.nextInt(260));
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** As a messaging app sends it on: smaller, and a lossy JPEG. */
    static BufferedImage resentAsJpeg(BufferedImage image) throws IOException {
        BufferedImage smaller = new BufferedImage(image.getWidth() * 3 / 4, image.getHeight() * 3 / 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = smaller.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, 0, 0, smaller.getWidth(), smaller.getHeight(), null);
        g.dispose();
        return ImageIO.read(new ByteArrayInputStream(jpeg(smaller, 0.6f)));
    }

    static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    @Test
    void theSamePhotoResentSmallerAndRecompressedStillLooksAlike() throws IOException {
        BufferedImage original = card(1);
        String hash = ImageHash.of(original);

        assertThat(hash).hasSize(64);
        assertThat(ImageHash.distance(hash, ImageHash.of(original))).isZero();
        assertThat(ImageHash.distance(hash, ImageHash.of(resentAsJpeg(original)))).isLessThanOrEqualTo(ImageHash.LOOKALIKE_DISTANCE);
    }

    @Test
    void differentCardsShotTheSameWayDoNot() {
        for (long seed = 2; seed <= 6; seed++) {
            assertThat(ImageHash.distance(ImageHash.of(card(1)), ImageHash.of(card(seed))))
                    .as("card %d against card 1", seed)
                    .isGreaterThan(ImageHash.LOOKALIKE_DISTANCE);
        }
    }

    @Test
    void aMissingHashIsNeverClose() {
        assertThat(ImageHash.distance(null, ImageHash.of(card(1)))).isEqualTo(Integer.MAX_VALUE);
        assertThat(ImageHash.distance("00", "0000")).isEqualTo(Integer.MAX_VALUE);
    }
}

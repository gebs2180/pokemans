package com.pokemans.app.condition;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/** Shrinks a photo for a provider with a smaller upload limit than ours. The stored original is untouched. */
final class ImageResizer {

    private ImageResizer() {
    }

    /** A JPEG of the image, scaled down in 15% steps until it fits under {@code maxBytes}. */
    static PreGradeProvider.Image jpegUnder(PreGradeProvider.Image image, int maxBytes) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image.bytes()));
            if (source == null) {
                throw new IllegalArgumentException("Not a readable image");
            }
            double scale = 1.0;
            while (true) {
                byte[] jpeg = jpeg(source, scale);
                if (jpeg.length <= maxBytes || scale < 0.3) {
                    return new PreGradeProvider.Image(jpeg, "image/jpeg");
                }
                scale *= 0.85;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] jpeg(BufferedImage source, double scale) throws IOException {
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(target, "jpg", out);
        return out.toByteArray();
    }
}

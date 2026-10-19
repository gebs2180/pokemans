package com.pokemans.app.reporting;

import com.pokemans.app.storage.ImageGuard;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Card photos shrunk for the insurer report and embedded as data URIs. */
final class Thumbnails {

    static final int WIDTH = 400;

    private Thumbnails() {
    }

    static String dataUri(byte[] image) {
        if (ImageGuard.safeHeader(image).isEmpty()) {
            throw new IllegalStateException("Stored photo is not an image the report can embed");
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
            if (source == null) {
                throw new IllegalStateException("Stored photo could not be decoded");
            }
            int width = Math.min(WIDTH, source.getWidth());
            int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source, 0, 0, width, height, java.awt.Color.WHITE, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(scaled, "jpg", out);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

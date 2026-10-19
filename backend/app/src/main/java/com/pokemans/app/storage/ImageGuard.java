package com.pokemans.app.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Reads an uploaded image's format and size from its header, without decoding the pixels, so a small
 * file that claims a huge canvas (a decompression bomb) is refused before anything allocates it.
 * Only JPEG and PNG are accepted, whatever Content-Type the upload declared.
 */
public final class ImageGuard {

    /** About twice a 48 MP phone photo; decoding this as RGB needs roughly 300 MB. */
    public static final long MAX_PIXELS = 100_000_000L;
    public static final int MAX_SIDE_PX = 16_000;
    private static final Set<String> FORMATS = Set.of("jpeg", "png");

    public record Header(String format, int width, int height) {
    }

    private ImageGuard() {
    }

    /** The header of a JPEG or PNG within the size limits, or empty if it's anything else. */
    public static Optional<Header> safeHeader(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!FORMATS.contains(format)) {
                    return Optional.empty();
                }
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_SIDE_PX || height > MAX_SIDE_PX
                        || (long) width * height > MAX_PIXELS) {
                    return Optional.empty();
                }
                return Optional.of(new Header(format, width, height));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}

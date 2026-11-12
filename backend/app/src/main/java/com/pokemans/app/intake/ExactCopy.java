package com.pokemans.app.intake;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.MetadataException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * A photo exactly as uploaded, pixel for pixel, without its metadata: the image data is copied byte for byte, never
 * decoded or re-encoded, and only the segments that say where and when it was taken (EXIF, XMP, comments, text) are
 * left out. Buyers see these: the photos are the return contract ("doesn't match its photos"), so they must show every
 * detail the seller's camera caught (UX review finding 8). The EXIF orientation is the one tag kept, so the photo
 * still shows the right way up.
 *
 * <p>{@link #withoutLocation} does the same for the stored original, keeping also when it was taken (DateTimeOriginal,
 * with its sub-seconds and time offset), which is pre-loss proof and what {@link ExifReader#capturedAt} reads. GPS, the
 * camera's make, model and serial, XMP, comments and the rest go (privacy review 2026-11-12).
 */
final class ExactCopy {

    /** PNG chunks that describe the pixels; text, time and EXIF chunks are left out. */
    private static final Set<String> PNG_KEPT = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT",
            "pHYs", "bKGD");
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** The EXIF capture-time tags kept on originals: DateTimeOriginal, OffsetTimeOriginal, SubSecTimeOriginal. */
    private static final int[] CAPTURE_TAGS = {0x9003, 0x9011, 0x9291};

    private ExactCopy() {
    }

    /**
     * The original without its location and device metadata, keeping orientation and capture time; null when it isn't a
     * JPEG or PNG this can read. The image data is copied byte for byte, as in {@link #of}.
     */
    static byte[] withoutLocation(byte[] image) {
        Metadata metadata = metadata(image);
        return copy(image, new Kept(orientation(metadata), captureTags(metadata)));
    }

    /**
     * The photo without its metadata, or null when it isn't a JPEG or PNG this can read. Call only on images
     * {@link com.pokemans.app.storage.ImageGuard} let through.
     */
    static byte[] of(byte[] image) {
        return copy(image, new Kept(orientation(image), List.of()));
    }

    private static byte[] copy(byte[] image, Kept kept) {
        try {
            if (image.length > 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8) {
                return jpeg(image, kept.tiff());
            }
            if (image.length > PNG_SIGNATURE.length && Arrays.equals(image, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
                return png(image, kept.tiff());
            }
        } catch (IndexOutOfBoundsException e) {
            // Truncated or malformed: no copy rather than a broken one.
        }
        return null;
    }

    /** The EXIF orientation, 1 (as stored) when there is none. */
    static int orientation(byte[] image) {
        return orientation(metadata(image));
    }

    private static int orientation(Metadata metadata) {
        ExifIFD0Directory ifd0 = metadata == null ? null : metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        try {
            return ifd0 != null && ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION) ? ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION) : 1;
        } catch (MetadataException e) {
            return 1;
        }
    }

    private static List<AsciiTag> captureTags(Metadata metadata) {
        ExifSubIFDDirectory exif = metadata == null ? null : metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        List<AsciiTag> tags = new ArrayList<>();
        if (exif != null) {
            for (int tag : CAPTURE_TAGS) {
                String value = exif.getString(tag);
                if (value != null && !value.isBlank()) {
                    tags.add(new AsciiTag(tag, value.strip()));
                }
            }
        }
        return tags;
    }

    private static Metadata metadata(byte[] image) {
        try {
            return ImageMetadataReader.readMetadata(new ByteArrayInputStream(image));
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            return null;
        }
    }

    /** What survives the copy: an orientation other than 1 and the capture-time tags, as one TIFF block, or none. */
    private record Kept(int orientation, List<AsciiTag> exif) {
        byte[] tiff() {
            return orientation == 1 && exif.isEmpty() ? null : ExactCopy.tiff(orientation, exif);
        }
    }

    private record AsciiTag(int tag, String value) {
        byte[] bytes() {
            byte[] text = value.getBytes(StandardCharsets.ISO_8859_1);
            return Arrays.copyOf(text, text.length + 1);   // NUL-terminated
        }
    }

    /**
     * Keeps the tables, frame and scans, and of the application segments only the JFIF header, the ICC colour profile
     * and Adobe's colour transform: everything a decoder needs to show the same pixels. Stops at the end of the image,
     * so data a phone appends after it (a second image, a depth or gain map) is left out too.
     */
    private static byte[] jpeg(byte[] in, byte[] tiff) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(0xFF);
        out.write(0xD8);
        boolean orientationWritten = tiff == null;
        int i = 2;
        while (true) {
            if ((in[i] & 0xFF) != 0xFF) {
                return null;
            }
            while ((in[i] & 0xFF) == 0xFF) {
                i++;
            }
            int marker = in[i++] & 0xFF;
            if (marker == 0xD9) {
                out.write(0xFF);
                out.write(0xD9);
                return out.toByteArray();
            }
            int length = ((in[i] & 0xFF) << 8) | (in[i + 1] & 0xFF);
            int end = i + length;
            if (length < 2 || end > in.length) {
                return null;
            }
            boolean keep = switch (marker) {
                case 0xE0 -> startsWith(in, i + 2, "JFIF\0");
                case 0xE2 -> startsWith(in, i + 2, "ICC_PROFILE\0");
                case 0xEE -> startsWith(in, i + 2, "Adobe");
                // Tables, frames, restart interval, arithmetic conditioning, number of lines, scans.
                case 0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCC, 0xCD, 0xCE, 0xCF, 0xDB, 0xDC, 0xDD, 0xDA -> true;
                default -> false;
            };
            if (!orientationWritten && marker != 0xE0) {
                // After the JFIF header, which must come first, and before anything else.
                out.writeBytes(exifSegment(tiff));
                orientationWritten = true;
            }
            if (keep) {
                out.write(0xFF);
                out.write(marker);
                out.write(in, i, length);
            }
            i = end;
            if (marker == 0xDA) {
                // The entropy-coded scan runs to the next marker that isn't a stuffed byte (FF00) or a restart (FFD0-D7).
                int start = i;
                while (!((in[i] & 0xFF) == 0xFF && in[i + 1] != 0 && ((in[i + 1] & 0xFF) < 0xD0 || (in[i + 1] & 0xFF) > 0xD7)
                        && (in[i + 1] & 0xFF) != 0xFF)) {
                    i++;
                }
                out.write(in, start, i - start);
            }
        }
    }

    private static byte[] png(byte[] in, byte[] tiff) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.writeBytes(PNG_SIGNATURE);
        int i = PNG_SIGNATURE.length;
        while (true) {
            int length = ByteBuffer.wrap(in, i, 4).getInt();
            int end = i + 12 + length;
            if (length < 0 || end > in.length) {
                return null;
            }
            String type = new String(in, i + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("IDAT") && tiff != null) {
                // Ancillary chunks about the image go before its data.
                out.writeBytes(pngChunk("eXIf", tiff));
                tiff = null;
            }
            if (PNG_KEPT.contains(type)) {
                out.write(in, i, end - i);
            }
            i = end;
            if (type.equals("IEND")) {
                return out.toByteArray();
            }
        }
    }

    /** An APP1 segment holding the EXIF block. */
    private static byte[] exifSegment(byte[] tiff) {
        int length = 2 + 6 + tiff.length;
        ByteBuffer b = ByteBuffer.allocate(2 + length);
        b.put((byte) 0xFF).put((byte) 0xE1).putShort((short) length).put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)).put(tiff);
        return b.array();
    }

    /**
     * A big-endian TIFF block: IFD0 with the orientation (0x0112, a SHORT) when it isn't 1, and, when there are capture
     * tags, a pointer (0x8769) to an EXIF IFD holding them as ASCII. With only an orientation this is the same 26 bytes
     * {@link #of} has always written.
     */
    private static byte[] tiff(int orientation, List<AsciiTag> exif) {
        int ifd0Entries = (orientation != 1 ? 1 : 0) + (exif.isEmpty() ? 0 : 1);
        int exifIfd = 8 + 2 + 12 * ifd0Entries + 4;
        int data = exif.isEmpty() ? exifIfd : exifIfd + 2 + 12 * exif.size() + 4;
        int size = data;
        for (AsciiTag t : exif) {
            int n = t.bytes().length;
            size += n > 4 ? n + (n & 1) : 0;   // values over 4 bytes live after the IFDs, word-aligned
        }
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        b.putShort((short) ifd0Entries);
        if (orientation != 1) {
            b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        }
        if (!exif.isEmpty()) {
            b.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(exifIfd);
        }
        b.putInt(0);
        if (!exif.isEmpty()) {
            b.putShort((short) exif.size());
            int next = data;
            for (AsciiTag t : exif) {   // CAPTURE_TAGS is in tag order, as TIFF requires
                byte[] v = t.bytes();
                b.putShort((short) t.tag()).putShort((short) 2).putInt(v.length);
                if (v.length <= 4) {
                    b.put(Arrays.copyOf(v, 4));
                } else {
                    b.putInt(next);
                    int at = b.position();
                    b.position(next).put(v).position(at);
                    next += v.length + (v.length & 1);
                }
            }
            b.putInt(0);
        }
        return b.array();
    }

    private static byte[] pngChunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue()).array();
    }

    private static boolean startsWith(byte[] in, int at, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.ISO_8859_1);
        return at + p.length <= in.length && Arrays.equals(in, at, at + p.length, p, 0, p.length);
    }
}

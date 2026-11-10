package com.pokemans.app.intake;

/** Synthetic card photos for integration tests in other modules. */
public final class TestImages {

    private TestImages() {
    }

    /** A sharp, whole card on a table: passes every check. */
    public static byte[] goodFront() {
        return QualityCheckerTest.png(QualityCheckerTest.goodPhoto());
    }

    /** Another passing photo with different pixels (the duplicate check compares hashes). */
    public static byte[] goodBack() {
        return QualityCheckerTest.png(QualityCheckerTest.withCard(QualityCheckerTest.background(1700, 2300), 950, 850, 1150, -3));
    }

    /** More passing photos, each with different pixels: {@code n} from 0 to 4. */
    public static byte[] goodRetake(int n) {
        return QualityCheckerTest.png(QualityCheckerTest.withCard(QualityCheckerTest.background(1650, 2250), 920, 825, 1125, -2 + n));
    }

    /** What {@link #geotaggedJpeg} hides in its EXIF segment: no copy anyone else gets may contain it. */
    public static final String GPS_SECRET = "GPS 39.7817N 89.6501W";

    /** The photo as a JPEG with an EXIF segment after the start marker, as a phone camera writes one. */
    public static byte[] geotaggedJpeg(byte[] png) throws java.io.IOException {
        java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(decoded.getWidth(), decoded.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(decoded, 0, 0, null);
        java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(rgb, "jpg", jpeg);
        byte[] plain = jpeg.toByteArray();
        // "Exif  ", then a big-endian TIFF header with an empty first IFD, then the payload a GPS IFD would hold.
        java.io.ByteArrayOutputStream exif = new java.io.ByteArrayOutputStream();
        exif.write("Exif  ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        exif.write(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0});
        exif.write(GPS_SECRET.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        int length = exif.size() + 2;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(plain, 0, 2); // SOI
        out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        exif.writeTo(out);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }
}

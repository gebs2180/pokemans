package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;
import org.junit.jupiter.api.Test;

/** Buyers' full-size photos: the same pixels as the upload, byte for byte in the image data, without what it says about the seller. */
class ExactCopyTest {

    private static final String SECRET = "GPS 39.7817N 89.6501W";

    @Test
    void aJpegKeepsItsImageDataAndOrientationAndLosesTheRest() throws Exception {
        byte[] plain = jpeg(false);
        byte[] upload = withSegments(plain, exif(6, SECRET), comment("taken at home"));

        byte[] copy = ExactCopy.of(upload);

        assertThat(text(copy)).doesNotContain(SECRET).doesNotContain("taken at home");
        assertThat(ExactCopy.orientation(copy)).isEqualTo(6);
        assertThat(pixels(copy)).isEqualTo(pixels(upload));
        // Everything after the headers is the upload's own bytes: nothing was decoded and encoded again.
        assertThat(tail(copy, plain.length - 20)).isEqualTo(tail(plain, plain.length - 20));
    }

    @Test
    void anUprightJpegGetsNoExifAtAll() throws Exception {
        byte[] plain = jpeg(false);

        byte[] copy = ExactCopy.of(withSegments(plain, exif(1, SECRET)));

        assertThat(copy).isEqualTo(plain);
    }

    @Test
    void aProgressiveJpegKeepsEveryScan() throws Exception {
        byte[] plain = jpeg(true);

        byte[] copy = ExactCopy.of(withSegments(plain, exif(1, SECRET)));

        assertThat(copy).isEqualTo(plain);
        assertThat(pixels(copy)).isEqualTo(pixels(plain));
    }

    @Test
    void dataAfterTheEndOfTheImageIsLeftOut() throws Exception {
        byte[] plain = jpeg(false);
        byte[] appended = Arrays.copyOf(plain, plain.length + SECRET.length());
        System.arraycopy(SECRET.getBytes(StandardCharsets.ISO_8859_1), 0, appended, plain.length, SECRET.length());

        assertThat(ExactCopy.of(appended)).isEqualTo(plain);
    }

    @Test
    void aPngLosesItsTextAndKeepsItsPixels() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image(), "png", png);
        byte[] plain = png.toByteArray();
        // A tEXt chunk after IHDR (8-byte signature, then IHDR's 25 bytes).
        byte[] upload = ByteBuffer.allocate(plain.length + 12 + 5 + SECRET.length()).put(plain, 0, 33)
                .put(chunk("tEXt", ("Note\0" + SECRET).getBytes(StandardCharsets.ISO_8859_1))).put(plain, 33, plain.length - 33).array();

        byte[] copy = ExactCopy.of(upload);

        assertThat(text(copy)).doesNotContain(SECRET);
        assertThat(copy).isEqualTo(plain);
    }

    @Test
    void theStoredOriginalKeepsItsCaptureTimeAndOrientationAndLosesLocationAndDevice() throws Exception {
        byte[] plain = jpeg(false);
        byte[] upload = withSegments(plain, cameraExif(), comment("taken at home"));
        assertThat(ExifReader.capturedAt(upload)).isNotNull();
        assertThat(metadata(upload).getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();

        byte[] stored = ExactCopy.withoutLocation(upload);

        assertThat(ExifReader.capturedAt(stored)).isEqualTo(ExifReader.capturedAt(upload));
        assertThat(ExactCopy.orientation(stored)).isEqualTo(6);
        assertThat(metadata(stored).getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(text(stored)).doesNotContain("PhoneMaker").doesNotContain("taken at home").doesNotContain("2026:11:01");
        assertThat(pixels(stored)).isEqualTo(pixels(upload));
        assertThat(tail(stored, plain.length - 20)).isEqualTo(tail(plain, plain.length - 20));
        // Run again (the backfill after an upload already stripped it): nothing more to take out.
        assertThat(ExactCopy.withoutLocation(stored)).isEqualTo(stored);
        // The buyers' copy still loses the capture time too.
        assertThat(ExifReader.capturedAt(ExactCopy.of(upload))).isNull();
    }

    @Test
    void aPhotoWithNothingToKeepIsStoredWithoutExif() throws Exception {
        byte[] plain = jpeg(false);

        assertThat(ExactCopy.withoutLocation(withSegments(plain, exif(1, SECRET)))).isEqualTo(plain);
        assertThat(ExactCopy.withoutLocation(plain)).isEqualTo(plain);
    }

    @Test
    void somethingThatIsntAnImageGetsNoCopy() {
        assertThat(ExactCopy.of("not an image".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(ExactCopy.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F})).isNull();
    }

    private static BufferedImage image() {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 64, 48);
        g.setColor(Color.BLUE);
        g.fillOval(10, 8, 30, 20);
        g.dispose();
        return img;
    }

    private static byte[] jpeg(boolean progressive) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (progressive) {
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image(), null, null), param);
        }
        writer.dispose();
        return out.toByteArray();
    }

    /** The JPEG with these segments after its JFIF header, as a phone camera writes them. */
    private static byte[] withSegments(byte[] jpeg, byte[]... segments) {
        int jfifEnd = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, jfifEnd);
        for (byte[] s : segments) {
            out.writeBytes(s);
        }
        out.write(jpeg, jfifEnd, jpeg.length - jfifEnd);
        return out.toByteArray();
    }

    /** APP1 Exif: IFD0 with the orientation, then bytes standing in for a GPS IFD. */
    private static byte[] exif(int orientation, String payload) {
        byte[] p = payload.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer body = ByteBuffer.allocate(6 + 26 + p.length);
        body.put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        body.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8).putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0).putInt(0);
        body.put(p);
        return segment(0xE1, body.array());
    }

    /**
     * APP1 Exif as a phone writes it: IFD0 with the maker, orientation 6 and pointers to an Exif IFD (DateTimeOriginal)
     * and a GPS IFD (GPSDateStamp, standing in for the coordinates).
     */
    static byte[] cameraExif() {
        ByteBuffer t = ByteBuffer.allocate(140);
        t.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        t.putShort((short) 4);                                                                     // IFD0 at 8
        t.putShort((short) 0x010F).putShort((short) 2).putInt(11).putInt(62);                      // Make
        t.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0);
        t.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(73);                      // Exif IFD
        t.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(111);                     // GPS IFD
        t.putInt(0);
        t.put("PhoneMaker\0".getBytes(StandardCharsets.ISO_8859_1));                             // 62..73
        t.putShort((short) 1);                                                                     // Exif IFD at 73
        t.putShort((short) 0x9003).putShort((short) 2).putInt(20).putInt(91);
        t.putInt(0);
        t.put("2026:10:30 14:05:09\0".getBytes(StandardCharsets.ISO_8859_1));                    // 91..111
        t.putShort((short) 1);                                                                     // GPS IFD at 111
        t.putShort((short) 0x001D).putShort((short) 2).putInt(11).putInt(129);
        t.putInt(0);
        t.put("2026:11:01\0".getBytes(StandardCharsets.ISO_8859_1));                             // 129..140
        ByteBuffer body = ByteBuffer.allocate(6 + 140).put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)).put(t.array());
        return segment(0xE1, body.array());
    }

    private static Metadata metadata(byte[] image) throws Exception {
        return ImageMetadataReader.readMetadata(new ByteArrayInputStream(image));
    }

    private static byte[] comment(String text) {
        return segment(0xFE, text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static byte[] segment(int marker, byte[] body) {
        return ByteBuffer.allocate(4 + body.length).put((byte) 0xFF).put((byte) marker).putShort((short) (body.length + 2)).put(body).array();
    }

    private static byte[] chunk(String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(type.getBytes(StandardCharsets.ISO_8859_1));
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(type.getBytes(StandardCharsets.ISO_8859_1)).put(data)
                .putInt((int) crc.getValue()).array();
    }

    private static int[] pixels(byte[] image) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private static byte[] tail(byte[] b, int n) {
        return Arrays.copyOfRange(b, b.length - n, b.length);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }
}

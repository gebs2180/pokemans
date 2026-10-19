package com.pokemans.app.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

public class ImageGuardTest {

    private static byte[] encode(String format, int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    /** A PNG signature and IHDR claiming the given canvas, with no pixel data: a few dozen bytes. */
    public static byte[] pngClaiming(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        ByteBuffer png = ByteBuffer.allocate(8 + 4 + 17 + 4);
        png.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})
                .putInt(13).put(ihdr.array()).putInt((int) crc.getValue());
        return png.array();
    }

    @Test
    void acceptsOrdinaryJpegAndPng() throws IOException {
        assertThat(ImageGuard.safeHeader(encode("jpg", 1600, 1200))).hasValueSatisfying(h -> {
            assertThat(h.format()).isEqualTo("jpeg");
            assertThat(h.width()).isEqualTo(1600);
        });
        assertThat(ImageGuard.safeHeader(encode("png", 64, 48))).hasValueSatisfying(h -> assertThat(h.format()).isEqualTo("png"));
    }

    @Test
    void refusesADecompressionBombFromItsHeaderAlone() {
        byte[] bomb = pngClaiming(30_000, 30_000);
        assertThat(bomb.length).isLessThan(100);
        assertThat(ImageGuard.safeHeader(bomb)).isEmpty();
        assertThat(ImageGuard.safeHeader(pngClaiming(12_000, 9_000))).isEmpty(); // 108 MP: under the side cap, over the pixel cap
        assertThat(ImageGuard.safeHeader(pngClaiming(20_000, 100))).isEmpty();   // one side too long
        assertThat(ImageGuard.safeHeader(pngClaiming(4_000, 3_000))).isPresent();
    }

    @Test
    void refusesOtherFormatsWhateverTheyClaimToBe() throws IOException {
        assertThat(ImageGuard.safeHeader(encode("gif", 64, 48))).isEmpty();
        assertThat(ImageGuard.safeHeader(encode("bmp", 64, 48))).isEmpty();
        assertThat(ImageGuard.safeHeader(encode("tiff", 64, 48))).isEmpty();
    }

    @Test
    void neverThrowsOnGarbage() throws IOException {
        Random random = new Random(7);
        byte[] jpeg = encode("jpg", 200, 100);
        assertThat(ImageGuard.safeHeader(new byte[0])).isEmpty();
        for (int i = 0; i < 500; i++) {
            byte[] bytes = random.nextBoolean()
                    ? Arrays.copyOf(jpeg, random.nextInt(jpeg.length))   // truncated
                    : jpeg.clone();
            for (int flips = random.nextInt(8); flips > 0; flips--) {
                bytes[random.nextInt(Math.max(1, Math.min(bytes.length, 64)))] ^= (byte) random.nextInt(256);
            }
            if (bytes.length > 0) {
                ImageGuard.safeHeader(bytes); // empty or a header, never an exception
            }
            byte[] noise = new byte[random.nextInt(256)];
            random.nextBytes(noise);
            assertThat(ImageGuard.safeHeader(noise)).isEmpty();
        }
    }
}

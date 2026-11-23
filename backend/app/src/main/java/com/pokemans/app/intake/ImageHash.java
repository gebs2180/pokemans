package com.pokemans.app.intake;

import java.awt.image.BufferedImage;
import java.util.HexFormat;

/**
 * A difference hash of a whole photo: 256 bits saying, for a 17×16 grid of block-mean greys, whether each cell is clearly
 * brighter than its right-hand neighbour. Re-saving, resizing or recompressing a photo barely changes it, so it finds the same
 * photo uploaded again where the exact hash can't (the walkthrough of 2026-11-23). It only warns: two copies of one
 * card, shot the same way, can come close too.
 */
final class ImageHash {

    private static final int WIDTH = 17;
    private static final int HEIGHT = 16;
    /** Grey levels two neighbours may differ by and still count as the same brightness. */
    private static final int FLAT = 3;

    /**
     * At most this many of the 256 bits differ for "looks like the same photo". Re-encoded copies differ by a handful;
     * different cards on the same background by far more (see ImageHashTest).
     */
    static final int LOOKALIKE_DISTANCE = 10;

    private ImageHash() {
    }

    /** The hash as 64 hex digits. */
    static String of(BufferedImage image) {
        // Each cell is the mean grey of its whole block of pixels, not a few sampled ones, so noise and JPEG blocks average out.
        long[][] sum = new long[HEIGHT][WIDTH];
        long[][] count = new long[HEIGHT][WIDTH];
        int w = image.getWidth();
        int h = image.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            int cy = (int) ((long) y * HEIGHT / h);
            for (int x = 0; x < w; x++) {
                int rgb = row[x];
                int grey = (299 * ((rgb >> 16) & 0xff) + 587 * ((rgb >> 8) & 0xff) + 114 * (rgb & 0xff)) / 1000;
                int cx = (int) ((long) x * WIDTH / w);
                sum[cy][cx] += grey;
                count[cy][cx]++;
            }
        }
        byte[] bits = new byte[(WIDTH - 1) * HEIGHT / 8];
        int i = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH - 1; x++, i++) {
                double left = (double) sum[y][x] / Math.max(1, count[y][x]);
                double right = (double) sum[y][x + 1] / Math.max(1, count[y][x + 1]);
                // Brighter by a margin: in flat areas (the table, a border) neighbours are equal give or take noise, and a
                // plain ">" would flip those bits at random between two copies of the same photo.
                if (left > right + FLAT) {
                    bits[i / 8] |= (byte) (1 << (7 - i % 8));
                }
            }
        }
        return HexFormat.of().formatHex(bits);
    }

    /** How many bits differ; Integer.MAX_VALUE when either is missing or they aren't the same length. */
    static int distance(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return Integer.MAX_VALUE;
        }
        byte[] x = HexFormat.of().parseHex(a);
        byte[] y = HexFormat.of().parseHex(b);
        int d = 0;
        for (int i = 0; i < x.length; i++) {
            d += Integer.bitCount((x[i] ^ y[i]) & 0xff);
        }
        return d;
    }
}

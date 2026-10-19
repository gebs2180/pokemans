package com.pokemans.app.intake;

import com.pokemans.app.storage.ImageGuard;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Capture-standard checks on one photo: resolution, a whole card in frame and large enough, blur
 * (variance of the Laplacian) and glare (share of blown-out pixels). Blur and glare are judged on the
 * card when one is found, otherwise on the whole frame. Pure image analysis, no I/O beyond decoding
 * the bytes it is given.
 *
 * <p>{@code blurScore} and {@code glareFraction} are always the whole-frame values, kept comparable
 * with photos checked before card detection existed; the card's own values are on {@link #card()}.
 */
class QualityChecker {

    /** @param card the detected card, or null when no whole card was found */
    record Result(Integer width, Integer height, Double blurScore, Double glareFraction, CardDetector.Detection card,
                  List<RetakeReason> reasons) {
    }

    private final QualityProperties properties;
    private final CardDetector detector;

    QualityChecker(QualityProperties properties) {
        this.properties = properties;
        this.detector = new CardDetector(properties);
    }

    Result check(byte[] imageBytes) {
        return check(imageBytes, true);
    }

    /**
     * @param wholeCardExpected false for photos that don't show one whole card (a lot spread out on a
     *                          table, a close-up of a corner); those skip the card checks
     */
    Result check(byte[] imageBytes, boolean wholeCardExpected) {
        // Header first: refuse non-JPEG/PNG bytes and decompression bombs before ImageIO or OpenCV decode them.
        if (ImageGuard.safeHeader(imageBytes).isEmpty()) {
            return new Result(null, null, null, null, null, List.of(RetakeReason.UNREADABLE));
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            image = null;
        }
        if (image == null) {
            return new Result(null, null, null, null, null, List.of(RetakeReason.UNREADABLE));
        }

        int width = image.getWidth();
        int height = image.getHeight();
        List<RetakeReason> reasons = new ArrayList<>();
        if (Math.min(width, height) < properties.minShortSidePx() || Math.max(width, height) < properties.minLongSidePx()) {
            reasons.add(RetakeReason.RESOLUTION_TOO_LOW);
        }

        int[][] luma = luma(downscale(image, properties.analysisLongSidePx()));
        double blur = laplacianVariance(luma);
        double glare = glareFraction(luma, properties.glareLuma());

        CardDetector.Detection card = wholeCardExpected ? detector.detect(imageBytes).orElse(null) : null;
        boolean blurry;
        boolean glary;
        if (card == null) {
            if (wholeCardExpected) {
                reasons.add(RetakeReason.CARD_NOT_FOUND);
            }
            blurry = blur < properties.minBlurScore();
            glary = glare > properties.maxGlareFraction();
        } else {
            if (card.coverage() < properties.minCardCoverage()) {
                reasons.add(RetakeReason.CARD_TOO_SMALL);
            }
            blurry = card.blurScore() < properties.minCardBlurScore();
            glary = card.glareFraction() > properties.maxGlareFraction();
        }
        if (blurry) {
            reasons.add(RetakeReason.BLURRY);
        }
        if (glary) {
            reasons.add(RetakeReason.GLARE);
        }
        return new Result(width, height, blur, glare, card, reasons);
    }

    private static BufferedImage downscale(BufferedImage image, int longSide) {
        int w = image.getWidth();
        int h = image.getHeight();
        double scale = Math.min(1.0, (double) longSide / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage target = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** Rec. 601 luma per pixel, [y][x]. */
    private static int[][] luma(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[][] luma = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                luma[y][x] = (299 * r + 587 * g + 114 * b) / 1000;
            }
        }
        return luma;
    }

    /** Variance of the 4-neighbour Laplacian over interior pixels: low means few sharp edges. */
    static double laplacianVariance(int[][] luma) {
        int h = luma.length;
        int w = h == 0 ? 0 : luma[0].length;
        if (h < 3 || w < 3) {
            return 0;
        }
        double sum = 0;
        double sumSquares = 0;
        long n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int lap = 4 * luma[y][x] - luma[y - 1][x] - luma[y + 1][x] - luma[y][x - 1] - luma[y][x + 1];
                sum += lap;
                sumSquares += (double) lap * lap;
                n++;
            }
        }
        double mean = sum / n;
        return sumSquares / n - mean * mean;
    }

    static double glareFraction(int[][] luma, int threshold) {
        long bright = 0;
        long n = 0;
        for (int[] row : luma) {
            for (int v : row) {
                if (v >= threshold) {
                    bright++;
                }
                n++;
            }
        }
        return n == 0 ? 0 : (double) bright / n;
    }
}

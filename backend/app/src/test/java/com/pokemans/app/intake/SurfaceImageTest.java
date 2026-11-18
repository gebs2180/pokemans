package com.pokemans.app.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import nu.pattern.OpenCV;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

/** The surface view's images: the local-contrast version differs from its source and keeps its size; the crop is capped by the source. */
class SurfaceImageTest {

    static {
        OpenCV.loadLocally();
    }

    @Test
    void theLocalContrastImageDiffersFromItsInputAndKeepsItsSize() {
        byte[] source = TestImages.goodFront();
        Mat input = decode(source, Imgcodecs.IMREAD_GRAYSCALE);

        byte[] detail = CardDetector.enhanceJpeg(source);

        assertThat(detail).isNotNull();
        Mat output = decode(detail, Imgcodecs.IMREAD_GRAYSCALE);
        assertThat(output.size()).isEqualTo(input.size());
        Mat difference = new Mat();
        Core.absdiff(input, output, difference);
        assertThat(Core.mean(difference).val[0]).as("mean absolute change per pixel").isGreaterThan(1.0);
    }

    @Test
    void theSurfaceCropIsCappedByTheSourceAndHasAnEnhancedTwin() {
        byte[] source = TestImages.goodFront();
        Mat decoded = decode(source, Imgcodecs.IMREAD_COLOR);
        List<CardDetector.Corner> corners = List.of(
                new CardDetector.Corner(0, 0), new CardDetector.Corner(decoded.cols() - 1, 0),
                new CardDetector.Corner(decoded.cols() - 1, decoded.rows() - 1), new CardDetector.Corner(0, decoded.rows() - 1));

        CardDetector.Surface surface = CardDetector.surface(source, corners);

        assertThat(surface).isNotNull();
        Mat hiRes = decode(surface.hiResJpeg(), Imgcodecs.IMREAD_COLOR);
        Mat detail = decode(surface.detailJpeg(), Imgcodecs.IMREAD_COLOR);
        double marginFactor = 1 + 2 * CardDetector.WIDE_MARGIN;
        assertThat(hiRes.cols()).isLessThanOrEqualTo(CardDetector.SURFACE_WIDTH);
        assertThat(hiRes.cols()).as("never more pixels across the card than the source has")
                .isLessThanOrEqualTo((int) Math.ceil(decoded.cols() * marginFactor) + 1);
        assertThat(detail.size()).isEqualTo(hiRes.size());
    }

    @Test
    void aSourceWithManyPixelsGivesTheFullTwoThousand() {
        Mat big = new Mat(3400, 2600, org.opencv.core.CvType.CV_8UC3, new org.opencv.core.Scalar(90, 120, 150));
        MatOfByte png = new MatOfByte();
        Imgcodecs.imencode(".png", big, png);
        List<CardDetector.Corner> corners = new ArrayList<>(List.of(
                new CardDetector.Corner(100, 100), new CardDetector.Corner(2500, 100),
                new CardDetector.Corner(2500, 3400 - 100), new CardDetector.Corner(100, 3400 - 100)));

        CardDetector.Surface surface = CardDetector.surface(png.toArray(), corners);

        Mat hiRes = decode(surface.hiResJpeg(), Imgcodecs.IMREAD_COLOR);
        assertThat(hiRes.cols()).isBetween(1990, 2000);
        assertThat(hiRes.rows()).isGreaterThan(CardDetector.CROP_HEIGHT + 2 * (int) (CardDetector.CROP_HEIGHT * CardDetector.WIDE_MARGIN));
    }

    private static Mat decode(byte[] bytes, int flags) {
        return Imgcodecs.imdecode(new MatOfByte(bytes), flags);
    }
}

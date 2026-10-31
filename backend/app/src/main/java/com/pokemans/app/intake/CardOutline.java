package com.pokemans.app.intake;

import java.util.Optional;
import org.opencv.core.Mat;
import org.opencv.core.Point;

/**
 * The card's outline in a photo, for modules that measure the card itself (condition's centering).
 * The same detection the photo checks use, so a photo that passed has an outline here.
 */
public final class CardOutline {

    private final CardDetector detector;

    public CardOutline(QualityProperties properties) {
        this.detector = new CardDetector(properties);
    }

    /**
     * The corners, top-left, top-right, bottom-right, bottom-left with the card upright, in the
     * image's pixels; empty when no whole card is found. Accurate to a few pixels of an 800 px copy,
     * so refine the edges before measuring anything finer than the crop.
     *
     * @param image a decoded BGR image, in display orientation
     */
    public Optional<Point[]> find(Mat image) {
        return Optional.ofNullable(detector.locate(image));
    }
}

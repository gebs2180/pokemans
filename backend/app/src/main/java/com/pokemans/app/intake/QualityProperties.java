package com.pokemans.app.intake;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Photo capture-standard thresholds. Starting values, to be calibrated against reviewer judgements:
 * every photo stores its raw metrics, so thresholds can be re-tuned and replayed later.
 *
 * @param minShortSidePx    smallest acceptable short side
 * @param minLongSidePx     smallest acceptable long side
 * @param analysisLongSidePx images are downscaled to this long side before blur and glare analysis
 * @param minBlurScore      variance of the Laplacian (on the downscaled grayscale image) below which a photo is blurry
 * @param glareLuma         luma (0–255) at or above which a pixel counts as blown out
 * @param maxGlareFraction  share of blown-out pixels above which a photo has glare
 * @param cardAnalysisLongSidePx images are downscaled to this long side to find the card outline
 * @param maxCardAspectError how far the measured width/height may be from a card's 0.716 (perspective, detection error)
 * @param minCardMarginFraction clear space required between the card and every frame edge, as a share of the
 *                          card's width or height; also rejects cards cut off just past their inner border
 * @param minCardCoverage   smallest share of the frame the card may fill
 * @param minCardBlurScore  variance of the Laplacian on the 1000 px wide card crop below which the card is blurry
 */
@ConfigurationProperties("pokemans.quality")
public record QualityProperties(
        @DefaultValue("1200") int minShortSidePx,
        @DefaultValue("1600") int minLongSidePx,
        @DefaultValue("1024") int analysisLongSidePx,
        @DefaultValue("100") double minBlurScore,
        @DefaultValue("250") int glareLuma,
        @DefaultValue("0.03") double maxGlareFraction,
        @DefaultValue("800") int cardAnalysisLongSidePx,
        @DefaultValue("0.05") double maxCardAspectError,
        @DefaultValue("0.06") double minCardMarginFraction,
        @DefaultValue("0.25") double minCardCoverage,
        @DefaultValue("50") double minCardBlurScore) {
}

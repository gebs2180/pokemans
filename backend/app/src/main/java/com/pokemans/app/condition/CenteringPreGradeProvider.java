package com.pokemans.app.condition;

import com.pokemans.app.intake.CardOutline;
import com.pokemans.app.intake.QualityProperties;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Our own centering measurement ({@link CenteringMeasurer}) on the passing front and back, run as one
 * more provider so it is stored and compared like the others. It reports centering only: the overall
 * and other sub-grades stay null, and the centering grade is the best PSA grade the measured centering
 * allows ({@link PsaCentering}), which caps the card's distribution and never raises it.
 *
 * <p>A side that can't be measured (full art, glare, sleeve) doesn't limit the cap; with neither side
 * measured the answer is a refusal with both reasons.
 */
@Component
@ConditionalOnBooleanProperty("pokemans.condition.centering.enabled")
class CenteringPreGradeProvider implements PreGradeProvider.Local {

    static final String NAME = "RAWMARK_CENTERING";
    /** Bump when the method changes, so stored measurements can be told apart. */
    static final String VERSION = "centering-3";
    /** The key, in each measured side's details, of the best grade that side allows on its own. */
    static final String SIDE_CAP = "psaCap";

    private final CenteringMeasurer measurer;
    private final double margin;

    @Autowired
    CenteringPreGradeProvider(ConditionProperties properties, QualityProperties quality) {
        this(new CenteringMeasurer(new CardOutline(quality)), properties.centering().measurementMargin());
    }

    CenteringPreGradeProvider(CenteringMeasurer measurer, double margin) {
        this.measurer = measurer;
        this.margin = margin;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Poll grade(Photos photos) {
        CenteringMeasurer.Result front = measurer.measure(photos.front().bytes());
        CenteringMeasurer.Result back = photos.back() == null
                ? new CenteringMeasurer.Unmeasured("no back photo") : measurer.measure(photos.back().bytes());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("frontCentering", withSideCap(describe(front), PsaCentering.cap(worstShare(front), null, margin)));
        details.put("backCentering", withSideCap(describe(back), PsaCentering.cap(null, worstShare(back), margin)));
        details.put("measurementMargin", margin);
        if (front instanceof CenteringMeasurer.Unmeasured(String frontReason)
                && back instanceof CenteringMeasurer.Unmeasured(String backReason)) {
            return Poll.failed("Centering not measured: front: " + frontReason + "; back: " + backReason, details);
        }
        Integer cap = PsaCentering.cap(worstShare(front), worstShare(back), margin);
        details.put("psaCenteringCap", cap);
        return Poll.done(new Reading(VERSION, null, null, cap.doubleValue(), null, null, null, details), details);
    }

    /**
     * What this side allows on its own, from the unrounded share: stored so a later retake that can't
     * measure this side keeps its limit exactly ({@link ProviderPrediction#centeringCap(java.util.List)}),
     * rather than recomputing it from the rounded {@code worstShare}, which could loosen it (review of
     * 2026-11, finding 5). Null for a side that wasn't measured.
     */
    private static Map<String, Object> withSideCap(Map<String, Object> side, Integer cap) {
        if (cap != null) {
            side.put(SIDE_CAP, cap);
        }
        return side;
    }

    private static Double worstShare(CenteringMeasurer.Result result) {
        return result instanceof CenteringMeasurer.Measured m ? m.worstShare() : null;
    }

    /** Ratios as PSA writes them (left/right, top/bottom), borders in mm, or why it wasn't measured. */
    static Map<String, Object> describe(CenteringMeasurer.Result result) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (result) {
            case CenteringMeasurer.Measured m -> {
                out.put("leftRight", ratio(m.leftShare()));
                out.put("topBottom", ratio(m.topShare()));
                out.put("worstShare", round(m.worstShare(), 1));
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("left", round(m.left() / CenteringMeasurer.PX_PER_MM, 2));
                mm.put("right", round(m.right() / CenteringMeasurer.PX_PER_MM, 2));
                mm.put("top", round(m.top() / CenteringMeasurer.PX_PER_MM, 2));
                mm.put("bottom", round(m.bottom() / CenteringMeasurer.PX_PER_MM, 2));
                out.put("bordersMm", mm);
                out.put("pxPerMm", CenteringMeasurer.PX_PER_MM);
                out.put("spreadPx", round(m.spreadPx(), 1));
                out.put("fewestPieces", m.fewestPieces());
            }
            case CenteringMeasurer.Unmeasured u -> out.put("unmeasured", u.reason());
        }
        return out;
    }

    /** "55.3/44.7": the first number is the left (or top) border's share. */
    static String ratio(double share) {
        return String.format(Locale.ROOT, "%.1f/%.1f", share, 100 - share);
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }
}

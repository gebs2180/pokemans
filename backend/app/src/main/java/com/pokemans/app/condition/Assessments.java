package com.pokemans.app.condition;

import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What other modules (valuation) may know about condition: each card's current assessment. No access checks. */
@Service
public class Assessments {

    /**
     * A card's current assessed distribution, after the photo-confidence and surface adjustments.
     *
     * @param confidence photo confidence
     */
    public record Current(UUID assessmentId, UUID cardItemId, int version, GradeDistribution distribution, Confidence confidence) {
    }

    /**
     * Our condition view for the seller, in words: the grades the card most likely is, from our current
     * assessment, and which of centering, corners, edges and surface hold it back. Never a provider's
     * own grade or name: the firm price is what we stand behind, not a grade. The one measurement shown
     * is our own centering, which stands apart from the assessment: with a measurement but no assessment
     * yet, the grades are 0.
     *
     * @param low          lowest grade of the likely range, 0 with no assessment
     * @param high         highest grade of the likely range, 0 with no assessment
     * @param mostLikely   the single most likely grade, 0 with no assessment
     * @param mayBeUngradable true when the card has a real chance of being ungradable (altered or damaged)
     * @param holdingBack  up to two of what holds the grade back most ("surface", …), lowest first; empty when nothing stands out
     * @param centering    our own centering measurement from the seller's photos, or null with no side measured
     */
    public record Summary(int low, int high, int mostLikely, boolean mayBeUngradable, List<String> holdingBack,
                          MeasuredCentering centering) {
    }

    /** Our own centering measurement. A side that wasn't measured is null. */
    public record MeasuredCentering(SideCentering front, SideCentering back) {
    }

    /**
     * One side's measured ratios in whole percent, the left or top border's share first.
     *
     * @param leftRight e.g. "49/51"
     * @param topBottom e.g. "48/52"
     */
    public record SideCentering(String leftRight, String topBottom) {
    }

    /** Share of the probability the likely range covers. */
    static final BigDecimal LIKELY_SHARE = new BigDecimal("0.8");
    /** Chance of U at which we say the card may be ungradable. */
    static final BigDecimal UNGRADABLE_NOTE = new BigDecimal("0.1");
    /** A sub-grade at or below this holds the card back. */
    static final double HOLDING_BACK_AT = 8.5;

    private final ConditionAssessmentRepository assessments;
    private final ProviderPredictionRepository predictions;

    Assessments(ConditionAssessmentRepository assessments, ProviderPredictionRepository predictions) {
        this.assessments = assessments;
        this.predictions = predictions;
    }

    /** Current assessment per card, for the cards that have one. */
    @Transactional(readOnly = true)
    public Map<UUID, Current> current(Collection<UUID> cardItemIds) {
        if (cardItemIds.isEmpty()) {
            return Map.of();
        }
        return assessments.findByCardItemIdInAndIsCurrentTrue(cardItemIds).stream()
                .collect(Collectors.toMap(ConditionAssessment::getCardItemId,
                        a -> new Current(a.getId(), a.getCardItemId(), a.getVersion(), a.distribution(), a.getConfidence())));
    }

    /** The seller's view of the card's condition, once it has a current assessment. */
    @Transactional(readOnly = true)
    public Optional<Summary> summary(UUID cardItemId) {
        List<ProviderPrediction> latest = latestSuccessful(cardItemId);
        Optional<Summary> assessed = assessments.findByCardItemIdAndIsCurrentTrue(cardItemId)
                .map(a -> summarize(a.distribution(), latest));
        if (assessed.isPresent()) {
            return assessed;
        }
        MeasuredCentering centering = measuredCentering(latest);
        return centering == null ? Optional.empty() : Optional.of(new Summary(0, 0, 0, false, List.of(), centering));
    }

    /**
     * Each provider's latest answer. For our own centering a later refusal counts too: what we show
     * the seller as measured must be our last word on the card, not an older measurement.
     */
    private List<ProviderPrediction> latestSuccessful(UUID cardItemId) {
        List<ProviderPrediction> all = predictions.findByCardItemIdOrderByCreatedAtDesc(cardItemId);
        boolean refused = all.stream().filter(Assessments::isOwnMeasurement).findFirst().filter(p -> !p.isSucceeded()).isPresent();
        return List.copyOf(all.stream()
                .filter(ProviderPrediction::isSucceeded)
                .filter(p -> !refused || !isOwnMeasurement(p))
                .collect(Collectors.toMap(ProviderPrediction::getProvider, p -> p, (newer, older) -> newer, LinkedHashMap::new))
                .values());
    }

    private static boolean isOwnMeasurement(ProviderPrediction prediction) {
        return prediction.getProvider().equals(CenteringPreGradeProvider.NAME);
    }

    /**
     * The likely range is the fewest grades, most probable first, that together hold {@link #LIKELY_SHARE}
     * of the gradable probability; the range runs from the lowest to the highest of them. Each sub-grade is the
     * lowest any provider gave it (conservative; centering is {@link #centeringLimit}), and those at or below
     * {@link #HOLDING_BACK_AT} hold it back.
     */
    static Summary summarize(GradeDistribution distribution, List<ProviderPrediction> latest) {
        List<Grade> byChance = distribution.asMap().entrySet().stream()
                .filter(e -> !e.getKey().isUngradable() && e.getValue().signum() > 0)
                .sorted(Map.Entry.<Grade, BigDecimal>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey)
                .toList();
        // A share of the gradable probability, so a chance of U doesn't narrow the range.
        BigDecimal target = LIKELY_SHARE.multiply(BigDecimal.ONE.subtract(distribution.p(Grade.U)));
        List<Grade> likely = new ArrayList<>();
        BigDecimal covered = BigDecimal.ZERO;
        for (Grade g : byChance) {
            likely.add(g);
            covered = covered.add(distribution.p(g));
            if (covered.compareTo(target) >= 0) {
                break;
            }
        }
        int low = likely.stream().mapToInt(Grade::number).min().orElse(0);
        int high = likely.stream().mapToInt(Grade::number).max().orElse(0);
        int mostLikely = byChance.isEmpty() ? 0 : byChance.getFirst().number();
        boolean mayBeUngradable = distribution.p(Grade.U).compareTo(UNGRADABLE_NOTE) >= 0;
        return new Summary(low, high, mostLikely, mayBeUngradable, holdingBack(latest, high), measuredCentering(latest));
    }

    /** Our own measurement among the latest predictions: only its ratios ever reach the seller. */
    private static MeasuredCentering measuredCentering(List<ProviderPrediction> latest) {
        return latest.stream().filter(Assessments::isOwnMeasurement).findFirst().map(p -> {
            SideCentering front = side(p.getDetails(), "frontCentering");
            SideCentering back = side(p.getDetails(), "backCentering");
            return front == null && back == null ? null : new MeasuredCentering(front, back);
        }).orElse(null);
    }

    private static SideCentering side(Map<String, Object> details, String key) {
        if (details != null && details.get(key) instanceof Map<?, ?> side
                && side.get("leftRight") instanceof String leftRight && side.get("topBottom") instanceof String topBottom) {
            return new SideCentering(wholePercent(leftRight), wholePercent(topBottom));
        }
        return null;
    }

    /** "49.1/50.9" to "49/51". */
    private static String wholePercent(String ratio) {
        long first = Math.round(Double.parseDouble(ratio.split("/")[0]));
        return first + "/" + (100 - first);
    }

    /**
     * What centering holds back. Other providers give a condition score, of which the lowest counts.
     * Our measurement gives a cap, the best grade the centering allows, so it only counts when it reaches
     * the likely range: a cap of 8 doesn't hold back a card that is likely a 5. When the front was
     * measured, the measurement replaces the scores, so the seller is never told centering holds the
     * card back beside ratios that say otherwise. The back alone can't: the front limits most grades.
     */
    private static Double centeringLimit(List<ProviderPrediction> latest, int high) {
        Optional<ProviderPrediction> own = latest.stream().filter(Assessments::isOwnMeasurement).findFirst();
        Double cap = own.map(ProviderPrediction::getCentering).filter(c -> c <= high).orElse(null);
        if (own.isPresent() && side(own.get().getDetails(), "frontCentering") != null) {
            return cap;
        }
        Double score = latest.stream().filter(p -> !isOwnMeasurement(p)).map(ProviderPrediction::getCentering)
                .filter(Objects::nonNull).min(Double::compare).orElse(null);
        return Stream.of(cap, score).filter(Objects::nonNull).min(Double::compare).orElse(null);
    }

    private static List<String> holdingBack(List<ProviderPrediction> latest, int high) {
        Map<String, Function<ProviderPrediction, Double>> parts = new LinkedHashMap<>();
        parts.put("corners", ProviderPrediction::getCorners);
        parts.put("edges", ProviderPrediction::getEdges);
        parts.put("surface", ProviderPrediction::getSurface);
        Map<String, Double> lowest = new LinkedHashMap<>();
        Double centering = centeringLimit(latest, high);
        if (centering != null) {
            lowest.put("centering", centering);
        }
        parts.forEach((name, read) -> latest.stream().map(read).filter(v -> v != null).min(Double::compare)
                .ifPresent(v -> lowest.put(name, v)));
        return lowest.entrySet().stream()
                .filter(e -> e.getValue() <= HOLDING_BACK_AT)
                .sorted(Map.Entry.comparingByValue())
                .limit(2)
                .map(Map.Entry::getKey)
                .toList();
    }
}

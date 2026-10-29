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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
     * own grade or name: the firm price is what we stand behind, not a grade.
     *
     * @param low          lowest grade of the likely range
     * @param high         highest grade of the likely range
     * @param mostLikely   the single most likely grade
     * @param mayBeUngradable true when the card has a real chance of being ungradable (altered or damaged)
     * @param holdingBack  up to two of what holds the grade back most ("surface", …), lowest first; empty when nothing stands out
     */
    public record Summary(int low, int high, int mostLikely, boolean mayBeUngradable, List<String> holdingBack) {
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
        return assessments.findByCardItemIdAndIsCurrentTrue(cardItemId)
                .map(a -> summarize(a.distribution(), latestSuccessful(cardItemId)));
    }

    private List<ProviderPrediction> latestSuccessful(UUID cardItemId) {
        return List.copyOf(predictions.findByCardItemIdOrderByCreatedAtDesc(cardItemId).stream()
                .filter(ProviderPrediction::isSucceeded)
                .collect(Collectors.toMap(ProviderPrediction::getProvider, p -> p, (newer, older) -> newer, LinkedHashMap::new))
                .values());
    }

    /**
     * The likely range is the fewest grades, most probable first, that together hold {@link #LIKELY_SHARE}
     * of the gradable probability; the range runs from the lowest to the highest of them. Each sub-grade is the
     * lowest any provider gave it (conservative), and those at or below {@link #HOLDING_BACK_AT} hold it back.
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
        return new Summary(low, high, mostLikely, mayBeUngradable, holdingBack(latest));
    }

    private static List<String> holdingBack(List<ProviderPrediction> latest) {
        Map<String, Function<ProviderPrediction, Double>> parts = new LinkedHashMap<>();
        parts.put("centering", ProviderPrediction::getCentering);
        parts.put("corners", ProviderPrediction::getCorners);
        parts.put("edges", ProviderPrediction::getEdges);
        parts.put("surface", ProviderPrediction::getSurface);
        Map<String, Double> lowest = new LinkedHashMap<>();
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

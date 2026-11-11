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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import com.pokemans.app.intake.CardItems;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What other modules (valuation) may know about condition: each card's current assessment. No access checks. */
@Service
public class Assessments {

    /**
     * A card's current assessed distribution, after the photo-confidence and surface adjustments.
     *
     * @param confidence    photo confidence
     * @param photosChanged true when the front or back was retaken since: the assessment no longer
     *                      describes the photos in use, so it doesn't count until the card is assessed again
     * @param providerRetired true when it's a system assessment built partly on a provider we've stopped
     *                      using (ProviderPrediction#RETIRED): it doesn't count until the card is assessed again
     */
    public record Current(UUID assessmentId, UUID cardItemId, int version, GradeDistribution distribution, Confidence confidence,
                          boolean photosChanged, boolean providerRetired) {

        /** Whether the assessment counts: neither its photos nor its providers have been replaced. */
        public boolean counts() {
            return !photosChanged && !providerRetired;
        }
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

    /** Basis key for the front and back photo ids an assessment was made from. */
    static final String PHOTO_IDS = "photoIds";

    private final ConditionAssessmentRepository assessments;
    private final ProviderPredictionRepository predictions;
    private final CardItems cardItems;

    Assessments(ConditionAssessmentRepository assessments, ProviderPredictionRepository predictions, CardItems cardItems) {
        this.assessments = assessments;
        this.predictions = predictions;
        this.cardItems = cardItems;
    }

    /** Current assessment per card, for the cards that have one. */
    @Transactional(readOnly = true)
    public Map<UUID, Current> current(Collection<UUID> cardItemIds) {
        if (cardItemIds.isEmpty()) {
            return Map.of();
        }
        return assessments.findByCardItemIdInAndIsCurrentTrue(cardItemIds).stream()
                .collect(Collectors.toMap(ConditionAssessment::getCardItemId,
                        a -> new Current(a.getId(), a.getCardItemId(), a.getVersion(), a.distribution(), a.getConfidence(),
                                photosChanged(a), builtOnRetiredProvider(a))));
    }

    /**
     * True for a system assessment whose distribution took in a provider we've stopped using
     * (ProviderPrediction#RETIRED). Like a retake, it holds the card until it's assessed again ("Ask
     * again" or a reviewer); the predictions themselves stay stored. A reviewer's assessment is their
     * own judgement and stays.
     */
    static boolean builtOnRetiredProvider(ConditionAssessment assessment) {
        if (assessment.getAuthorType() != ConditionAssessment.AuthorType.SYSTEM || assessment.getBasis() == null) {
            return false;
        }
        return assessment.getBasis().get("providerGrades") instanceof Map<?, ?> grades
                && grades.keySet().stream().anyMatch(provider -> ProviderPrediction.RETIRED.contains(String.valueOf(provider)));
    }

    /** True when the assessment doesn't count until the card is assessed again: photos retaken or a provider retired. */
    boolean needsAssessingAgain(ConditionAssessment assessment) {
        return builtOnRetiredProvider(assessment) || photosChanged(assessment);
    }

    /** True when the card's front or back was retaken since this assessment was made. */
    boolean photosChanged(ConditionAssessment assessment) {
        return photosChanged(assessment, cardItems.passedPhotos(assessment.getCardItemId()),
                () -> predictions.findByCardItemIdOrderByCreatedAtDesc(assessment.getCardItemId()));
    }

    /**
     * Whether an assessment no longer rests on the photos in use (the latest passing front and back). It
     * records them since 2026-11-04. Older ones are judged from what they were built on: a system
     * assessment by its graded predictions' photos, a reviewer's by whether a photo in use came after it.
     */
    static boolean photosChanged(ConditionAssessment assessment, List<CardItems.PhotoRef> passed,
                                 Supplier<List<ProviderPrediction>> predictions) {
        List<CardItems.PhotoRef> inUse = inUse(passed);
        Set<String> inUseIds = inUse.stream().map(p -> p.id().toString()).collect(Collectors.toSet());
        Map<String, Object> basis = assessment.getBasis() == null ? Map.of() : assessment.getBasis();
        if (basis.get(PHOTO_IDS) instanceof List<?> recorded) {
            return !recorded.stream().map(Object::toString).collect(Collectors.toSet()).equals(inUseIds);
        }
        if (assessment.getAuthorType() == ConditionAssessment.AuthorType.REVIEWER) {
            return inUse.stream().anyMatch(p -> p.uploadedAt().isAfter(assessment.getCreatedAt()));
        }
        Set<String> used = basis.get("predictionIds") instanceof List<?> ids
                ? ids.stream().map(Object::toString).collect(Collectors.toSet()) : Set.of();
        return predictions.get().stream()
                .filter(p -> used.contains(p.getId().toString()) && !p.isOwnMeasurement())
                .anyMatch(p -> !madeOn(p, inUseIds));
    }

    /** The latest passing front and back, the photos a card is assessed from. */
    static List<CardItems.PhotoRef> inUse(List<CardItems.PhotoRef> passed) {
        return java.util.stream.Stream.of("FRONT", "BACK")
                .flatMap(side -> passed.stream().filter(p -> p.side().equals(side))
                        .max(Comparator.comparing(CardItems.PhotoRef::uploadedAt)).stream())
                .toList();
    }

    /** Whether a prediction was made on exactly these photos. */
    static boolean madeOn(ProviderPrediction p, Set<String> photoIds) {
        return p.getPhotoIds() != null && p.getPhotoIds().stream().map(UUID::toString).collect(Collectors.toSet()).equals(photoIds);
    }

    /** The seller's view of the card's condition, once it has a current assessment. */
    @Transactional(readOnly = true)
    public Optional<Summary> summary(UUID cardItemId) {
        List<ProviderPrediction> latest = shownToSeller(cardItemId);
        // An assessment of photos since replaced says nothing about the ones the seller sees, and one built
        // on a retired provider is being redone.
        Optional<Summary> assessed = assessments.findByCardItemIdAndIsCurrentTrue(cardItemId)
                .filter(a -> !needsAssessingAgain(a))
                .map(a -> summarize(a.distribution(), latest));
        if (assessed.isPresent()) {
            return assessed;
        }
        MeasuredCentering centering = measuredCentering(latest);
        return centering == null ? Optional.empty() : Optional.of(new Summary(0, 0, 0, false, List.of(), centering));
    }

    /**
     * Each provider's latest answer. For our own centering a later refusal counts too: what we show
     * the seller as measured must be our last word on the card, not an older measurement. The
     * distribution goes the other way and keeps the older cap (ConditionService#build).
     */
    private List<ProviderPrediction> shownToSeller(UUID cardItemId) {
        List<ProviderPrediction> all = predictions.findByCardItemIdOrderByCreatedAtDesc(cardItemId);
        Map<String, ProviderPrediction> latest = ProviderPrediction.latestSucceeded(all);
        all.stream().filter(ProviderPrediction::isOwnMeasurement).findFirst().filter(p -> !p.isSucceeded())
                .ifPresent(refusal -> latest.remove(refusal.getProvider()));
        return List.copyOf(latest.values());
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
        return latest.stream().filter(ProviderPrediction::isOwnMeasurement).findFirst().map(p -> {
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
     * What centering holds back: only our own measurement counts, never another provider's centering
     * score (CLAUDE.md, pipeline step 2). An allowlist, so a provider added later can't slip in: Ximilar
     * read 42/58 on real cards where the borders are nearly even, and nobody has checked the others.
     * Our measurement gives a cap, the best grade the centering allows, so it only counts when it reaches
     * the likely range: a cap of 8 doesn't hold back a card that is likely a 5.
     */
    private static Double centeringLimit(List<ProviderPrediction> latest, int high) {
        return latest.stream().filter(ProviderPrediction::isOwnMeasurement).findFirst()
                .map(ProviderPrediction::getCentering).filter(c -> c <= high).orElse(null);
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

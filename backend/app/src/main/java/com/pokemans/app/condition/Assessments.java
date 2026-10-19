package com.pokemans.app.condition;

import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.GradeDistribution;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
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

    private final ConditionAssessmentRepository assessments;

    Assessments(ConditionAssessmentRepository assessments) {
        this.assessments = assessments;
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
}

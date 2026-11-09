package com.pokemans.app.outcomes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What other modules (valuation) may know about returned grades. No access checks. */
@Service
public class Outcomes {

    /**
     * A card's returned grade whose cert has been checked and matches.
     *
     * @param gradingCompany the grader's name, as pricing names grading companies (PSA, BGS, CGC, ...)
     * @param grade          numeric grade as returned, half grades kept (8.5)
     */
    public record VerifiedGrade(UUID outcomeId, UUID cardItemId, String gradingCompany, BigDecimal grade, String certNumber,
                                LocalDate gradedOn) {
    }

    private final GradeOutcomeRepository outcomes;
    private final OutcomeVerificationRepository verifications;
    private final DealOutcomes dealOutcomes;

    Outcomes(GradeOutcomeRepository outcomes, OutcomeVerificationRepository verifications, DealOutcomes dealOutcomes) {
        this.outcomes = outcomes;
        this.verifications = verifications;
        this.dealOutcomes = dealOutcomes;
    }

    /**
     * For each card whose latest returned grade is numeric and whose latest cert check on it is
     * VERIFIED. A card with a mismatched or unchecked cert, or a newer outcome not yet checked, is
     * left out: it is still raw for valuation. A buyer's grade that the buyer has since corrected or taken
     * back no longer counts, checked or not.
     */
    @Transactional(readOnly = true)
    public Map<UUID, VerifiedGrade> verifiedGrades(Collection<UUID> cardItemIds) {
        if (cardItemIds.isEmpty()) {
            return Map.of();
        }
        List<GradeOutcome> all = outcomes.findByCardItemIdInOrderByCreatedAtDesc(cardItemIds);
        java.util.Set<UUID> retracted = new java.util.HashSet<>(dealOutcomes.retracted(all));
        Map<UUID, GradeOutcome> latest = all.stream().filter(o -> !retracted.contains(o.getId()))
                .collect(Collectors.toMap(GradeOutcome::getCardItemId, Function.identity(), (newer, older) -> newer));
        List<UUID> outcomeIds = latest.values().stream().map(GradeOutcome::getId).toList();
        Map<UUID, OutcomeVerification> check = outcomeIds.isEmpty() ? Map.of()
                : verifications.findByOutcomeIdInOrderByCreatedAtDesc(outcomeIds).stream()
                .collect(Collectors.toMap(OutcomeVerification::getOutcomeId, Function.identity(), (newer, older) -> newer));
        Map<UUID, VerifiedGrade> result = new LinkedHashMap<>();
        for (GradeOutcome o : latest.values()) {
            OutcomeVerification v = check.get(o.getId());
            if (o.getGrade() != null && v != null && v.getStatus() == OutcomeVerification.Status.VERIFIED) {
                result.put(o.getCardItemId(), new VerifiedGrade(o.getId(), o.getCardItemId(), o.getGrader().name(), o.getGrade(),
                        o.getCertNumber(), o.getGradedOn()));
            }
        }
        return result;
    }
}

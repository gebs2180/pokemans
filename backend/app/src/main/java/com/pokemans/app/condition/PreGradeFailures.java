package com.pokemans.app.condition;

import com.pokemans.app.intake.CardItems;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cards left unassessed because the automatic pre-grade failed (a final "can't grade", out of credits, a bad
 * key, gave up). Such a card waits for the admin's "Ask again" or a reviewer. No access checks.
 *
 * <p>A card counts when the latest request to a pre-grade provider on the photos in use FAILED and it has no
 * assessment that counts. A newer request (queued, running or done), a reviewer's assessment, or a retake
 * of either photo takes it off. Our own centering measurement isn't a pre-grade: its failures don't count.
 */
@Service
public class PreGradeFailures {

    /** What the admin's home shows. */
    public record Summary(int failedCards, int outOfCreditsCards, boolean outOfCredits, Instant submitsResumeAt) {
    }

    private final PredictionRequestRepository requests;
    private final ConditionAssessmentRepository assessmentRepository;
    private final Assessments assessments;
    private final CardItems cardItems;
    private final List<PreGradeProvider> providers;
    private final Clock clock;

    PreGradeFailures(PredictionRequestRepository requests, ConditionAssessmentRepository assessmentRepository, Assessments assessments,
                     CardItems cardItems, List<PreGradeProvider> providers) {
        this.requests = requests;
        this.assessmentRepository = assessmentRepository;
        this.assessments = assessments;
        this.cardItems = cardItems;
        this.providers = providers;
        this.clock = Clock.systemUTC();
    }

    /** Whether this card is unassessed because its pre-grade failed. */
    @Transactional(readOnly = true)
    public boolean failed(UUID cardId) {
        return failedRequests(List.of(cardId)).containsKey(cardId);
    }

    /** Counts the failed cards among these, and says if CardGrade being out of credits is why. */
    @Transactional(readOnly = true)
    public Summary summary(Collection<UUID> cardIds) {
        Map<UUID, PredictionRequest> failed = failedRequests(cardIds);
        int noCredits = (int) failed.values().stream().filter(PreGradeFailures::outOfCredits).count();
        Instant now = clock.instant();
        Optional<Instant> paused = providers.stream().map(p -> p.submitsPausedUntil(now)).flatMap(Optional::stream)
                .max(Comparator.naturalOrder());
        return new Summary(failed.size(), noCredits, noCredits > 0 || paused.isPresent(), paused.orElse(null));
    }

    /** The latest failed request of each card that counts. */
    private Map<UUID, PredictionRequest> failedRequests(Collection<UUID> cardIds) {
        if (cardIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> assessed = new HashSet<>();
        assessmentRepository.findByCardItemIdInAndIsCurrentTrue(cardIds).stream()
                .filter(a -> !assessments.needsAssessingAgain(a)).forEach(a -> assessed.add(a.getCardItemId()));
        Map<UUID, List<PredictionRequest>> byCard = requests.findByCardItemIdIn(cardIds).stream()
                .filter(r -> !CenteringPreGradeProvider.NAME.equals(r.getProvider()))
                .collect(Collectors.groupingBy(PredictionRequest::getCardItemId));
        Map<UUID, PredictionRequest> failed = new java.util.HashMap<>();
        byCard.forEach((cardId, all) -> {
            if (assessed.contains(cardId)) {
                return;
            }
            Set<String> inUse = Assessments.inUse(cardItems.passedPhotos(cardId)).stream().map(p -> p.id().toString())
                    .collect(Collectors.toSet());
            all.stream().filter(r -> r.getPhotoIds() != null
                            && r.getPhotoIds().stream().map(UUID::toString).collect(Collectors.toSet()).equals(inUse))
                    .max(Comparator.comparing(PredictionRequest::getCreatedAt))
                    .filter(r -> r.getStatus() == PredictionRequest.Status.FAILED)
                    .ifPresent(r -> failed.put(cardId, r));
        });
        return failed;
    }

    private static boolean outOfCredits(PredictionRequest r) {
        return r.getLastError() != null && r.getLastError().contains("out of credits");
    }
}

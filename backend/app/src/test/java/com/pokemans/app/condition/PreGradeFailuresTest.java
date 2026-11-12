package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pokemans.app.intake.CardItems;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreGradeFailuresTest {

    private final PredictionRequestRepository requests = mock(PredictionRequestRepository.class);
    private final ConditionAssessmentRepository assessmentRepository = mock(ConditionAssessmentRepository.class);
    private final Assessments assessments = mock(Assessments.class);
    private final CardItems cardItems = mock(CardItems.class);
    private final PreGradeProvider provider = mock(PreGradeProvider.class);
    private final PreGradeFailures failures = new PreGradeFailures(requests, assessmentRepository, assessments, cardItems, List.of(provider));

    private final UUID card = UUID.randomUUID();
    private final UUID front = UUID.randomUUID();
    private final UUID back = UUID.randomUUID();

    PreGradeFailuresTest() {
        when(provider.submitsPausedUntil(any())).thenReturn(Optional.empty());
        photosInUse(front, back);
    }

    private void photosInUse(UUID f, UUID b) {
        Instant at = Instant.parse("2026-11-01T10:00:00Z");
        when(cardItems.passedPhotos(card)).thenReturn(List.of(
                new CardItems.PhotoRef(f, card, "FRONT", "k1", "image/jpeg", null, at, at, "a"),
                new CardItems.PhotoRef(b, card, "BACK", "k2", "image/jpeg", null, at, at, "b")));
    }

    private PredictionRequest request(String providerName, List<UUID> photos) {
        return new PredictionRequest(UUID.randomUUID(), card, providerName, photos, null);
    }

    private PredictionRequest failed(List<UUID> photos, String error) {
        PredictionRequest r = request(CardGradePreGradeProvider.NAME, photos);
        r.rejected(error);
        return r;
    }

    private void requestsAre(PredictionRequest... all) {
        when(requests.findByCardItemIdIn(any())).thenReturn(List.of(all));
    }

    private static void later() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aCardWhoseLatestPreGradeFailedAndHasNoAssessmentCounts() {
        requestsAre(failed(List.of(front, back), "CardGrade couldn't grade the card: blurry"));

        PreGradeFailures.Summary summary = failures.summary(List.of(card));
        assertThat(summary.failedCards()).isEqualTo(1);
        assertThat(summary.outOfCreditsCards()).isZero();
        assertThat(summary.outOfCredits()).isFalse();
        assertThat(failures.failed(card)).isTrue();
    }

    @Test
    void outOfCreditsIsCountedApartAndSaysWhenSubmitsResume() {
        Instant resume = Instant.parse("2026-11-13T10:00:00Z");
        when(provider.submitsPausedUntil(any())).thenReturn(Optional.of(resume));
        requestsAre(failed(List.of(front, back), "CardGrade said 402 (out of credits; the monthly grades are used up)"));

        PreGradeFailures.Summary summary = failures.summary(List.of(card));
        assertThat(summary.failedCards()).isEqualTo(1);
        assertThat(summary.outOfCreditsCards()).isEqualTo(1);
        assertThat(summary.outOfCredits()).isTrue();
        assertThat(summary.submitsResumeAt()).isEqualTo(resume);
    }

    @Test
    void pausedSubmitsAreReportedEvenBeforeAnyCardFails() {
        when(provider.submitsPausedUntil(any())).thenReturn(Optional.of(Instant.parse("2026-11-13T10:00:00Z")));
        requestsAre();

        PreGradeFailures.Summary summary = failures.summary(List.of(card));
        assertThat(summary.failedCards()).isZero();
        assertThat(summary.outOfCredits()).isTrue();
    }

    @Test
    void aReassessedCardIsNotCounted() {
        requestsAre(failed(List.of(front, back), "CardGrade couldn't grade the card"));
        ConditionAssessment reviewer = mock(ConditionAssessment.class);
        when(reviewer.getCardItemId()).thenReturn(card);
        when(assessmentRepository.findByCardItemIdInAndIsCurrentTrue(any())).thenReturn(List.of(reviewer));
        when(assessments.needsAssessingAgain(reviewer)).thenReturn(false);

        assertThat(failures.summary(List.of(card)).failedCards()).isZero();
        assertThat(failures.failed(card)).isFalse();
    }

    @Test
    void anAssessmentThatNoLongerCountsDoesNotHideTheFailure() {
        requestsAre(failed(List.of(front, back), "CardGrade couldn't grade the card"));
        ConditionAssessment stale = mock(ConditionAssessment.class);
        when(stale.getCardItemId()).thenReturn(card);
        when(assessmentRepository.findByCardItemIdInAndIsCurrentTrue(any())).thenReturn(List.of(stale));
        when(assessments.needsAssessingAgain(stale)).thenReturn(true);

        assertThat(failures.summary(List.of(card)).failedCards()).isEqualTo(1);
    }

    @Test
    void aNewerRequestTakesTheCardOffTheList() {
        PredictionRequest old = failed(List.of(front, back), "CardGrade couldn't grade the card");
        later();
        PredictionRequest askedAgain = request(CardGradePreGradeProvider.NAME, List.of(front, back));
        requestsAre(old, askedAgain);

        assertThat(failures.summary(List.of(card)).failedCards()).isZero();
    }

    @Test
    void aFailureOnPhotosSinceRetakenIsNotCounted() {
        requestsAre(failed(List.of(front, back), "CardGrade couldn't grade the card"));
        photosInUse(UUID.randomUUID(), back);

        assertThat(failures.summary(List.of(card)).failedCards()).isZero();
        assertThat(failures.failed(card)).isFalse();
    }

    @Test
    void ourOwnCenteringMeasurementIsNotAPreGrade() {
        PredictionRequest centering = request(CenteringPreGradeProvider.NAME, List.of(front, back));
        centering.rejected("Could not measure");
        requestsAre(centering);

        assertThat(failures.summary(List.of(card)).failedCards()).isZero();
    }
}

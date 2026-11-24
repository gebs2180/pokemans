package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * When the claude-vision shadow is queued. Each request is a paid batch: never twice for the same photos,
 * never two open at once, and a retake CardGrade answered meanwhile is picked up when the open one ends.
 */
class ShadowQueueTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID CARD = UUID.randomUUID();
    private static final List<UUID> FIRST = List.of(UUID.randomUUID(), UUID.randomUUID());
    private static final List<UUID> RETAKE = List.of(UUID.randomUUID(), UUID.randomUUID());

    private final PredictionRequestRepository requests = mock(PredictionRequestRepository.class);
    private final ProviderPredictionRepository predictions = mock(ProviderPredictionRepository.class);
    /** Newest first, as the repositories return them. */
    private final List<PredictionRequest> history = new ArrayList<>();
    private final List<ProviderPrediction> answers = new ArrayList<>();

    private ConditionService service() {
        when(requests.findByCardItemIdOrderByCreatedAtDesc(CARD)).thenReturn(history);
        when(predictions.findByCardItemIdOrderByCreatedAtDesc(CARD)).thenReturn(answers);
        when(requests.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        return new ConditionService(List.of(provider(CardGradePreGradeProvider.NAME), provider(ClaudeVisionPreGradeProvider.NAME)),
                requests, predictions, mock(ConditionAssessmentRepository.class), mock(CardItems.class), mock(Catalog.class),
                mock(CurrentUser.class), DistributionBuilderTest.defaults(), mock(ApplicationEventPublisher.class));
    }

    private static PreGradeProvider provider(String name) {
        PreGradeProvider provider = mock(PreGradeProvider.class);
        when(provider.name()).thenReturn(name);
        return provider;
    }

    /** A request that has ended, added as the newest. */
    private PredictionRequest ended(String provider, List<UUID> photos, boolean done) {
        PredictionRequest request = new PredictionRequest(ORG, CARD, provider, photos, null);
        if (done) {
            request.done();
        } else {
            request.callFailed("boom", 1);
        }
        history.addFirst(request);
        return request;
    }

    private PredictionRequest open(String provider, List<UUID> photos) {
        PredictionRequest request = new PredictionRequest(ORG, CARD, provider, photos, null);
        history.addFirst(request);
        return request;
    }

    /** claude-vision answered on {@code photos}. */
    private PredictionRequest answered(List<UUID> photos) {
        PredictionRequest request = ended(ClaudeVisionPreGradeProvider.NAME, photos, true);
        answers.addFirst(ProviderPrediction.succeeded(request,
                new PreGradeProvider.Reading("m", 8.0, 8.0, null, null, null, null, Map.of()), Map.of(), 1));
        return request;
    }

    private List<PredictionRequest> queued() {
        ArgumentCaptor<PredictionRequest> saved = ArgumentCaptor.forClass(PredictionRequest.class);
        verify(requests, atLeast(0)).saveAndFlush(saved.capture());
        return saved.getAllValues();
    }

    @Test
    void cardGradesAnswerQueuesTheShadowOnTheSamePhotos() {
        ConditionService service = service();
        PredictionRequest cardGrade = ended(CardGradePreGradeProvider.NAME, FIRST, true);

        service.queueShadow(cardGrade);

        assertThat(queued()).singleElement().satisfies(r -> {
            assertThat(r.getProvider()).isEqualTo(ClaudeVisionPreGradeProvider.NAME);
            assertThat(r.getPhotoIds()).isEqualTo(FIRST);
        });
    }

    @Test
    void askAgainOnPhotosClaudeAlreadyAnsweredQueuesNothing() {
        ConditionService service = service();
        ended(CardGradePreGradeProvider.NAME, FIRST, true);
        answered(FIRST);
        PredictionRequest askedAgain = ended(CardGradePreGradeProvider.NAME, FIRST, true);

        service.queueShadow(askedAgain);

        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void askAgainAfterAFailedShadowTriesOnceMore() {
        ConditionService service = service();
        ended(CardGradePreGradeProvider.NAME, FIRST, true);
        ended(ClaudeVisionPreGradeProvider.NAME, FIRST, false);
        PredictionRequest askedAgain = ended(CardGradePreGradeProvider.NAME, FIRST, true);

        service.queueShadow(askedAgain);

        assertThat(queued()).singleElement().satisfies(r -> assertThat(r.getPhotoIds()).isEqualTo(FIRST));
    }

    @Test
    void aShadowStillOpenHoldsTheRetakeBackUntilItEnds() {
        ConditionService service = service();
        ended(CardGradePreGradeProvider.NAME, FIRST, true);
        PredictionRequest running = open(ClaudeVisionPreGradeProvider.NAME, FIRST);
        PredictionRequest retake = ended(CardGradePreGradeProvider.NAME, RETAKE, true);

        service.queueShadow(retake);
        verify(requests, never()).saveAndFlush(any());

        // The open one ends: CardGrade's newest photos haven't been asked about, so they are queued now.
        running.done();
        answers.addFirst(ProviderPrediction.succeeded(running,
                new PreGradeProvider.Reading("m", 8.0, 8.0, null, null, null, null, Map.of()), Map.of(), 1));
        service.queueShadow(running);

        assertThat(queued()).singleElement().satisfies(r -> assertThat(r.getPhotoIds()).isEqualTo(RETAKE));
    }

    @Test
    void aShadowEndingOnTheNewestPhotosQueuesNothingEvenWhenItFailed() {
        ConditionService service = service();
        ended(CardGradePreGradeProvider.NAME, FIRST, true);
        PredictionRequest failed = ended(ClaudeVisionPreGradeProvider.NAME, FIRST, false);

        service.queueShadow(failed);

        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void anOlderCardGradeRequestEndingLateQueuesTheNewestPhotos() {
        ConditionService service = service();
        PredictionRequest older = new PredictionRequest(ORG, CARD, CardGradePreGradeProvider.NAME, FIRST, null);
        ended(CardGradePreGradeProvider.NAME, RETAKE, true);
        older.done();
        history.addLast(older); // made first, ended last

        service.queueShadow(older);

        assertThat(queued()).singleElement().satisfies(r -> assertThat(r.getPhotoIds()).isEqualTo(RETAKE));
    }

    @Test
    void cardGradeFailingQueuesNothing() {
        ConditionService service = service();
        PredictionRequest failed = ended(CardGradePreGradeProvider.NAME, FIRST, false);

        service.queueShadow(failed);

        verify(requests, never()).saveAndFlush(any());
    }
}

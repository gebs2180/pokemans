package com.pokemans.app.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import com.pokemans.valuation.Confidence;
import com.pokemans.valuation.Grade;
import com.pokemans.valuation.GradeDistribution;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

/** Which of a card's stored answers a system assessment is built from. */
class ConditionRebuildTest {

    private static final UUID CARD = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();

    private static PredictionRequest request(String provider) {
        return new PredictionRequest(UUID.randomUUID(), CARD, provider, List.of(UUID.randomUUID()), null);
    }

    private static ProviderPrediction graded(String provider, Double overall, Double centering) {
        return ProviderPrediction.succeeded(request(provider),
                new PreGradeProvider.Reading(null, overall, null, centering, null, null, null, Map.of()), Map.of(), 1);
    }

    private static ProviderPrediction measured(double cap) {
        return ProviderPrediction.succeeded(request(CenteringPreGradeProvider.NAME),
                new PreGradeProvider.Reading(CenteringPreGradeProvider.VERSION, null, null, cap, null, null, null, Map.of()), Map.of(), 1);
    }

    /** Worst shares by side, as the measurer stores them: null for a side it couldn't measure. */
    private static ProviderPrediction measuredSides(Double front, Double back) {
        Map<String, Object> details = Map.of("frontCentering", side(front), "backCentering", side(back), "measurementMargin", 1.0);
        double cap = PsaCentering.cap(front, back, 1.0);
        return ProviderPrediction.succeeded(request(CenteringPreGradeProvider.NAME),
                new PreGradeProvider.Reading(CenteringPreGradeProvider.VERSION, null, null, cap, null, null, null, details), Map.of(), 1);
    }

    private static Map<String, Object> side(Double worstShare) {
        return worstShare == null ? Map.of("unmeasured", "no clear inner edge") : Map.of("worstShare", worstShare);
    }

    private static ProviderPrediction refused() {
        return ProviderPrediction.rejected(request(CenteringPreGradeProvider.NAME), "Centering not measured", Map.of(), 1);
    }

    private static ConditionService service(ProviderPrediction... newestFirst) {
        ProviderPredictionRepository predictions = mock(ProviderPredictionRepository.class);
        when(predictions.findByCardItemIdOrderByCreatedAtDesc(CARD)).thenReturn(List.of(newestFirst));
        ConditionAssessmentRepository assessments = mock(ConditionAssessmentRepository.class);
        when(assessments.findByCardItemIdAndIsCurrentTrue(CARD)).thenReturn(Optional.empty());
        when(assessments.save(any())).thenAnswer(call -> call.getArgument(0));
        CardItems cardItems = mock(CardItems.class);
        when(cardItems.get(CARD)).thenReturn(new CardItems.CardItemRef(CARD, ORG, "A card", false, 1, Instant.now()));
        when(cardItems.passedPhotos(CARD)).thenReturn(List.of());
        Catalog catalog = mock(Catalog.class);
        when(catalog.current(CARD)).thenReturn(Optional.empty());
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(UUID.randomUUID());
        return new ConditionService(List.of(), mock(PredictionRequestRepository.class), predictions, assessments, cardItems, catalog,
                currentUser, DistributionBuilderTest.defaults(), mock(ApplicationEventPublisher.class));
    }

    private static Optional<ConditionAssessment> rebuild(ProviderPrediction... newestFirst) {
        return service(newestFirst).rebuildSystemAssessment(CARD);
    }

    private static GradeDistribution even(Grade first, Grade second) {
        return GradeDistribution.of(Map.of(first, new BigDecimal("0.5"), second, new BigDecimal("0.5")));
    }

    private static ConditionAssessment byHand(GradeDistribution distribution, boolean override, ProviderPrediction... newestFirst) {
        return service(newestFirst).reviewerAssessment(ORG, CARD, distribution, Confidence.B, "Checked by hand", override);
    }

    @Test
    void aLaterRefusalKeepsTheOlderCap() {
        ConditionAssessment built = rebuild(refused(), measured(7), graded("A", 9.0, null)).orElseThrow();

        assertThat(built.getBasis()).containsEntry("centeringCap", 7);
    }

    @Test
    void aNewerMeasurementReplacesTheCap() {
        ConditionAssessment built = rebuild(measured(9), measured(7), graded("A", 9.0, null)).orElseThrow();

        assertThat(built.getBasis()).containsEntry("centeringCap", 9);
    }

    @Test
    void aRetakeThatCantMeasureTheFrontKeepsTheOlderFrontsLimit() {
        // The front measured 54.6, which caps at 9; alone, the back would allow a 10.
        ConditionAssessment built = rebuild(measuredSides(null, 53.3), measuredSides(54.6, 53.3), graded("A", 9.0, null)).orElseThrow();

        assertThat(built.getBasis()).containsEntry("centeringCap", 9);
    }

    @Test
    void aRetakeThatMeasuresTheFrontReplacesItsLimit() {
        ConditionAssessment built = rebuild(measuredSides(52.0, 53.3), measuredSides(54.6, 53.3), graded("A", 9.0, null)).orElseThrow();

        assertThat(built.getBasis()).containsEntry("centeringCap", 10);
    }

    @Test
    void aProvidersCenteringNeverCaps() {
        ConditionAssessment built = rebuild(graded("B", null, 5.0), graded("A", 9.0, 5.0)).orElseThrow();

        assertThat(built.getBasis().get("centeringCap")).isNull();
    }

    @Test
    void centeringAloneBuildsNothing() {
        assertThat(rebuild(measured(8))).isEmpty();
    }

    @Test
    void aHandDistributionAboveTheCapIsRefused() {
        assertThatThrownBy(() -> byHand(even(Grade.of(8), Grade.of(9)), false, measured(8)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("allows PSA 8 at best");
    }

    @Test
    void aHandDistributionAboveTheCapIsStillRefusedAfterARefusal() {
        assertThatThrownBy(() -> byHand(even(Grade.of(8), Grade.of(9)), false, refused(), measured(8)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aHandDistributionAtTheCapIsSaved() {
        ConditionAssessment saved = byHand(even(Grade.of(7), Grade.of(8)), false, measured(8));

        assertThat(saved.getBasis()).containsEntry("centeringCap", 8).containsEntry("centeringCapOverridden", false);
    }

    @Test
    void anOverrideIsSavedOnTheRecord() {
        ConditionAssessment saved = byHand(even(Grade.of(8), Grade.of(9)), true, measured(8));

        assertThat(saved.getBasis()).containsEntry("centeringCap", 8).containsEntry("centeringCapOverridden", true);
    }

    @Test
    void ungradableProbabilityDoesNotCountAsAboveTheCap() {
        ConditionAssessment saved = byHand(even(Grade.U, Grade.of(1)), false, measured(1));

        assertThat(saved.getBasis()).containsEntry("centeringCapOverridden", false);
    }

    @Test
    void withNothingMeasuredThereIsNoCap() {
        ConditionAssessment saved = byHand(even(Grade.of(9), Grade.of(10)), false);

        assertThat(saved.getBasis().get("centeringCap")).isNull();
    }
}

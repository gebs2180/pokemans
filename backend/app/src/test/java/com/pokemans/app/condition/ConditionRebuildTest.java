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
    private static final Instant TAKEN = Instant.parse("2026-11-01T10:00:00Z");
    /** The front and back in use, unless a test retakes one. */
    private static final CardItems.PhotoRef FRONT = photo("FRONT", TAKEN);
    private static final CardItems.PhotoRef BACK = photo("BACK", TAKEN);

    private static CardItems.PhotoRef photo(String side, Instant uploadedAt) {
        return new CardItems.PhotoRef(UUID.randomUUID(), CARD, side, "key", "image/jpeg", "crop", uploadedAt, uploadedAt, "sha");
    }

    private static PredictionRequest request(String provider) {
        return request(provider, FRONT, BACK);
    }

    private static PredictionRequest request(String provider, CardItems.PhotoRef front, CardItems.PhotoRef back) {
        return new PredictionRequest(UUID.randomUUID(), CARD, provider, List.of(front.id(), back.id()), null);
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
        return service(null, List.of(FRONT, BACK), newestFirst);
    }

    private static ConditionService service(ConditionAssessment current, List<CardItems.PhotoRef> passed, ProviderPrediction... newestFirst) {
        ProviderPredictionRepository predictions = mock(ProviderPredictionRepository.class);
        when(predictions.findByCardItemIdOrderByCreatedAtDesc(CARD)).thenReturn(List.of(newestFirst));
        ConditionAssessmentRepository assessments = mock(ConditionAssessmentRepository.class);
        when(assessments.findByCardItemIdAndIsCurrentTrue(CARD)).thenReturn(Optional.ofNullable(current));
        when(assessments.save(any())).thenAnswer(call -> call.getArgument(0));
        CardItems cardItems = mock(CardItems.class);
        when(cardItems.get(CARD)).thenReturn(new CardItems.CardItemRef(CARD, ORG, "A card", false, 1, Instant.now()));
        when(cardItems.passedPhotos(CARD)).thenReturn(passed);
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

    /**
     * Front measured at 54.04, stored as the provider does now: the share rounded to 54.0 beside the
     * side's own cap from the unrounded share. With the 1.0 margin that is 55.04, past 55: PSA 9.
     */
    private static ProviderPrediction frontAt5404(boolean withSideCap) {
        Map<String, Object> front = withSideCap
                ? Map.of("worstShare", 54.0, CenteringPreGradeProvider.SIDE_CAP, PsaCentering.cap(54.04, null, 1.0))
                : Map.of("worstShare", 54.0);
        Map<String, Object> details = Map.of("frontCentering", front, "backCentering", side(null), "measurementMargin", 1.0);
        return ProviderPrediction.succeeded(request(CenteringPreGradeProvider.NAME), new PreGradeProvider.Reading(
                CenteringPreGradeProvider.VERSION, null, null, (double) PsaCentering.cap(54.04, null, 1.0), null, null, null, details),
                Map.of(), 1);
    }

    /** Review of 2026-11, finding 5: the rounded share must not loosen the front's cap from 9 to 10. */
    @Test
    void aRoundedFrontShareKeepsItsCapThroughARetakeOfTheBack() {
        assertThat(PsaCentering.cap(54.04, null, 1.0)).isEqualTo(9);
        assertThat(PsaCentering.cap(54.0, null, 1.0)).as("the rounded share alone would allow a 10").isEqualTo(10);

        // The retake measures only the back, which alone allows a 10.
        ConditionAssessment built = rebuild(measuredSides(null, 52.0), frontAt5404(true), graded("A", 9.0, null)).orElseThrow();

        assertThat(built.getBasis()).containsEntry("centeringCap", 9);
    }

    /** Rows stored before the side's cap was kept: read the rounded share at the highest it could have been. */
    @Test
    void anOlderRowWithOnlyTheRoundedShareStaysConservative() {
        ConditionAssessment built = rebuild(measuredSides(null, 52.0), frontAt5404(false), graded("A", 9.0, null)).orElseThrow();

        assertThat(built.getBasis()).containsEntry("centeringCap", 9);
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

    /** A grade made on these photos. */
    private static ProviderPrediction gradedOn(CardItems.PhotoRef front, CardItems.PhotoRef back, double overall) {
        return ProviderPrediction.succeeded(request("A", front, back),
                new PreGradeProvider.Reading(null, overall, null, null, null, null, null, Map.of()), Map.of(), 1);
    }

    @Test
    void anAssessmentRecordsThePhotosItWasMadeFrom() {
        List<String> inUse = List.of(FRONT.id().toString(), BACK.id().toString());

        assertThat(rebuild(graded("A", 9.0, null)).orElseThrow().getBasis()).containsEntry("photoIds", inUse);
        assertThat(byHand(even(Grade.of(7), Grade.of(8)), false).getBasis()).containsEntry("photoIds", inUse);
    }

    /** Review of 2026-11-01, item 3: a retake left the assessment on the replaced photo's grades. */
    @Test
    void gradesOfARetakenPhotoDontBuildAnAssessment() {
        CardItems.PhotoRef retake = photo("FRONT", TAKEN.plusSeconds(3600));

        Optional<ConditionAssessment> built = service(null, List.of(FRONT, retake, BACK), measured(9), gradedOn(FRONT, BACK, 9.0))
                .rebuildSystemAssessment(CARD);

        assertThat(built).isEmpty();
    }

    @Test
    void anAdminRebuildAfterARetakeSaysToAskAgain() {
        CardItems.PhotoRef retake = photo("BACK", TAKEN.plusSeconds(3600));

        assertThatThrownBy(() -> service(null, List.of(FRONT, BACK, retake), gradedOn(FRONT, BACK, 9.0)).rebuildNow(ORG, CARD))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Ask again");
    }

    @Test
    void gradesOfTheRetakeBuildOne() {
        CardItems.PhotoRef retake = photo("FRONT", TAKEN.plusSeconds(3600));

        Optional<ConditionAssessment> built = service(null, List.of(FRONT, retake, BACK), gradedOn(retake, BACK, 8.0), gradedOn(FRONT, BACK, 9.0))
                .rebuildSystemAssessment(CARD);

        assertThat(built).isPresent();
        assertThat(built.get().getBasis()).containsEntry("photoIds", List.of(retake.id().toString(), BACK.id().toString()));
    }

    @Test
    void aRetakeMakesTheAssessmentStale() {
        ConditionAssessment made = rebuild(graded("A", 9.0, null)).orElseThrow();
        CardItems.PhotoRef retake = photo("BACK", TAKEN.plusSeconds(3600));

        assertThat(Assessments.photosChanged(made, List.of(FRONT, BACK), List::of)).isFalse();
        assertThat(Assessments.photosChanged(made, List.of(FRONT, BACK, retake), List::of)).isTrue();
    }

    @Test
    void aReviewersAssessmentOfRetakenPhotosGivesWayToTheNewGrades() {
        ConditionAssessment reviewed = byHand(even(Grade.of(9), Grade.of(10)), false);
        CardItems.PhotoRef retake = photo("FRONT", TAKEN.plusSeconds(3600));

        assertThat(service(reviewed, List.of(FRONT, BACK), graded("A", 8.0, null)).rebuildSystemAssessment(CARD))
                .as("the reviewer's still stands on the photos they saw").isEmpty();
        assertThat(service(reviewed, List.of(FRONT, retake, BACK), gradedOn(retake, BACK, 8.0)).rebuildSystemAssessment(CARD))
                .isPresent();
    }

    /** Assessments saved before they recorded their photos. */
    @Test
    void anOlderReviewersAssessmentIsStaleOnceAPhotoInUseCameLater() {
        ConditionAssessment reviewed = byHand(even(Grade.of(9), Grade.of(10)), false);
        reviewed.getBasis().remove("photoIds");
        CardItems.PhotoRef later = photo("FRONT", reviewed.getCreatedAt().plusSeconds(60));

        assertThat(Assessments.photosChanged(reviewed, List.of(FRONT, BACK), List::of)).isFalse();
        assertThat(Assessments.photosChanged(reviewed, List.of(FRONT, later, BACK), List::of)).isTrue();
    }

    @Test
    void anOlderSystemAssessmentIsStaleWhenItsGradesWereOnOtherPhotos() {
        ProviderPrediction grade = gradedOn(FRONT, BACK, 9.0);
        ConditionAssessment made = rebuild(grade).orElseThrow();
        made.getBasis().remove("photoIds");
        CardItems.PhotoRef retake = photo("FRONT", TAKEN.plusSeconds(3600));

        assertThat(Assessments.photosChanged(made, List.of(FRONT, BACK), () -> List.of(grade))).isFalse();
        assertThat(Assessments.photosChanged(made, List.of(FRONT, retake, BACK), () -> List.of(grade))).isTrue();
    }

    /** Ximilar was removed on 2026-11-10 (too inaccurate): its stored grades never build a distribution. */
    @Test
    void aRetiredProvidersGradesBuildNothing() {
        assertThat(rebuild(graded("XIMILAR", 9.0, null))).isEmpty();

        ConditionAssessment built = rebuild(graded("XIMILAR", 9.0, null), graded("CARDGRADE", 7.0, null)).orElseThrow();
        assertThat(built.getBasis().get("providerGrades")).isEqualTo(Map.of("CARDGRADE", 7.0));
        assertThat(Assessments.builtOnRetiredProvider(built)).isFalse();
    }

    @Test
    void aSystemAssessmentThatTookInARetiredProviderNeedsAssessingAgainButAReviewersStands() {
        ConditionAssessment system = rebuild(graded("CARDGRADE", 7.0, null)).orElseThrow();
        system.getBasis().put("providerGrades", Map.of("XIMILAR", 9.0, "CARDGRADE", 7.0));
        ConditionAssessment reviewed = byHand(even(Grade.of(7), Grade.of(8)), false);
        reviewed.getBasis().put("providerGrades", Map.of("XIMILAR", 9.0));

        assertThat(Assessments.builtOnRetiredProvider(system)).isTrue();
        assertThat(Assessments.builtOnRetiredProvider(reviewed)).isFalse();
    }

    @Test
    void withNothingMeasuredThereIsNoCap() {
        ConditionAssessment saved = byHand(even(Grade.of(9), Grade.of(10)), false);

        assertThat(saved.getBasis().get("centeringCap")).isNull();
    }

    /** Review B2: the 10 cap and U floor shape system distributions only. */
    @Test
    void aSystemAssessmentGetsTheTenCapAndTheUngradableFloor() {
        GradeDistribution built = rebuild(graded("A", 10.0, null), graded("B", 10.0, null)).orElseThrow().distribution();

        assertThat(built.p(Grade.G10)).isLessThanOrEqualTo(new BigDecimal("0.25"));
        assertThat(built.p(Grade.U)).isEqualByComparingTo("0.02");
    }

    @Test
    void aHandDistributionGetsNeitherTheTenCapNorTheUngradableFloor() {
        GradeDistribution allTen = GradeDistribution.certain(Grade.G10);

        ConditionAssessment saved = byHand(allTen, false, graded("A", 10.0, null));

        assertThat(saved.distribution()).isEqualTo(allTen);
        assertThat(saved.distribution().p(Grade.U)).isZero();
    }
}

package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pokemans.app.catalog.Catalog;
import com.pokemans.app.catalog.IdentifyWaits;
import com.pokemans.app.condition.Assessments;
import com.pokemans.app.condition.PreGradeFailures;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.offers.CardStatusService.CardStatus;
import com.pokemans.app.offers.CardStatusService.Need;
import com.pokemans.app.offers.CardStatusService.Stage;
import com.pokemans.app.valuation.CardInputs;
import com.pokemans.app.valuation.Valuations;
import com.pokemans.valuation.ParameterSet;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CardStatusServiceTest {

    static final OfferPolicy POLICY = new OfferPolicy(ParameterSet.load("1.2.0"),
            new OffersProperties(Duration.ofDays(7), Duration.ofDays(14), new BigDecimal("5000"), true, new BigDecimal("0.10"), 90));

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    @Test
    void aLabelIsOverduePastItsDateUntilTheCardShips() {
        Instant late = NOW.minus(Duration.ofDays(4));
        assertThat(ListingStateMachine.overdue(late, ListingStateMachine.DealStage.SOLD, NOW)).isTrue();
        assertThat(ListingStateMachine.overdue(NOW.minus(Duration.ofDays(2)), ListingStateMachine.DealStage.SOLD, NOW)).isFalse();
        assertThat(ListingStateMachine.overdue(late, ListingStateMachine.DealStage.SHIPPED, NOW)).isFalse();
        assertThat(ListingStateMachine.overdue(late, ListingStateMachine.DealStage.RETURNED, NOW)).isFalse();
    }

    private static Need need(Stage stage, Instant deadline, boolean retake) {
        return CardStatusService.need(new CardStatus(UUID.randomUUID(), stage, null, "", false).withDeadline(deadline), retake, NOW);
    }

    @Test
    void anOfferCloseToItsEndSaysSo() {
        assertThat(need(Stage.OFFERED, NOW.plus(Duration.ofHours(49)), false)).isEqualTo(Need.OFFER_READY);
        assertThat(need(Stage.OFFERED, NOW.plus(Duration.ofHours(47)), false)).isEqualTo(Need.OFFER_EXPIRING);
    }

    @Test
    void aSoldCardNeedsItsSellerOnlyWhileThereIsALabelToUse() {
        assertThat(need(Stage.SOLD, NOW.minus(Duration.ofDays(1)), false)).isEqualTo(Need.LABEL_READY);
        assertThat(need(Stage.SOLD, null, false)).isNull();
    }

    @Test
    void afterShippingADealNeedsItsSellerOnlyWhileItWaitsOnTheirWord() {
        CardStatus arrived = new CardStatus(UUID.randomUUID(), Stage.ARRIVED, null, "", false);
        CardStatus paid = new CardStatus(UUID.randomUUID(), Stage.PAID, null, "", false);
        CardStatus returned = new CardStatus(UUID.randomUUID(), Stage.RETURNED, null, "", false);
        assertThat(CardStatusService.need(arrived.withSellersWord(true), false, NOW)).isEqualTo(Need.PAYMENT_DUE);
        assertThat(CardStatusService.need(paid.withSellersWord(true), false, NOW)).isEqualTo(Need.PAYMENT_CHECK);
        assertThat(CardStatusService.need(returned.withSellersWord(true), false, NOW)).isEqualTo(Need.CARD_RETURNING);
        // Raised with us, confirmed, or held for our review: nothing for them to say.
        for (CardStatus waiting : new CardStatus[] {arrived, paid, returned}) {
            assertThat(CardStatusService.need(waiting, false, NOW)).isNull();
        }
        assertThat(need(Stage.SHIPPED, null, false)).isNull();
    }

    @Test
    void photosNeedTheSellerOnlyAfterAFailedTry() {
        assertThat(need(Stage.PHOTOS, null, true)).isEqualTo(Need.RETAKE);
        assertThat(need(Stage.PHOTOS, null, false)).isNull();
    }

    @Test
    void cardsWaitingOnUsOrABuyerAreNotTheSellers() {
        assertThat(need(Stage.READY, null, false)).isEqualTo(Need.GET_OFFER);
        for (Stage stage : new Stage[] {Stage.LOT, Stage.IDENTIFYING, Stage.ASSESSING, Stage.PRICING, Stage.NO_OFFER, Stage.OVER_LIMIT, Stage.LISTED}) {
            assertThat(need(stage, null, false)).isNull();
        }
    }

    @Test
    void theLotCopyNamesTheMinimumFromTheParameterSet() {
        // Review finding 47: the $25 was written into the copy.
        CardItems cardItems = mock(CardItems.class);
        CardStatusService service = new CardStatusService(mock(OfferService.class), mock(SaleListingRepository.class),
                mock(ListingEventRepository.class), cardItems, mock(CardOfferVoidRepository.class), mock(Assessments.class),
                mock(Shipping.class), POLICY, mock(PreGradeFailures.class),
                mock(IdentifyWaits.class), mock(Catalog.class));
        UUID lot = UUID.randomUUID();
        when(cardItems.get(lot)).thenReturn(new CardItems.CardItemRef(lot, UUID.randomUUID(), "Binder", true, 40, NOW));

        assertThat(POLICY.minFmvText()).isEqualTo("$25");
        assertThat(service.status(lot).nextStep()).contains("worth " + POLICY.minFmvText() + " or more");
    }

    /** An identified card with front and back photos and no assessment, as the status service sees it. */
    private static CardStatus unassessed(boolean preGradeFailed) {
        return identified("no condition assessment", OfferRefusal.NOT_ASSESSED, "Its condition hasn't been assessed yet.", preGradeFailed);
    }

    /** An identified card with front and back photos, valued or not, and the offer flow's answer for it. */
    private static CardStatus identified(String unvaluedReason, OfferRefusal refused, String refusal, boolean preGradeFailed) {
        return identified(unvaluedReason, refused, refusal, preGradeFailed, Instant.now());
    }

    private static CardStatus identified(String unvaluedReason, OfferRefusal refused, String refusal, boolean preGradeFailed,
                                         Instant photographed) {
        OfferService offers = mock(OfferService.class);
        CardItems cardItems = mock(CardItems.class);
        PreGradeFailures failures = mock(PreGradeFailures.class);
        CardStatusService service = new CardStatusService(offers, mock(SaleListingRepository.class), mock(ListingEventRepository.class),
                cardItems, mock(CardOfferVoidRepository.class), mock(Assessments.class), mock(Shipping.class), POLICY, failures,
                mock(IdentifyWaits.class), mock(Catalog.class));
        UUID card = UUID.randomUUID();
        when(cardItems.get(card)).thenReturn(new CardItems.CardItemRef(card, UUID.randomUUID(), "Umbreon", false, 1, NOW));
        when(cardItems.passedPhotos(card)).thenReturn(List.of(
                new CardItems.PhotoRef(UUID.randomUUID(), card, "FRONT", "k1", "image/jpeg", null, photographed, photographed, "a", null),
                new CardItems.PhotoRef(UUID.randomUUID(), card, "BACK", "k2", "image/jpeg", null, photographed, photographed, "b", null)));
        CardInputs.Identity umbreon = new CardInputs.Identity(UUID.randomUUID(), "Umbreon", "Evolving Skies", "215", "en", null,
                "UNLIMITED", "NORMAL", false, null, List.of());
        CardInputs inputs = mock(CardInputs.class);
        when(inputs.identity()).thenReturn(umbreon);
        when(inputs.unvaluedReason()).thenReturn(unvaluedReason);
        Valuations.CurrentValue value = mock(Valuations.CurrentValue.class);
        when(value.inputs()).thenReturn(inputs);
        when(offers.quote(card)).thenReturn(new OfferService.Quote(value, refused, refusal));
        when(failures.failed(card)).thenReturn(preGradeFailed);
        return service.status(card);
    }

    @Test
    void aCardWhosePreGradeFailedSaysSoPlainlyWithoutProviderDetail() {
        CardStatus status = unassessed(true);
        assertThat(status.stage()).isEqualTo(Stage.ASSESSING);
        assertThat(status.nextStep()).startsWith("The automatic condition check didn't work for this card, so we'll check it by hand, usually within a day or two.")
                .doesNotContainIgnoringCase("cardgrade").doesNotContainIgnoringCase("credit");
    }

    /** Walkthrough 2026-11-23: a card over the limit said "No offer yet", but no instant offer ever comes for it. */
    @Test
    void aCardOverTheOfferLimitSaysSoInsteadOfNoOfferYet() {
        CardStatus status = identified(null, OfferRefusal.OVER_MAXIMUM, "Instant offers go up to $5,000, the most a shipping label "
                + "can insure, and this card is worth more than that, so it gets no offer.", false);
        assertThat(status.stage()).isEqualTo(Stage.OVER_LIMIT);
        assertThat(status.canOffer()).isFalse();
        assertThat(status.nextStep()).startsWith("Instant offers go up to $5,000").endsWith("You're free to sell it elsewhere.");
        assertThat(CardStatusService.need(status, false, NOW)).isNull();

        assertThat(identified(null, OfferRefusal.THIN_SALES, "There isn't enough sales data.", false).stage()).isEqualTo(Stage.NO_OFFER);
    }

    @Test
    void aCardStillWaitingForItsPreGradeKeepsTheUsualText() {
        assertThat(unassessed(false).nextStep()).startsWith("We're reviewing its condition from the photos.");
    }

    @Test
    void theNotificationNoteIsTrueNow() {
        // Review finding 1: we now email when the offer is ready (OfferReadyEmails).
        assertThat(unassessed(false).nextStep()).endsWith(CardStatusService.OFFER_EMAIL).doesNotContain("check back here");
    }

    @Test
    void aCardWaitingOnUsSaysSinceWhen() {
        Instant photographed = Instant.now().minus(Duration.ofHours(3));
        CardStatus status = identified("no condition assessment", OfferRefusal.NOT_ASSESSED, "Not assessed.", false, photographed);
        assertThat(status.waitingSince()).isEqualTo(photographed);
        assertThat(status.nextStep()).contains("usually takes a few minutes");
    }

    /** Methodology Step 8 (Zach, 2026-11-23): comps added by hand have a 3-day target, and no offer after 14 days. */
    @Test
    void aCardWaitingOnlyOnCompsSaysTheTargetThenGetsNoOfferAfterTwoWeeks() {
        Instant recent = Instant.now().minus(Duration.ofDays(2));
        CardStatus waiting = identified("no PSA price table", null, null, false, recent);
        assertThat(waiting.stage()).isEqualTo(Stage.PRICING);
        assertThat(waiting.waitingSince()).isEqualTo(recent);
        assertThat(waiting.nextStep()).contains("within 3 days").contains("within 14 days, it gets no offer");

        CardStatus gaveUp = identified("no PSA price table", null, null, false, Instant.now().minus(Duration.ofDays(15)));
        assertThat(gaveUp.stage()).isEqualTo(Stage.NO_OFFER);
        assertThat(gaveUp.waitingSince()).isNull();
        assertThat(gaveUp.nextStep()).startsWith("We couldn't find enough sold prices").endsWith("You're free to sell it elsewhere.");

        CardStatus thin = identified(null, OfferRefusal.THIN_SALES, "There isn't enough sales data.", false, recent);
        assertThat(thin.stage()).isEqualTo(Stage.NO_OFFER);
        assertThat(thin.nextStep()).startsWith("There isn't enough sales data. We're adding more by hand");
        assertThat(identified(null, OfferRefusal.THIN_SALES, "There isn't enough sales data.", false,
                Instant.now().minus(Duration.ofDays(15))).nextStep()).startsWith("We couldn't find enough sold prices");
    }

    @Test
    void comps14DaysIsTheEdge() {
        assertThat(CardStatusService.gaveUpOnComps(NOW.minus(Duration.ofDays(14)), NOW)).isTrue();
        assertThat(CardStatusService.gaveUpOnComps(NOW.minus(Duration.ofDays(14)).plusSeconds(1), NOW)).isFalse();
        assertThat(CardStatusService.gaveUpOnComps(null, NOW)).isFalse();
    }

    /** Review finding 2: Home lists a card whose identity waits on the collector's answer. */
    @Test
    void aCardWaitingOnTheCollectorsAnswerNeedsThem() {
        CardStatus waiting = new CardStatus(UUID.randomUUID(), Stage.IDENTIFYING, null, "", false);
        assertThat(CardStatusService.need(waiting.withSellersWord(true), false, NOW)).isEqualTo(Need.CONFIRM_CARD);
        assertThat(CardStatusService.need(waiting, false, NOW)).isNull();
    }

    @Test
    void anUnidentifiedCardSaysWhoseTurnItIs() {
        OfferService offers = mock(OfferService.class);
        CardItems cardItems = mock(CardItems.class);
        IdentifyWaits waits = mock(IdentifyWaits.class);
        CardStatusService service = new CardStatusService(offers, mock(SaleListingRepository.class), mock(ListingEventRepository.class),
                cardItems, mock(CardOfferVoidRepository.class), mock(Assessments.class), mock(Shipping.class), POLICY,
                mock(PreGradeFailures.class), waits, mock(Catalog.class));
        UUID card = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        when(cardItems.get(card)).thenReturn(new CardItems.CardItemRef(card, org, "New card", false, 1, NOW));
        when(cardItems.inCollectionsOf(org)).thenReturn(List.of(new CardItems.CardItemRef(card, org, "New card", false, 1, NOW)));
        when(cardItems.passedPhotos(card)).thenReturn(List.of(
                new CardItems.PhotoRef(UUID.randomUUID(), card, "FRONT", "k1", "image/jpeg", null, NOW, NOW, "a", null),
                new CardItems.PhotoRef(UUID.randomUUID(), card, "BACK", "k2", "image/jpeg", null, NOW, NOW, "b", null)));
        CardInputs inputs = mock(CardInputs.class);
        when(inputs.unvaluedReason()).thenReturn("not identified");
        Valuations.CurrentValue value = mock(Valuations.CurrentValue.class);
        when(value.inputs()).thenReturn(inputs);
        when(offers.quote(card)).thenReturn(new OfferService.Quote(value, OfferRefusal.NOT_ASSESSED, "Not identified."));

        when(waits.onCollector(card)).thenReturn(true);
        CardStatus yours = service.status(card);
        assertThat(yours.stage()).isEqualTo(Stage.IDENTIFYING);
        assertThat(yours.sellersWord()).isTrue();
        assertThat(yours.nextStep()).startsWith("Tell us which card it is");
        assertThat(service.needsYou(org, true)).extracting(CardStatusService.NeedsYou::need).containsExactly(Need.CONFIRM_CARD);

        when(waits.onCollector(card)).thenReturn(false);
        CardStatus ours = service.status(card);
        assertThat(ours.sellersWord()).isFalse();
        assertThat(ours.waitingSince()).isEqualTo(NOW);
        assertThat(service.needsYou(org, false)).singleElement().satisfies(n -> {
            assertThat(n.need()).isEqualTo(Need.CHECKING);
            assertThat(n.due()).isEqualTo(NOW);
        });
    }

    @Test
    void aPostedOrOfferedCardIsntValuedAgainForItsStatus() {
        // Review finding 9: the status quoted every card (a full valuation) before looking at its listing, so the card
        // list and the home page valued every sold and posted card on each load.
        OfferService offers = mock(OfferService.class);
        SaleListingRepository listings = mock(SaleListingRepository.class);
        CardItems cardItems = mock(CardItems.class);
        CardStatusService service = new CardStatusService(offers, listings, mock(ListingEventRepository.class), cardItems,
                mock(CardOfferVoidRepository.class), mock(Assessments.class), mock(Shipping.class), POLICY, mock(PreGradeFailures.class),
                mock(IdentifyWaits.class), mock(Catalog.class));
        CardInputs.Identity umbreon = new CardInputs.Identity(UUID.randomUUID(), "Umbreon", "Evolving Skies", "215", "en", null,
                "UNLIMITED", "NORMAL", false, null, List.of());

        UUID posted = UUID.randomUUID();
        when(cardItems.get(posted)).thenReturn(new CardItems.CardItemRef(posted, UUID.randomUUID(), "Umbreon", false, 1, NOW));
        SaleListing listing = new SaleListing(UUID.randomUUID(), UUID.randomUUID(), posted, UUID.randomUUID(), Instant.now(),
                Instant.now().plus(Duration.ofDays(14)));
        when(listings.findFirstByCardItemIdOrderByCreatedAtDesc(posted)).thenReturn(Optional.of(listing));
        when(offers.identity(listing.getOfferId())).thenReturn(umbreon);

        CardStatus status = service.status(posted);
        assertThat(status.stage()).isEqualTo(Stage.LISTED);
        assertThat(status.identity()).isEqualTo("Umbreon · Evolving Skies 215 · EN");

        UUID offered = UUID.randomUUID();
        when(cardItems.get(offered)).thenReturn(new CardItems.CardItemRef(offered, UUID.randomUUID(), "Umbreon", false, 1, NOW));
        CardOffer offer = mock(CardOffer.class);
        UUID offerId = UUID.randomUUID();
        when(offer.getId()).thenReturn(offerId);
        when(offer.getExpiresAt()).thenReturn(Instant.now().plus(Duration.ofDays(7)));
        when(offers.latest(offered)).thenReturn(Optional.of(offer));
        when(offers.identity(offerId)).thenReturn(umbreon);
        assertThat(service.status(offered).stage()).isEqualTo(Stage.OFFERED);

        verify(offers, never()).quote(any());
    }
}

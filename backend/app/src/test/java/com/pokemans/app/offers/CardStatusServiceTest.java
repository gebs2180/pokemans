package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pokemans.app.condition.Assessments;
import com.pokemans.app.intake.CardItems;
import com.pokemans.app.offers.CardStatusService.CardStatus;
import com.pokemans.app.offers.CardStatusService.Need;
import com.pokemans.app.offers.CardStatusService.Stage;
import com.pokemans.app.valuation.CardInputs;
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
            new OffersProperties(Duration.ofDays(7), Duration.ofDays(14), new BigDecimal("5000"), true));

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
        for (Stage stage : new Stage[] {Stage.LOT, Stage.IDENTIFYING, Stage.ASSESSING, Stage.PRICING, Stage.NO_OFFER, Stage.LISTED}) {
            assertThat(need(stage, null, false)).isNull();
        }
    }

    @Test
    void theLotCopyNamesTheMinimumFromTheParameterSet() {
        // Review finding 47: the $25 was written into the copy.
        CardItems cardItems = mock(CardItems.class);
        CardStatusService service = new CardStatusService(mock(OfferService.class), mock(SaleListingRepository.class),
                mock(ListingEventRepository.class), cardItems, mock(CardOfferVoidRepository.class), mock(Assessments.class),
                mock(Shipping.class), POLICY);
        UUID lot = UUID.randomUUID();
        when(cardItems.get(lot)).thenReturn(new CardItems.CardItemRef(lot, UUID.randomUUID(), "Binder", true, 40, NOW));

        assertThat(POLICY.minFmvText()).isEqualTo("$25");
        assertThat(service.status(lot).nextStep()).contains("worth " + POLICY.minFmvText() + " or more");
    }

    @Test
    void aPostedOrOfferedCardIsntValuedAgainForItsStatus() {
        // Review finding 9: the status quoted every card (a full valuation) before looking at its listing, so the card
        // list and the home page valued every sold and posted card on each load.
        OfferService offers = mock(OfferService.class);
        SaleListingRepository listings = mock(SaleListingRepository.class);
        CardItems cardItems = mock(CardItems.class);
        CardStatusService service = new CardStatusService(offers, listings, mock(ListingEventRepository.class), cardItems,
                mock(CardOfferVoidRepository.class), mock(Assessments.class), mock(Shipping.class), POLICY);
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

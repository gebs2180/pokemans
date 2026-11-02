package com.pokemans.app.offers;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokemans.app.offers.CardStatusService.CardStatus;
import com.pokemans.app.offers.CardStatusService.Need;
import com.pokemans.app.offers.CardStatusService.Stage;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CardStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    @Test
    void aLabelIsOverduePastItsDateUntilTheCardShips() {
        Instant late = NOW.minus(Duration.ofDays(4));
        assertThat(ListingService.overdue(late, ListingService.DealStage.SOLD, NOW)).isTrue();
        assertThat(ListingService.overdue(NOW.minus(Duration.ofDays(2)), ListingService.DealStage.SOLD, NOW)).isFalse();
        assertThat(ListingService.overdue(late, ListingService.DealStage.SHIPPED, NOW)).isFalse();
        assertThat(ListingService.overdue(late, ListingService.DealStage.RETURNED, NOW)).isFalse();
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
}

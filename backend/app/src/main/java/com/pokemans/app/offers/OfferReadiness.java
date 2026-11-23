package com.pokemans.app.offers;

import com.pokemans.app.intake.CardItems;
import com.pokemans.app.offers.CardStatusService.CardStatus;
import com.pokemans.app.offers.CardStatusService.Stage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * What other modules may read about which cards have an offer ready, e.g. to email the collector (review finding 1;
 * Zach, 2026-11-23). No access checks: callers decide who hears what.
 */
@Service
public class OfferReadiness {

    /**
     * A card whose offer is ready to get, or already made.
     *
     * @param addedBy who added the card: the person to tell
     * @param title   the card as identified, else its label
     */
    public record Ready(UUID cardId, UUID orgId, UUID addedBy, String title) {
    }

    private static final Set<Stage> READY = EnumSet.of(Stage.READY, Stage.OFFERED);

    private final CardItems cardItems;
    private final CardStatusService statuses;

    OfferReadiness(CardItems cardItems, CardStatusService statuses) {
        this.cardItems = cardItems;
        this.statuses = statuses;
    }

    /**
     * Single cards (not lots) whose photos last passed in {@code [since, before)} and whose offer is ready now. A card
     * past this window has waited longer than comps may take (14 days), so it is either ready already or gets none.
     */
    public List<Ready> photographedBetween(Instant since, Instant before) {
        List<Ready> ready = new ArrayList<>();
        for (CardItems.CardItemRef card : cardItems.captureCompletedBetween(since, before)) {
            if (card.lot()) {
                continue;
            }
            CardStatus status = statuses.status(card.id());
            if (READY.contains(status.stage())) {
                String title = status.identity() == null || status.identity().isBlank() ? card.label() : status.identity();
                ready.add(new Ready(card.id(), card.orgId(), cardItems.addedBy(card.id()), title));
            }
        }
        return ready;
    }
}

package com.pokemans.app.offers;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A collector taking a card off their list: only while it has no live offer and has never been posted, so no buyer
 * or deal ever depends on it. Its photos and predictions stay for calibration (methodology Step 8, "Removing a
 * card"; Zach, 2026-11-23). No access check: the controller has done it.
 */
@Service
class CardArchive {

    private final CardItems cardItems;
    private final OfferService offers;
    private final SaleListingRepository listings;
    private final CardOfferVoidRepository voids;
    private final CurrentUser currentUser;

    CardArchive(CardItems cardItems, OfferService offers, SaleListingRepository listings, CardOfferVoidRepository voids,
                CurrentUser currentUser) {
        this.cardItems = cardItems;
        this.offers = offers;
        this.listings = listings;
        this.voids = voids;
        this.currentUser = currentUser;
    }

    /** Whether the card may be archived now. */
    @Transactional(readOnly = true)
    boolean allowed(UUID cardId) {
        return listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardId).isEmpty() && liveOffer(cardId) == null;
    }

    /** Archives the card, else 409 saying why not. */
    @Transactional
    void archive(UUID cardId) {
        if (listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This card has been posted to buyers, so it stays on your list.");
        }
        if (liveOffer(cardId) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This card has an offer: let it expire before you archive it.");
        }
        cardItems.archive(cardId, currentUser.id());
    }

    private CardOffer liveOffer(UUID cardId) {
        return offers.latest(cardId).filter(o -> o.getExpiresAt().isAfter(Instant.now()))
                .filter(o -> voids.findByOfferId(o.getId()).isEmpty()).orElse(null);
    }
}

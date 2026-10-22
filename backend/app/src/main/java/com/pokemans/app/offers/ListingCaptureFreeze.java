package com.pokemans.app.offers;

import com.pokemans.app.intake.CaptureFreeze;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A card's photos stay as buyers saw them once it is posted, and for good once it is sold: the buyer
 * terms let a card go back when it doesn't match its photos, so neither side may change them.
 */
@Component
class ListingCaptureFreeze implements CaptureFreeze {

    private final SaleListingRepository listings;
    private final ListingEventRepository events;

    ListingCaptureFreeze(SaleListingRepository listings, ListingEventRepository events) {
        this.listings = listings;
        this.events = events;
    }

    @Override
    public Optional<String> frozenReason(UUID cardItemId) {
        return listings.findFirstByCardItemIdOrderByCreatedAtDesc(cardItemId).flatMap(l -> {
            List<ListingEvent> history = events.findByListingIdInOrderByCreatedAtAsc(List.of(l.getId()));
            return switch (ListingService.status(l, history, Instant.now())) {
                case OPEN -> Optional.of("This card is posted to buyers, so its photos can't change. Withdraw it first to retake one.");
                case ACCEPTED -> Optional.of("This card is sold, so its photos stay as the buyer saw them.");
                default -> Optional.empty();
            };
        });
    }
}

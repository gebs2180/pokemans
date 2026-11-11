package com.pokemans.app.offers;

import com.pokemans.app.offers.ListingStateMachine.DealStage;
import com.pokemans.app.offers.ListingStateMachine.Progress;
import com.pokemans.app.valuation.CardInputs;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What other modules may read about sales, e.g. to tell each side what happened. No access checks: callers decide who sees what. */
@Service
public class Deals {

    /**
     * A taken card and where its deal stands.
     *
     * @param sellerUserId who posted the card
     * @param buyerUserId  who accepted it
     * @param arrivedAt    when it arrived genuine and as shown, or null
     * @param payBy        when payment is due, or null before arrival
     * @param owed         arrived, not paid, not returned, not cancelled: the buyer still has to pay
     */
    public record Deal(UUID listingId, UUID cardItemId, Card card, BigDecimal price, UUID sellerOrgId, UUID sellerUserId,
                       UUID buyerOrgId, UUID buyerUserId, Instant arrivedAt, Instant payBy, BigDecimal pricePaid, boolean owed) {
    }

    /** A card as a buyer is shown it, without the seller's own label. */
    public record Card(String name, String setName, String number) {

        /** E.g. "Charizard, Base Set #4". */
        public String title() {
            StringBuilder s = new StringBuilder(name == null ? "Card" : name);
            if (setName != null) {
                s.append(", ").append(setName);
            }
            if (number != null) {
                s.append(" #").append(number);
            }
            return s.toString();
        }
    }

    /** A card on the market. */
    public record Listing(UUID listingId, UUID sellerOrgId, Card card, BigDecimal price, Instant postedAt, Instant expiresAt) {
    }

    private final SaleListingRepository listings;
    private final ListingEventRepository events;
    private final CardOfferRepository offers;

    Deals(SaleListingRepository listings, ListingEventRepository events, CardOfferRepository offers) {
        this.listings = listings;
        this.events = events;
        this.offers = offers;
    }

    /** The deal on a listing, or empty with no buyer yet, or once cancelled. */
    @Transactional(readOnly = true)
    public Optional<Deal> deal(UUID listingId) {
        Optional<SaleListing> found = listings.findById(listingId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SaleListing listing = found.get();
        List<ListingEvent> history = events.history(List.of(listing)).getOrDefault(listingId, List.of());
        Progress progress = ListingStateMachine.progress(history);
        if (progress == null) {
            return Optional.empty();
        }
        ListingEvent accepted = history.stream().filter(e -> e.getType() == ListingEvent.Type.ACCEPTED).findFirst().orElseThrow();
        CardOffer offer = offers.findById(listing.getOfferId()).orElseThrow();
        return Optional.of(new Deal(listingId, listing.getCardItemId(), card(offer), offer.getPrice(), listing.getOrgId(),
                listing.getListedBy(), accepted.getActorOrgId(), accepted.getActorUserId(), progress.arrivedAt(), progress.payBy(),
                progress.pricePaid(), progress.stage() == DealStage.ARRIVED));
    }

    /** Open listings posted after {@code since}, oldest first. */
    @Transactional(readOnly = true)
    public List<Listing> openPostedSince(Instant since, Instant now) {
        List<SaleListing> open = listings.findOpen(now).stream().filter(l -> l.getCreatedAt().isAfter(since)).toList();
        Map<UUID, CardOffer> byId = offers.findAllById(open.stream().map(SaleListing::getOfferId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(CardOffer::getId, o -> o));
        return open.stream()
                .sorted(Comparator.comparing(SaleListing::getCreatedAt))
                .map(l -> {
                    CardOffer offer = byId.get(l.getOfferId());
                    return new Listing(l.getId(), l.getOrgId(), card(offer), offer.getPrice(), l.getCreatedAt(), l.getExpiresAt());
                })
                .toList();
    }

    private static Card card(CardOffer offer) {
        CardInputs.Identity id = OfferService.inputs(offer).identity();
        return id == null ? new Card(null, null, null) : new Card(id.name(), id.setName(), id.number());
    }
}

package com.pokemans.app.offers;

import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.OrgAccess;
import com.pokemans.app.identity.OrgType;
import com.pokemans.app.intake.CardItems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Instant offers. Sellers (members of a collector or contributor org) see our offer on a card and post
 * it to buyers; buyers (members of a buyer org who accepted the buyer terms) see open listings, take
 * one, and record what they found on inspection.
 */
@RestController
class OfferController {

    private static final OrgType[] SELLER_ORGS = {OrgType.COLLECTOR, OrgType.CONTRIBUTOR};

    record PostRequest(@NotNull UUID offerId) {
    }

    /**
     * @param inspectedGrade PSA scale, half grades allowed; 0 for ungradable
     * @param declinedReason only if the card isn't genuine, isn't the listed card or doesn't match its photos
     */
    record InspectionBody(@NotNull @DecimalMin("0") @DecimalMax("10") @Digits(integer = 2, fraction = 1) BigDecimal inspectedGrade,
                          @NotNull Boolean authentic,
                          @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal pricePaid,
                          ListingEvent.DeclinedReason declinedReason,
                          @Size(max = 2000) String note) {
    }

    private final OfferService offers;
    private final ListingService listings;
    private final OrgAccess access;
    private final CardItems cardItems;
    private final Consents consents;

    OfferController(OfferService offers, ListingService listings, OrgAccess access, CardItems cardItems, Consents consents) {
        this.offers = offers;
        this.listings = listings;
        this.access = access;
        this.cardItems = cardItems;
        this.consents = consents;
    }

    // Sellers

    @GetMapping("/api/orgs/{orgId}/cards/{cardId}/sale")
    ListingService.SaleView sale(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        requireSellerCard(orgId, cardId);
        return listings.sale(cardId);
    }

    /** Prices the card now. 409 with the reason when it gets no offer. */
    @PostMapping("/api/orgs/{orgId}/cards/{cardId}/offers")
    @ResponseStatus(HttpStatus.CREATED)
    ListingService.OfferView price(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        requireSellerCard(orgId, cardId);
        return ListingService.offerView(offers.price(orgId, cardId));
    }

    /** Posts the offer to every buyer. Not a sale yet: a buyer has to take it up. */
    @PostMapping("/api/orgs/{orgId}/cards/{cardId}/listings")
    @ResponseStatus(HttpStatus.CREATED)
    ListingService.SaleView post(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody PostRequest request) {
        requireSellerCard(orgId, cardId);
        listings.post(orgId, cardId, request.offerId());
        return listings.sale(cardId);
    }

    @PostMapping("/api/orgs/{orgId}/listings/{listingId}/withdraw")
    void withdraw(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        access.require(orgId, SELLER_ORGS);
        listings.withdraw(orgId, listingId);
    }

    // Buyers

    @GetMapping("/api/orgs/{orgId}/market")
    List<ListingService.MarketItem> market(@PathVariable UUID orgId) {
        requireBuyer(orgId);
        return listings.market();
    }

    @PostMapping("/api/orgs/{orgId}/market/{listingId}/accept")
    List<ListingService.DealView> accept(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        requireBuyer(orgId);
        listings.accept(orgId, listingId);
        return listings.deals(orgId);
    }

    @GetMapping("/api/orgs/{orgId}/deals")
    List<ListingService.DealView> deals(@PathVariable UUID orgId) {
        requireBuyer(orgId);
        return listings.deals(orgId);
    }

    @PostMapping("/api/orgs/{orgId}/deals/{listingId}/inspection")
    @ResponseStatus(HttpStatus.CREATED)
    List<ListingService.DealView> inspect(@PathVariable UUID orgId, @PathVariable UUID listingId, @Valid @RequestBody InspectionBody body) {
        requireBuyer(orgId);
        if (body.inspectedGrade().remainder(new BigDecimal("0.5")).signum() != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grades go in whole or half steps");
        }
        listings.inspect(orgId, listingId, new ListingService.InspectionRequest(body.inspectedGrade(), body.authentic(), body.pricePaid(),
                body.declinedReason(), body.note()));
        return listings.deals(orgId);
    }

    private void requireSellerCard(UUID orgId, UUID cardId) {
        access.require(orgId, SELLER_ORGS);
        cardItems.requireAccessible(orgId, cardId);
    }

    private void requireBuyer(UUID orgId) {
        access.require(orgId, OrgType.BUYER);
        consents.requireAccepted(orgId);
    }
}

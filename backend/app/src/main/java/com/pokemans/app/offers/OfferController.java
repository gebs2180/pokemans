package com.pokemans.app.offers;

import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.OrgAccess;
import com.pokemans.app.identity.OrgType;
import com.pokemans.app.intake.CardItems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Instant offers. Sellers (members of a collector or contributor org) see our firm offer on a card and
 * post it to buyers; buyers (members of a buyer org who accepted the buyer terms) take one, get a
 * prepaid label sent to the seller, and record that the card arrived as shown and that they paid, or
 * return it, while the seller says whether they got paid. The admin's routes are in {@link OfferAdminController}.
 */
@RestController
class OfferController {

    private static final Logger log = LoggerFactory.getLogger(OfferController.class);
    private static final String LABEL_PENDING = "We couldn't buy the shipping label just now. Try again later; we've been told.";

    private static final OrgType[] SELLER_ORGS = {OrgType.COLLECTOR, OrgType.CONTRIBUTOR};
    private static final OrgType[] SHIPPING_ORGS = {OrgType.COLLECTOR, OrgType.CONTRIBUTOR, OrgType.BUYER};

    record PostRequest(@NotNull UUID offerId) {
    }

    /**
     * US addresses only in v1.
     *
     * @param paymentMethod with paymentHandle, how a seller gets paid; a seller's address needs both, a buyer's has neither
     */
    record AddressBody(@NotBlank @Size(max = 100) String name, @Size(max = 100) String company,
                       @NotBlank @Size(max = 200) String street1, @Size(max = 200) String street2,
                       @NotBlank @Size(max = 100) String city,
                       @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "use the two-letter state code") String state,
                       @NotBlank @Pattern(regexp = "\\d{5}(-\\d{4})?", message = "use a 5 or 9 digit ZIP code") String zip,
                       @Size(max = 30) String phone, PaymentHandle.Method paymentMethod,
                       @Size(max = 100) String paymentHandle) {
    }

    record AddressView(String name, String company, String street1, String street2, String city, String state, String zip,
                       String country, String phone, PaymentHandle.Method paymentMethod, String paymentHandle, Instant recordedAt) {
    }

    record ReceivedBody(@Size(max = 2000) String note) {
    }

    /** @param pricePaid what the buyer paid the seller: the firm price, unless they agreed otherwise between them (then say why in the note) */
    record PaidBody(@NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal pricePaid, @Size(max = 2000) String note) {
    }

    record DisputeBody(@Size(max = 2000) String note) {
    }

    record ReturnedBody(@NotNull ListingEvent.DeclinedReason reason, @Size(max = 2000) String note) {
    }

    /** @param labelPending why the label didn't come with the accept, or null; it can be bought again */
    record AcceptResult(List<ListingViews.DealView> deals, String labelPending) {
    }

    private final OfferService offers;
    private final ListingService listings;
    private final DealService deals;
    private final ListingViews views;
    private final Shipping shipping;
    private final OrgAccess access;
    private final CardItems cardItems;
    private final Consents consents;
    private final CardStatusService statuses;

    OfferController(OfferService offers, ListingService listings, DealService deals, ListingViews views, Shipping shipping, OrgAccess access, CardItems cardItems,
                    Consents consents, CardStatusService statuses) {
        this.offers = offers;
        this.listings = listings;
        this.deals = deals;
        this.views = views;
        this.shipping = shipping;
        this.access = access;
        this.cardItems = cardItems;
        this.consents = consents;
        this.statuses = statuses;
    }

    // Addresses: where a seller ships from, or a buyer ships to.

    @GetMapping("/api/orgs/{orgId}/ship-address")
    AddressView address(@PathVariable UUID orgId) {
        access.require(orgId, SHIPPING_ORGS);
        return shipping.address(orgId).map(OfferController::view).orElse(null);
    }

    @PostMapping("/api/orgs/{orgId}/ship-address")
    @ResponseStatus(HttpStatus.CREATED)
    AddressView recordAddress(@PathVariable UUID orgId, @Valid @RequestBody AddressBody body) {
        boolean seller = access.require(orgId, SHIPPING_ORGS).getType() != OrgType.BUYER;
        return view(shipping.record(orgId, new ShippingProvider.Address(body.name().strip(), blankToNull(body.company()),
                body.street1().strip(), blankToNull(body.street2()), body.city().strip(), body.state(), body.zip(), "US",
                blankToNull(body.phone())), seller ? payment(body) : null));
    }

    private static PaymentHandle payment(AddressBody body) {
        if (body.paymentMethod() == null || blankToNull(body.paymentHandle()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say how you'd like to be paid: PayPal, Venmo or Zelle, and your details.");
        }
        return new PaymentHandle(body.paymentMethod(), body.paymentHandle().strip());
    }

    // Sellers

    @GetMapping("/api/orgs/{orgId}/cards/{cardId}/sale")
    ListingViews.SaleView sale(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        requireSellerCard(orgId, cardId);
        return listings.sale(cardId);
    }

    /** Where the card is on the way to a sale (photos, our review, offer, sold), and what happens next. */
    @GetMapping("/api/orgs/{orgId}/cards/{cardId}/status")
    CardStatusService.CardStatus status(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        requireSellerCard(orgId, cardId);
        return statuses.status(cardId);
    }

    /** {@link #status} for each card in a collection, in the collection's order. */
    @GetMapping("/api/orgs/{orgId}/collections/{collectionId}/status")
    List<CardStatusService.CardStatus> collectionStatus(@PathVariable UUID orgId, @PathVariable UUID collectionId) {
        access.require(orgId, SELLER_ORGS);
        cardItems.requireCollection(orgId, collectionId);
        return cardItems.inCollection(collectionId).stream().map(c -> statuses.status(c.id())).toList();
    }

    /** What's waiting on the seller across the org's collections: labels to ship, offers to take up, photos to retake. */
    @GetMapping("/api/orgs/{orgId}/needs-you")
    List<CardStatusService.NeedsYou> needsYou(@PathVariable UUID orgId) {
        return statuses.needsYou(orgId, access.require(orgId, SELLER_ORGS).getType() == OrgType.COLLECTOR);
    }

    /** Prices the card now. 409 with the reason when it gets no offer. */
    @PostMapping("/api/orgs/{orgId}/cards/{cardId}/offers")
    @ResponseStatus(HttpStatus.CREATED)
    ListingViews.OfferView price(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        requireSellerCard(orgId, cardId);
        consents.requireAccepted(orgId);
        listings.requireNotHeld(cardId);
        return views.offerView(offers.price(orgId, cardId), null);
    }

    /** Posts the offer to every buyer. Not a sale yet: a buyer has to take it. */
    @PostMapping("/api/orgs/{orgId}/cards/{cardId}/listings")
    @ResponseStatus(HttpStatus.CREATED)
    ListingViews.SaleView post(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody PostRequest request) {
        requireSellerCard(orgId, cardId);
        consents.requireAccepted(orgId);
        listings.post(orgId, cardId, request.offerId());
        return listings.sale(cardId);
    }

    @PostMapping("/api/orgs/{orgId}/listings/{listingId}/withdraw")
    void withdraw(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        access.require(orgId, SELLER_ORGS);
        listings.withdraw(orgId, listingId);
    }

    // After the sale, the seller's word for each step. Terms aren't asked for again here: the sale was made under the old ones.

    /** The card is with the carrier. */
    @PostMapping("/api/orgs/{orgId}/listings/{listingId}/shipped")
    void shipped(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        access.require(orgId, SELLER_ORGS);
        deals.shipped(orgId, listingId);
    }

    /** "I got paid." */
    @PostMapping("/api/orgs/{orgId}/listings/{listingId}/payment-confirmed")
    void paymentConfirmed(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        access.require(orgId, SELLER_ORGS);
        deals.paymentConfirmed(orgId, listingId);
    }

    /** "I haven't been paid", or "I disagree with this return": either goes to the admin. */
    @PostMapping("/api/orgs/{orgId}/listings/{listingId}/dispute")
    void dispute(@PathVariable UUID orgId, @PathVariable UUID listingId, @Valid @RequestBody DisputeBody body) {
        access.require(orgId, SELLER_ORGS);
        deals.disputed(orgId, listingId, body.note());
    }

    /** A returned card is back with the seller. */
    @PostMapping("/api/orgs/{orgId}/listings/{listingId}/card-back")
    void cardBack(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        access.require(orgId, SELLER_ORGS);
        deals.cardBack(orgId, listingId);
    }

    // Buyers

    @GetMapping("/api/orgs/{orgId}/market")
    List<ListingViews.MarketItem> market(@PathVariable UUID orgId) {
        requireBuyer(orgId);
        return listings.market();
    }

    /** What the label would cost, shown in the confirm step before accepting (UX review finding 8). Buys nothing. */
    @GetMapping("/api/orgs/{orgId}/market/{listingId}/postage")
    Shipping.Estimate postage(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        requireBuyer(orgId);
        return listings.postage(orgId, listingId);
    }

    /** Takes the card at the offer price, then buys the label. A label failure doesn't undo the take. */
    @PostMapping("/api/orgs/{orgId}/market/{listingId}/accept")
    AcceptResult accept(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        requireBuyer(orgId);
        SaleListing taken = listings.accept(orgId, listingId);
        String pending = null;
        try {
            deals.buyLabel(taken, orgId);
        } catch (ResponseStatusException e) {
            pending = e.getReason();
        } catch (RuntimeException e) {
            // The take has committed, so the buyer owns the card whatever went wrong here: never a 500 (review finding 7).
            log.error("Couldn't record the label for listing {}: {}", listingId, e.getClass().getSimpleName());
            pending = LABEL_PENDING;
        }
        return new AcceptResult(deals.deals(orgId), pending);
    }

    @GetMapping("/api/orgs/{orgId}/deals")
    List<ListingViews.DealView> deals(@PathVariable UUID orgId) {
        requireBuyer(orgId);
        return deals.deals(orgId);
    }

    /** Tries the label again, for a deal whose label didn't go through. */
    @PostMapping("/api/orgs/{orgId}/deals/{listingId}/label")
    List<ListingViews.DealView> label(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        requireBuyer(orgId);
        deals.buyLabel(deals.requireDeal(orgId, listingId), orgId);
        return deals.deals(orgId);
    }

    @PostMapping("/api/orgs/{orgId}/deals/{listingId}/received")
    @ResponseStatus(HttpStatus.CREATED)
    List<ListingViews.DealView> received(@PathVariable UUID orgId, @PathVariable UUID listingId, @Valid @RequestBody ReceivedBody body) {
        requireBuyer(orgId);
        deals.received(orgId, listingId, body.note(), false);
        return deals.deals(orgId);
    }

    @PostMapping("/api/orgs/{orgId}/deals/{listingId}/paid")
    @ResponseStatus(HttpStatus.CREATED)
    List<ListingViews.DealView> paid(@PathVariable UUID orgId, @PathVariable UUID listingId, @Valid @RequestBody PaidBody body) {
        requireBuyer(orgId);
        deals.paid(orgId, listingId, body.pricePaid(), body.note());
        return deals.deals(orgId);
    }

    @PostMapping("/api/orgs/{orgId}/deals/{listingId}/returned")
    @ResponseStatus(HttpStatus.CREATED)
    List<ListingViews.DealView> returned(@PathVariable UUID orgId, @PathVariable UUID listingId, @Valid @RequestBody ReturnedBody body) {
        requireBuyer(orgId);
        deals.returned(orgId, listingId, body.reason(), body.note(), false);
        return deals.deals(orgId);
    }

    private void requireSellerCard(UUID orgId, UUID cardId) {
        access.require(orgId, SELLER_ORGS);
        cardItems.requireAccessible(orgId, cardId);
    }

    private void requireBuyer(UUID orgId) {
        access.require(orgId, OrgType.BUYER);
        consents.requireAccepted(orgId);
    }

    private static AddressView view(ShipAddress a) {
        ShippingProvider.Address x = a.toAddress();
        PaymentHandle payment = a.payment();
        return new AddressView(x.name(), x.company(), x.street1(), x.street2(), x.city(), x.state(), x.zip(), x.country(), x.phone(),
                payment == null ? null : payment.method(), payment == null ? null : payment.handle(), a.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

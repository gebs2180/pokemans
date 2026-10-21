package com.pokemans.app.offers;

import com.pokemans.app.identity.Consents;
import com.pokemans.app.identity.Directory;
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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Instant offers. Sellers (members of a collector or contributor org) see our firm offer on a card and
 * post it to buyers; buyers (members of a buyer org who accepted the buyer terms) take one, get a
 * prepaid label sent to the seller, and record that the card arrived as shown and was paid for, or
 * return it. The admin buys labels that didn't go through and sees what to bill each buyer.
 */
@RestController
class OfferController {

    private static final OrgType[] SELLER_ORGS = {OrgType.COLLECTOR, OrgType.CONTRIBUTOR};
    private static final OrgType[] SHIPPING_ORGS = {OrgType.COLLECTOR, OrgType.CONTRIBUTOR, OrgType.BUYER};

    record PostRequest(@NotNull UUID offerId) {
    }

    /** US addresses only in v1. */
    record AddressBody(@NotBlank @Size(max = 100) String name, @Size(max = 100) String company,
                       @NotBlank @Size(max = 200) String street1, @Size(max = 200) String street2,
                       @NotBlank @Size(max = 100) String city,
                       @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "use the two-letter state code") String state,
                       @NotBlank @Pattern(regexp = "\\d{5}(-\\d{4})?", message = "use a 5 or 9 digit ZIP code") String zip,
                       @Size(max = 30) String phone) {
    }

    record AddressView(String name, String company, String street1, String street2, String city, String state, String zip,
                       String country, String phone, Instant recordedAt) {
    }

    /** @param pricePaid what the buyer paid the seller: the firm price, unless they agreed otherwise between them (then say why in the note) */
    record ReceivedBody(@NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal pricePaid, @Size(max = 2000) String note) {
    }

    record ReturnedBody(@NotNull ListingEvent.DeclinedReason reason, @Size(max = 2000) String note) {
    }

    /** @param labelPending why the label didn't come with the accept, or null; it can be bought again */
    record AcceptResult(List<ListingService.DealView> deals, String labelPending) {
    }

    /** One buyer's labels in a month: what to bill them, off-platform. */
    record BuyerCharges(UUID buyerOrgId, String buyerName, int labels, BigDecimal postage, BigDecimal insurance, BigDecimal total,
                        List<ChargeLine> lines) {
    }

    record ChargeLine(UUID listingId, Instant boughtAt, String carrier, String service, String trackingCode, BigDecimal postage,
                      BigDecimal insurance) {
    }

    private final OfferService offers;
    private final ListingService listings;
    private final Shipping shipping;
    private final OrgAccess access;
    private final CardItems cardItems;
    private final Consents consents;
    private final Directory directory;
    private final CardStatusService statuses;

    OfferController(OfferService offers, ListingService listings, Shipping shipping, OrgAccess access, CardItems cardItems,
                    Consents consents, Directory directory, CardStatusService statuses) {
        this.offers = offers;
        this.listings = listings;
        this.shipping = shipping;
        this.access = access;
        this.cardItems = cardItems;
        this.consents = consents;
        this.directory = directory;
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
        access.require(orgId, SHIPPING_ORGS);
        return view(shipping.record(orgId, new ShippingProvider.Address(body.name().strip(), blankToNull(body.company()),
                body.street1().strip(), blankToNull(body.street2()), body.city().strip(), body.state(), body.zip(), "US",
                blankToNull(body.phone()))));
    }

    // Sellers

    @GetMapping("/api/orgs/{orgId}/cards/{cardId}/sale")
    ListingService.SaleView sale(@PathVariable UUID orgId, @PathVariable UUID cardId) {
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

    /** Prices the card now. 409 with the reason when it gets no offer. */
    @PostMapping("/api/orgs/{orgId}/cards/{cardId}/offers")
    @ResponseStatus(HttpStatus.CREATED)
    ListingService.OfferView price(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        requireSellerCard(orgId, cardId);
        return ListingService.offerView(offers.price(orgId, cardId));
    }

    /** Posts the offer to every buyer. Not a sale yet: a buyer has to take it. */
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

    /** Takes the card at the offer price, then buys the label. A label failure doesn't undo the take. */
    @PostMapping("/api/orgs/{orgId}/market/{listingId}/accept")
    AcceptResult accept(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        requireBuyer(orgId);
        SaleListing taken = listings.accept(orgId, listingId);
        String pending = null;
        try {
            listings.buyLabel(taken, orgId);
        } catch (ResponseStatusException e) {
            pending = e.getReason();
        }
        return new AcceptResult(listings.deals(orgId), pending);
    }

    @GetMapping("/api/orgs/{orgId}/deals")
    List<ListingService.DealView> deals(@PathVariable UUID orgId) {
        requireBuyer(orgId);
        return listings.deals(orgId);
    }

    /** Tries the label again, for a deal whose label didn't go through. */
    @PostMapping("/api/orgs/{orgId}/deals/{listingId}/label")
    List<ListingService.DealView> label(@PathVariable UUID orgId, @PathVariable UUID listingId) {
        requireBuyer(orgId);
        listings.buyLabel(listings.requireDeal(orgId, listingId), orgId);
        return listings.deals(orgId);
    }

    @PostMapping("/api/orgs/{orgId}/deals/{listingId}/received")
    @ResponseStatus(HttpStatus.CREATED)
    List<ListingService.DealView> received(@PathVariable UUID orgId, @PathVariable UUID listingId, @Valid @RequestBody ReceivedBody body) {
        requireBuyer(orgId);
        listings.received(orgId, listingId, body.pricePaid(), body.note(), false);
        return listings.deals(orgId);
    }

    @PostMapping("/api/orgs/{orgId}/deals/{listingId}/returned")
    @ResponseStatus(HttpStatus.CREATED)
    List<ListingService.DealView> returned(@PathVariable UUID orgId, @PathVariable UUID listingId, @Valid @RequestBody ReturnedBody body) {
        requireBuyer(orgId);
        listings.returned(orgId, listingId, body.reason(), body.note(), false);
        return listings.deals(orgId);
    }

    // Admin (by path)

    /** Deals still waiting for their label, oldest first: retry the label, or record the arrival by hand. */
    @GetMapping("/api/admin/deals/awaiting-label")
    List<ListingService.AwaitingLabel> awaitingLabels() {
        return listings.awaitingLabels();
    }

    /** What an offer on the card would be now, or why it gets none, with the steps that set its confidence. Stores nothing. */
    @GetMapping("/api/admin/cards/{cardId}/offer-preview")
    OfferService.Preview offerPreview(@PathVariable UUID cardId) {
        cardItems.get(cardId);
        return offers.preview(cardId);
    }

    /** Records an arrival for the buyer when no label was bought (the card shipped another way). The note says how. */
    @PostMapping("/api/admin/deals/{listingId}/received")
    @ResponseStatus(HttpStatus.CREATED)
    void adminReceived(@PathVariable UUID listingId, @RequestParam UUID buyerOrgId, @Valid @RequestBody ReceivedBody body) {
        listings.received(buyerOrgId, listingId, body.pricePaid(), body.note(), true);
    }

    /** Records a return for the buyer when no label was bought. The note says how the card shipped. */
    @PostMapping("/api/admin/deals/{listingId}/returned")
    @ResponseStatus(HttpStatus.CREATED)
    void adminReturned(@PathVariable UUID listingId, @RequestParam UUID buyerOrgId, @Valid @RequestBody ReturnedBody body) {
        listings.returned(buyerOrgId, listingId, body.reason(), body.note(), true);
    }

    /** Buys the label for any deal, e.g. once a shipping account is set up. */
    @PostMapping("/api/admin/deals/{listingId}/label")
    void adminLabel(@PathVariable UUID listingId, @RequestParam UUID buyerOrgId) {
        listings.buyLabel(listings.requireDeal(buyerOrgId, listingId), buyerOrgId);
    }

    /** Labels bought in a month, per buyer: what to bill each one. {@code month} is yyyy-MM, UTC; default this month. */
    @GetMapping("/api/admin/shipping/charges")
    List<BuyerCharges> charges(@RequestParam(required = false) String month) {
        YearMonth m;
        try {
            m = month == null ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month is yyyy-MM");
        }
        Map<UUID, List<ShippingLabel>> byBuyer = new LinkedHashMap<>();
        for (ShippingLabel l : shipping.labelsIn(m)) {
            byBuyer.computeIfAbsent(l.getBuyerOrgId(), k -> new ArrayList<>()).add(l);
        }
        List<BuyerCharges> charges = new ArrayList<>();
        byBuyer.forEach((buyer, ls) -> {
            BigDecimal postage = ls.stream().map(ShippingLabel::getPostage).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal insurance = ls.stream().map(ShippingLabel::getInsuranceCost).reduce(BigDecimal.ZERO, BigDecimal::add);
            charges.add(new BuyerCharges(buyer, directory.orgName(buyer), ls.size(), postage, insurance,
                    postage.add(insurance), ls.stream().map(l -> new ChargeLine(l.getListingId(), l.getCreatedAt(), l.getCarrier(),
                    l.getService(), l.getTrackingCode(), l.getPostage(), l.getInsuranceCost())).toList()));
        });
        return charges;
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
        return new AddressView(x.name(), x.company(), x.street1(), x.street2(), x.city(), x.state(), x.zip(), x.country(), x.phone(),
                a.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

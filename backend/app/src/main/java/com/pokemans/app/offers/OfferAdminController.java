package com.pokemans.app.offers;

import com.pokemans.app.intake.CardItems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
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
 * Admin: offers, deals and what to bill buyers, every /api/admin route of the offers module (review
 * finding 30). Every undo is a new row with who, when and why. /api/admin/** needs the admin role after
 * an MFA login (SecurityConfig).
 */
@RestController
class OfferAdminController {

    /** @param reason why, kept with the row; the seller sees a voided offer's reason, the buyer a cancelled deal's */
    record ReasonBody(@NotBlank @Size(max = 2000) String reason) {
    }

    private final OfferAdminService admin;
    private final OfferService offers;
    private final DealService deals;
    private final ShippingBilling billing;
    private final CardItems cardItems;

    OfferAdminController(OfferAdminService admin, OfferService offers, DealService deals, ShippingBilling billing, CardItems cardItems) {
        this.admin = admin;
        this.offers = offers;
        this.deals = deals;
        this.billing = billing;
        this.cardItems = cardItems;
    }

    // Offers

    @GetMapping("/api/admin/offers")
    List<OfferAdminService.Offer> recent() {
        return admin.recent();
    }

    /** What an offer on the card would be now, or why it gets none, with the steps that set its confidence. Stores nothing. */
    @GetMapping("/api/admin/cards/{cardId}/offer-preview")
    OfferService.Preview offerPreview(@PathVariable UUID cardId) {
        cardItems.get(cardId);
        return offers.preview(cardId);
    }

    /** Voids an offer, and takes it off the market if it's posted. 409 once a buyer has taken it. */
    @PostMapping("/api/admin/offers/{offerId}/void")
    OfferAdminService.Offer voidOffer(@PathVariable UUID offerId, @Valid @RequestBody ReasonBody body) {
        return admin.voidOffer(offerId, body.reason().strip());
    }

    // Deals

    /** Deals waiting for the admin: sellers who say they weren't paid, and returns to review. */
    @GetMapping("/api/admin/deals/attention")
    List<OfferAdminService.Attention> attention() {
        return admin.attention();
    }

    /** Closes what's waiting on a deal. Both sides see the reason, and it lifts the hold on a "not genuine" return. */
    @PostMapping("/api/admin/deals/{listingId}/resolve")
    List<OfferAdminService.Attention> resolve(@PathVariable UUID listingId, @Valid @RequestBody ReasonBody body) {
        admin.resolve(listingId, body.reason().strip());
        return admin.attention();
    }

    /** Deals still waiting for their label, oldest first: retry the label, or record the arrival by hand. */
    @GetMapping("/api/admin/deals/awaiting-label")
    List<ListingViews.AwaitingLabel> awaitingLabels() {
        return deals.awaitingLabels();
    }

    /** Overdue labels, for the count on the admin page. */
    @GetMapping("/api/admin/deals/overdue")
    ListingViews.Overdue overdue() {
        return deals.overdue();
    }

    /** Buys the label for any deal, e.g. once a shipping account is set up. */
    @PostMapping("/api/admin/deals/{listingId}/label")
    void label(@PathVariable UUID listingId, @RequestParam UUID buyerOrgId) {
        deals.buyLabel(deals.requireDeal(buyerOrgId, listingId), buyerOrgId);
    }

    /** Records an arrival for the buyer when no label was bought (the card shipped another way). The note says how. */
    @PostMapping("/api/admin/deals/{listingId}/received")
    @ResponseStatus(HttpStatus.CREATED)
    void received(@PathVariable UUID listingId, @RequestParam UUID buyerOrgId, @Valid @RequestBody OfferController.ReceivedBody body) {
        deals.received(buyerOrgId, listingId, body.note(), true);
    }

    /** Records a return for the buyer when no label was bought. The note says how the card shipped. */
    @PostMapping("/api/admin/deals/{listingId}/returned")
    @ResponseStatus(HttpStatus.CREATED)
    void returned(@PathVariable UUID listingId, @RequestParam UUID buyerOrgId, @Valid @RequestBody OfferController.ReturnedBody body) {
        deals.returned(buyerOrgId, listingId, body.reason(), body.note(), true);
    }

    /** Cancels a deal before its arrival or return is recorded; the card is free for a new offer. */
    @PostMapping("/api/admin/deals/{listingId}/cancel")
    OfferAdminService.Offer cancelDeal(@PathVariable UUID listingId, @Valid @RequestBody ReasonBody body) {
        return admin.cancelDeal(listingId, body.reason().strip());
    }

    /** Marks a cancelled deal's label to be refunded and asks its provider. */
    @PostMapping("/api/admin/deals/{listingId}/label/refund")
    OfferAdminService.Offer refundLabel(@PathVariable UUID listingId, @Valid @RequestBody ReasonBody body) {
        return admin.refundLabel(listingId, body.reason().strip());
    }

    // Billing

    /** Labels bought in a month, per buyer: what to bill each one. {@code month} is yyyy-MM, UTC; default this month. */
    @GetMapping("/api/admin/shipping/charges")
    List<ShippingBilling.BuyerCharges> charges(@RequestParam(required = false) String month) {
        YearMonth m;
        try {
            m = month == null ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month is yyyy-MM");
        }
        return billing.charges(m);
    }
}

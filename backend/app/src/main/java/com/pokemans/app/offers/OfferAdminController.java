package com.pokemans.app.offers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin: the newest offers and the undo for them (Zach, 2026-10-21). Every undo is a new row with who,
 * when and why. /api/admin/** needs the admin role after an MFA login (SecurityConfig).
 */
@RestController
class OfferAdminController {

    /** @param reason why, kept with the row; the seller sees a voided offer's reason, the buyer a cancelled deal's */
    record ReasonBody(@NotBlank @Size(max = 2000) String reason) {
    }

    private final OfferAdminService admin;

    OfferAdminController(OfferAdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/api/admin/offers")
    List<OfferAdminService.Offer> recent() {
        return admin.recent();
    }

    /** Voids an offer, and takes it off the market if it's posted. 409 once a buyer has taken it. */
    @PostMapping("/api/admin/offers/{offerId}/void")
    OfferAdminService.Offer voidOffer(@PathVariable UUID offerId, @Valid @RequestBody ReasonBody body) {
        return admin.voidOffer(offerId, body.reason().strip());
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
}

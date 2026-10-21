# Instant offers

Status 2026-10-19 · firm prices on branch `claude/firm-offers`. Built on fixture and hand-entered data; no buyer has used it yet.

The lender and insurer side is paused while Zach clears his outside interest with his employer (see [m4-progress.md](m4-progress.md)). Instant offers reuse the same valuation engine for a different customer: collectors selling raw cards, and shops and dealers buying them.

**The pitch (Zach, 2026-10-19).** Posting a card is easy, the offer is instant and based on its expected grade, and when a buyer accepts the seller instantly gets a shipping label. Sellers skip pricing and the time an eBay listing takes; buyers get raw cards that may grade a 10 for well under what a 10 costs. There is no back-and-forth between buyer and seller, which is what keeps this from being an eBay competitor.

**Competitors (researched 2026-10-17).** Card Buyers, TCG Suppliers, poko.cards and SellCCG already do photo-to-offer-to-label for raw cards. But each is a single shop that re-grades the card on arrival and re-offers. A firm price that holds on arrival, priced from a grade distribution, across a network of buyers, is the difference.

## How it works

1. **The offer.** A member of a collector or contributor org opens a card and asks for an offer. The card is valued as of now:
   - its current condition distribution;
   - its PSA price table, net of the default grading cost;
   - its provenance.

   The offer is **one firm price**. "How we priced it" shows the per-grade ladder behind it.
2. **Selling.** "Sell" posts the price to every buyer org, after one confirm step. The seller needs a ship-from address on file first. Posting isn't a sale: the first buyer to accept buys the card at the price, and the page says so. A listing nobody accepts expires.
   - Buyers see the photos the offer was priced on: each side's current photo (front, back, close-up), labelled, never superseded attempts. An offer whose photos were retaken since can't be posted: the seller gets a new one.
   - From posting on, the card's photos can't change (E2E audit, 2026-10-21): the API refuses uploads while it's posted, and for good once it's sold. Withdrawing frees them again.
3. **The label.** Accepting buys a prepaid, insured label at once, on our EasyPost account, from the seller's address to the buyer's. The seller prints it from the card page. If the purchase fails (no shipping account yet, or the carrier is down), the sale still stands: the buyer retries from Your deals, or the admin from **Labels to buy** (`/admin/labels`). Meanwhile the seller is told not to ship until the label shows.
4. **On arrival.** Once a label exists, the buyer records one of two things:
   - the card arrived genuine and as shown, and what they paid the seller, by default the offer price; any other amount needs a note saying why;
   - it goes back, for one of the reasons the buyer terms allow: not genuine, not the listed card, or doesn't match its photos. No reason is preselected.

   Without a label nothing can have shipped, so the API refuses both. If a card shipped another way, the admin records it for the buyer from **Labels to buy**, with a note saying how; the note is kept as "Recorded by the admin: …".

   No grade is judged: condition within the photos is the buyer's risk.
5. **Billing.** Postage and insurance are billed to buyers monthly, off-platform, from `/admin/shipping`. Payment for the card is between buyer and seller, due within 7 days of a card arriving as shown. We hold no cards and no money for cards (Zach doesn't rule out becoming the middleman later).

## Decisions (Zach, 2026-10-19)

- **One firm price, not a per-grade ladder settled on inspection.** A ladder needs someone to judge the grade on arrival, which brings back the back-and-forth.
- **Price:** `price = Σ p_g · offer_g`, where `offer_g = P↓_g · (1 − f) · (1 − d) · (1 − m)`.
  - P↓ is the grade's net price (net of grading cost), capped at the grade above, so a PSA 1 premium never pays more for a worse card.
  - f = 15% fees; d = the speed discount for the card's liquidity (5/10/20%); m = the buyer margin, by the card's confidence: 10% at A, 13% at B, 17% at C (Zach, 2026-10-21, parameter set 1.2.0). Before that, B and C cost a seller nothing until they tipped into D; a less certain condition is a bigger risk for the buyer under a firm price, so it now leaves the buyer more room. "How we priced it" says which margin applied and why.
  - The confidence an offer is priced at is the card's own, without the penalties that only bear on FLV (Zach, 2026-10-21, after the E2E audit's finding 1). The offer uses the central distribution and net prices, never σ or the stressed distribution. So a fallback σ and derived prices that only the stress reaches don't lower it. The photo and comps base, a stale price table, and a derived price where the card is likely still do. FLV and valuation runs keep every penalty. The trace records it as `offer_confidence`, and the admin's offer preview shows it.
  - Grades without their own sales are still priced by extrapolation for offers, as the methodology does (Zach, 2026-10-21, after finding 6, which showed a Base Charizard PSA 5 at about $3).
  - The buyer keeps the upside of a 10 and carries the downside.
  - For a verified graded card the ladder is one rung, at gross prices.
- **Validity:** an offer stands for 7 days, and a posted listing for 14 (`pokemans.offers.*`).
- **Payment:** the buyer pays the seller off-platform within 7 days of a card arriving as shown.
- **Labels:** bought on our EasyPost account, insured for the offer price, and billed to buyers monthly (`pokemans.shipping.*`, off until `EASYPOST_ENABLED` and `EASYPOST_API_KEY` are set). The parcel is a bubble mailer with a toploader: 7×5×1 in, 3 oz.
- **Mismatches:** the buyer returns the card and records why. There is no re-offer.
- **No offer for:**
  - lots, unidentified or unassessed cards;
  - cards without a PSA table;
  - cards whose authenticity is doubted;
  - confidence D;
  - FMV under $25;
  - a firm price above the maximum, $500 by default (`pokemans.offers.max-price`; Zach, 2026-10-21). It caps what one mistake can cost a buyer during the beta, and keeps every label under EasyPost's $5,000 insurance limit. The seller is told instant offers go up to $500 for now; no offer row is stored.

  Unverified high-risk cards (vintage holos, Japanese promos) get an offer, marked "subject to the buyer authenticating the card in hand".

## Undo (Zach, 2026-10-21)

Before the closed beta, the admin can take back a mistake (a wrong identification, a bad price, a deal that fell through). Nothing is edited: each undo is a new row with who, when and why, so the record shows the mistake and its correction.

- **Void an offer.** The offer can't be posted again. If it's on the market, its listing comes down with it (a `VOIDED` listing event) and buyers can no longer take it. The seller sees "We withdrew this offer" with the reason, and gets a new one. An offer a buyer has already taken is undone by cancelling the deal instead.
- **Cancel a deal.** Only before the buyer records an arrival or a return; the database lets a deal end once, so a cancel and an arrival can't both land. The deal's offer is voided too, so the card is free for a new offer at today's price. The buyer sees the deal cancelled with the reason and can record nothing more on it or retry its label; the seller is told not to ship. A cancelled deal's label isn't billed to the buyer: we undid the deal, so its postage is ours.
- **Refund a label.** Only for a cancelled deal: a live deal's label is the one the seller ships with. The refund is asked of the provider that sold the label (EasyPost: `POST /shipments/{id}/refund`), and its answer is kept (`submitted`, `refunded`, `rejected`, `not_applicable`). If the provider couldn't be asked (none set up, or it failed), the request is still recorded with why, and the admin can ask again; once answered, a label isn't asked about again. EasyPost refunds unused USPS labels within its refund window; a used or late one is rejected.
- **Track record.** A voided listing and a cancelled deal get statuses of their own in `v_offer_outcomes` (`VOIDED`, `CANCELLED`) and are left out of the acceptance and delivery numbers, which count them apart.

## Built

- **valuation-core:** `OfferLadder` returns the firm price and the ladder behind it.
- **Valuation:** `Valuations.current(cardItemId)` values one card as of now with the run engine, so offers and valuation runs can't drift apart.
- **identity:**
  - a `BUYER` org type and role;
  - buyer terms, versioned in `Consents` (`buyer-firm-2026-10-19`). The text is a plain-language **draft for Zach**, not reviewed legal wording;
  - `Directory` for names and contact details.
- **`offers` module (V14, V16, V18; all append-only):**
  - `card_offer`: every input, the firm price, the ladder and the trace.
  - `sale_listing`.
  - `listing_event`: ACCEPTED, WITHDRAWN, RECEIVED and RETURNED, plus INSPECTED from the per-grade offers that came before, and the admin's VOIDED and CANCELLED (V18, no actor org, the reason in the note). Partial unique indexes let a listing close only once (taken, withdrawn or voided) and a deal end once (arrived, returned or cancelled). Posting, voiding and cancelling are serialized per card with an advisory lock.
  - `card_offer_void` (V18): one per voided offer, with the reason and the admin.
  - `label_refund` (V18): each refund request, with the reason, the admin and the provider's answer or why it couldn't be asked.
  - `ship_address`: one per org, the latest row current.
  - `shipping_label`.
  - The `ShippingProvider` port, with an EasyPost adapter that buys the cheapest USPS rate, insured for the offer, and asks for a label's refund.
- **API:**
  - Seller:
    - `GET /api/orgs/{org}/cards/{card}/sale`
    - `POST .../offers`
    - `POST .../listings`
    - `POST /api/orgs/{org}/listings/{id}/withdraw`
    - `GET /api/orgs/{org}/cards/{card}/status` and `GET /api/orgs/{org}/collections/{collection}/status`: where each card is (photos, our identification, condition review, prices, offer, posted, sold), its identity once confirmed, and what happens next
  - Both sides: `GET`/`POST /api/orgs/{org}/ship-address`.
  - Buyer (buyer org, terms accepted):
    - `GET /api/orgs/{org}/market`
    - `POST .../market/{id}/accept`, which returns the deals and `labelPending` if the label didn't go through
    - `GET .../deals`
    - `POST .../deals/{id}/label` (retry)
    - `POST .../deals/{id}/received`
    - `POST .../deals/{id}/returned`
  - Admin (admin role after an MFA login):
    - `GET /api/admin/cards/{card}/offer-preview`: the offer the seller would get now, or why none, with the trace steps that set its confidence. Stores nothing.
    - `GET /api/admin/deals/awaiting-label`
    - `POST /api/admin/deals/{id}/label?buyerOrgId=`
    - `POST /api/admin/deals/{id}/received?buyerOrgId=` and `.../returned?buyerOrgId=`: an arrival or return recorded by hand, note required
    - `GET /api/admin/shipping/charges?month=yyyy-MM`: cancelled deals' labels are listed with `billed: false` and their refund state
    - `GET /api/admin/offers`: the newest 200 offers with their latest listing, label and any undo
    - `POST /api/admin/offers/{id}/void`, `POST /api/admin/deals/{id}/cancel`, `POST /api/admin/deals/{id}/label/refund`, each with `{"reason": "..."}`
  - The market never names the seller. Contact details and the label appear only to the two parties once a buyer accepts.
- **Web:**
  - the "Instant offer" panel on the card page: the price, how it was priced, the ship-from address, "Sell for $X", and the label to print;
  - the buyer org page, behind the buyer terms, with its ship-to address;
  - "Cards for sale", with a two-step "Buy";
  - "Your deals": the label and tracking, retrying the label, and recording the arrival or return;
  - admin "Postage to bill";
  - admin "Offers and deals" (`/admin/deals`): void, cancel and refund, each asking for a reason, and the voided, cancelled and refund states with who and when. The seller's offer panel and the buyer's deals show a voided offer or a cancelled deal with its reason.
- **Tests:**
  - `OffersFlowTest`: the race, the label bought at once and insured at the price, a label failure that doesn't undo the sale and a retry, a return, withdrawal, every refusal including the maximum price, voiding, cancelling and refunding (with the fake provider failing and recovering), the track record, and append-only rows.
  - `EasyPostShippingProviderTest`, against EasyPost's published response shapes.
  - `CrossOrgAccessTest` covers every route.
  - Vitest covers the offer panel (including voided and cancelled), market, deals, arrival form, track record, the admin offers page and `likelyRungs`.

## Track record

The realized sales are the evidence for the later lender and insurer pitch: our estimates, backed by what buyers actually bought and paid.

- **`v_offer_outcomes` (V16)** has one row per listing:
  - the card's identity, the offer date, the price and FMV;
  - the status (open, accepted, withdrawn, expired) and time to accept;
  - the label (carrier, postage, insurance);
  - the outcome (received with the price paid, or returned with the reason) and the time from label to outcome.

  V16 drops the grade-calibration view: with firm prices, nobody judges a grade on arrival.
- **Admin page** "Offer track record" (`/admin/offers`) shows:
  - acceptance rate and median time to accept, overall and by price band;
  - deliveries: return rate by reason and median label-to-arrival time;
  - price paid against the offer;
  - postage per card;
  - the counts behind every rate (flagged "too few to read" under 30), and the caveats.
- **Counting rules, chosen to understate:**
  - The acceptance rate is accepted out of accepted + withdrawn + expired; open listings wait.
  - A card posted twice counts twice.
  - Deals taken under the earlier per-grade offers are counted apart.
- **Caveats, shown on the page:**
  - Sellers only post offers they like.
  - Buyers record arrivals themselves, and those who never record one drop out.
  - Grade accuracy has to come from graders' results for sold cards, which aren't collected yet.

## Open

- **Buyer terms wording:** review before inviting the first buyer.
- **EasyPost account:** create one, test with a test key (labels are fake), then set the production key. EasyPost insures up to $5,000 a parcel; the $500 offer maximum keeps well under it, but its terms need a look before raising the maximum.
- **Undo, open (2026-10-21):** the seller sees a voided offer's reason as the admin typed it, so write it for them; a cancelled deal after the card shipped needs the card back by hand; a refund's later status (EasyPost moves `submitted` to `refunded` or `rejected`) isn't fetched yet.
- **Invoices:** monthly postage bills are produced by hand from "Postage to bill". There is no invoicing or payment collection.
- **Grade accuracy:** add a way for buyers to report a grader's result when they slab a card they bought, as calibration data from a grader.
- **Notifications:** buyers aren't told when a card is posted, and sellers aren't emailed their label. Email waits for the notifier (M6).
- **"Instant" depends on the review queue.** An offer needs an identified, assessed card, and identification and condition review are still done by hand.
- **Outside interest:** a marketplace for shops and collectors isn't "primarily to the financial services industry", but the employer's general outside-interest disclosure may still apply. Raise it in the lawyer hour.
- **Not checked in the running app:** logging in needs Zach's Auth0 MFA.

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
   - From posting on, the card's photos can't change (E2E audit, 2026-10-21): the API refuses uploads while it's posted, and once it's sold until a returned card is back with the seller. Withdrawing frees them again.
3. **The label.** Accepting buys a prepaid, insured label at once, on our EasyPost account, from the seller's address to the buyer's. The seller prints it from the card page. If the purchase fails (no shipping account yet, or the carrier is down), the sale still stands: the buyer retries from Your deals, or the admin from **Labels to buy** (`/admin/labels`). Meanwhile the seller is told not to ship until the label shows.
4. **After the sale** (UX review, 2026-11-01). **Rawmark never handles the money:** everything here records what each side says, one appended row at a time. The seller's card page and the buyer's Your deals both follow the deal through these stages:
   - **Sold.** The seller gets the label and how to ship. The buyer gets the seller's payment handle (see Payment below).
   - **Shipped.** The seller says "I've shipped it", after a confirm. The label is then gone from the screen and the API, since it's used; tracking stays. We don't read tracking yet, so this is the seller's word, and a stray tap needs the admin to pass the label back.
   - **Arrived.** Once a label exists, the buyer taps **It arrived** and records one of two things. Neither is preselected, and **Record** asks once more, because a record can't be undone and the seller sees it:
     - the card arrived genuine and as shown. That starts the 7 days, so both sides now see a pay-by date;
     - it goes back, for one of the reasons the buyer terms allow: not genuine, not the listed card, or doesn't match its photos. No reason is preselected. "Not genuine" also needs a note, and its confirm says plainly what it means for the seller.
   - **Paid.** A separate step from arriving, since the buyer has 7 days. The buyer records what they paid, by default the offer price; any other amount needs a note saying why. The seller then says **I got paid**, which closes the deal, or **I haven't been paid**.
   - **Returned.** The seller sees the reason, the buyer's note and what happens next. **The card's back with me** frees the card: its photos can change again and it can get a new offer, never the one it sold at. **I disagree** sends Zach the seller's reason, which the buyer doesn't see.

   The seller can say either payment answer from Shipped on, so a buyer who never records the arrival can't leave them stuck. A card returned as **not genuine** gets no new offer until the admin closes the return, so a fake can't go straight out to the next buyer.

   **What reaches the admin.** "Needs you" on `/admin/deals`, counted on `/admin`, lists every seller who says they weren't paid, every return, and every seller who disagrees with one, with both sides' contact details. The admin acts off the app, then closes the item with a note both sides see. A seller's own "I got paid" closes a payment item too.

   Without a label nothing can have shipped, so the API refuses both. If a card shipped another way, the admin records it for the buyer from **Labels to buy**, with a note saying how; the note is kept as "Recorded by the admin: …".

   No grade is judged: condition within the photos is the buyer's risk.
5. **Billing.** Postage and insurance are billed to buyers monthly, off-platform, from `/admin/shipping`. Payment for the card is between buyer and seller, due within 7 days of a card arriving as shown. We hold no cards and no money for cards (Zach doesn't rule out becoming the middleman later).
6. **Payment handle.** A seller gives PayPal, Venmo or Zelle details on the address form, and can't post without them; a seller whose address predates them is asked with their address prefilled. Buyers never see the handle on the market, only on a deal they took, and not once a deal is cancelled or returned. It goes to nobody else, the shipping provider included. We don't check it.

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
  - a firm price above the maximum, $5,000 by default (`pokemans.offers.max-price`): EasyPost's insurance limit, so every label is insured for the full price. The seller is told instant offers go up to $5,000, the most a label can insure; no offer row is stored. It was $500 from 2026-10-21 to cap what one mistake could cost a buyer during the beta, and Zach lifted it on 2026-10-26 because too many cards were above it. Set it lower to cap the beta again.

  Unverified high-risk cards (vintage holos, Japanese promos) get an offer, marked "subject to the buyer authenticating the card in hand".

## Undo (Zach, 2026-10-21)

Before the closed beta, the admin can take back a mistake (a wrong identification, a bad price, a deal that fell through). Nothing is edited: each undo is a new row with who, when and why, so the record shows the mistake and its correction.

- **Void an offer.** The offer can't be posted again. If it's on the market, its listing comes down with it (a `VOIDED` listing event) and buyers can no longer take it. The seller sees "We withdrew this offer" with the reason, and gets a new one. An offer a buyer has already taken is undone by cancelling the deal instead.
- **A doubt voids by itself (Zach, 2026-11-02).** Recording counterfeit or suspected counterfeit voids the card's live offers and takes any listing down. A card a buyer already took stays a deal for the admin to cancel or not. A later "verified" needs a new offer.
- **Cancel a deal.** Only before the buyer records an arrival or a return; the database lets a deal end once, so a cancel and an arrival can't both land. The deal's offer is voided too, so the card is free for a new offer at today's price. The buyer sees the deal cancelled with the reason and can record nothing more on it or retry its label; the seller is told not to ship. A cancelled deal's label isn't billed to the buyer: we undid the deal, so its postage is ours.
- **Refund a label.** Only for a cancelled deal: a live deal's label is the one the seller ships with. The refund is asked of the provider that sold the label (EasyPost: `POST /shipments/{id}/refund`), and its answer is kept (`submitted`, `refunded`, `rejected`, `not_applicable`). If the provider couldn't be asked (none set up, or it failed), the request is still recorded with why, and the admin can ask again; once answered, a label isn't asked about again. EasyPost refunds unused USPS labels within its refund window; a used or late one is rejected.
- **Track record.** A voided listing and a cancelled deal get statuses of their own in `v_offer_outcomes` (`VOIDED`, `CANCELLED`) and are left out of the acceptance and delivery numbers, which count them apart.

## Built

- **valuation-core:** `OfferLadder` returns the firm price and the ladder behind it.
- **Valuation:** `Valuations.current(cardItemId)` values one card as of now with the run engine, so offers and valuation runs can't drift apart.
- **identity:**
  - a `BUYER` org type and role;
  - terms, versioned in `Consents`; each is a plain-language **draft for Zach**, not reviewed legal wording, and a new version must be accepted again:
    - buyer terms (`buyer-firm-2026-11-01`), accepted before a buyer sees the market;
    - seller terms (`seller-2026-11-01`), accepted by collectors before they upload photos, get an offer or post a card. Contributors accept the same seller terms inside their own (`contributor-2026-11-01`), since they can sell too;
    - the 2026-11-01 versions add the payment handle and who sees it, recording arrival, payment and shipping in the app, the note a "not genuine" return needs, and the review before such a card gets a new offer. The 2026-10-26 versions dropped the $500 maximum;
    - the 2026-10-21 versions cover shipping within 3 days of the label, transit loss (we claim on the label's insurance and pay the seller; the buyer owes nothing), cards subject to authentication, disputes (Zach decides from the photos), the admin's undo, no dealing around the app during the test, and non-payment ending a buyer's part in the test. Zach agreed each of these on 2026-10-21, along with return postage staying with the buyer and contributors keeping their free collection report. No liability cap or "as is" wording until a lawyer drafts it;
  - `Directory` for names and contact details.
- **`offers` module (V14, V16, V18; all append-only):**
  - `card_offer`: every input, the firm price, the ladder and the trace.
  - `sale_listing`.
  - `listing_event`: ACCEPTED, WITHDRAWN, RECEIVED and RETURNED, plus INSPECTED from the per-grade offers that came before, and the admin's VOIDED and CANCELLED (V18, no actor org, the reason in the note). V20 adds the seller's SHIPPED, PAYMENT_CONFIRMED, DISPUTED and CARD_BACK, the buyer's PAID, and the admin's RESOLVED. A RECEIVED with a price is from before V20, when arriving and paying were one row, and counts as both. Partial unique indexes let a listing close only once (taken, withdrawn or voided), a deal end once (arrived, returned or cancelled), and each of SHIPPED, PAID, PAYMENT_CONFIRMED and CARD_BACK happen once. DISPUTED and RESOLVED can repeat. Posting, voiding, cancelling and what's said after the sale are serialized per card with an advisory lock.
  - `card_offer_void` (V18): one per voided offer, with the reason and the admin.
  - `label_refund` (V18): each refund request, with the reason, the admin and the provider's answer or why it couldn't be asked.
  - `ship_address`: one per org, the latest row current; a seller's holds their payment handle too (V20).
  - `shipping_label`.
  - The `ShippingProvider` port, with an EasyPost adapter that buys the cheapest USPS rate, insured for the offer, and asks for a label's refund.
- **API:**
  - Seller:
    - `GET /api/orgs/{org}/cards/{card}/sale`
    - `POST .../offers`
    - `POST .../listings`
    - `POST /api/orgs/{org}/listings/{id}/withdraw`
    - `POST /api/orgs/{org}/listings/{id}/shipped`, `.../payment-confirmed`, `.../dispute` and `.../card-back`
    - `GET /api/orgs/{org}/cards/{card}/status` and `GET /api/orgs/{org}/collections/{collection}/status`: where each card is (photos, our identification, condition review, prices, offer, posted, sold), its identity once confirmed, and what happens next
  - Both sides: `GET`/`POST /api/orgs/{org}/ship-address`.
  - Buyer (buyer org, terms accepted):
    - `GET /api/orgs/{org}/market`
    - `POST .../market/{id}/accept`, which returns the deals and `labelPending` if the label didn't go through
    - `GET .../deals`
    - `POST .../deals/{id}/label` (retry)
    - `POST .../deals/{id}/received`
    - `POST .../deals/{id}/paid`
    - `POST .../deals/{id}/returned`
  - Admin (admin role after an MFA login):
    - `GET /api/admin/cards/{card}/offer-preview`: the offer the seller would get now, or why none, with the trace steps that set its confidence. Stores nothing.
    - `GET /api/admin/deals/awaiting-label`
    - `POST /api/admin/deals/{id}/label?buyerOrgId=`
    - `POST /api/admin/deals/{id}/received?buyerOrgId=` and `.../returned?buyerOrgId=`: an arrival or return recorded by hand, note required
    - `GET /api/admin/shipping/charges?month=yyyy-MM`: cancelled deals' labels are listed with `billed: false` and their refund state
    - `GET /api/admin/offers`: the newest 200 offers with their latest listing, label and any undo
    - `GET /api/admin/deals/attention` and `POST /api/admin/deals/{id}/resolve` with `{"reason": "..."}`: what's waiting on the admin after a sale
    - `POST /api/admin/offers/{id}/void`, `POST /api/admin/deals/{id}/cancel`, `POST /api/admin/deals/{id}/label/refund`, each with `{"reason": "..."}`
  - The market never names the seller. Once a buyer accepts, each side sees the other's org name and email, and only the seller gets the printable label. The buyer never sees the seller's address or city, not even through the label, so the two can't meet up and deal around us (Zach, 2026-10-21). The parcel's return address still shows the seller's address on arrival: kept for the beta (Zach, 2026-10-21).
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
  - the outcome (received, or returned with the reason), the price paid, and the time from label to outcome;
  - from V20, when it shipped, when the buyer says they paid, when the seller confirmed it, and whether the seller raised anything.

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
- **EasyPost account:** create one, test with a test key (labels are fake), then set the production key. EasyPost insures up to $5,000 a parcel, and the offer maximum is set to that limit. Read its insurance terms (what's excluded, how claims are paid) before the first expensive card ships.
- **After the sale, open (2026-11-01):** Shipped is the seller's word, since we don't read tracking yet. Nobody is told when a pay-by date passes: the seller has to look. "Arrived as shown" is final in the app, so a buyer who taps it and then finds a problem needs the admin.
- **Undo, open (2026-10-21):** the seller sees a voided offer's reason as the admin typed it, so write it for them; a cancelled deal after the card shipped needs the card back by hand; a refund's later status (EasyPost moves `submitted` to `refunded` or `rejected`) isn't fetched yet.
- **Invoices:** monthly postage bills are produced by hand from "Postage to bill". There is no invoicing or payment collection.
- **Grade accuracy:** add a way for buyers to report a grader's result when they slab a card they bought, as calibration data from a grader.
- **Notifications:** buyers aren't told when a card is posted, and sellers aren't emailed their label. Email waits for the notifier (M6).
- **"Instant" depends on the review queue.** An offer needs an identified, assessed card, and identification and condition review are still done by hand.
- **Outside interest:** a marketplace for shops and collectors isn't "primarily to the financial services industry", but the employer's general outside-interest disclosure may still apply. Raise it in the lawyer hour.
- **Not checked in the running app:** logging in needs Zach's Auth0 MFA.

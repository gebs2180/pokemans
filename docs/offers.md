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

   The offer is **one firm price**. "How we priced it" shows the per-grade ladder behind it, with what each likely grade recently sold for, e.g. "$300 (3 sales, 90 days)" (UX review finding 11, 2026-11-02). That's the PSA price the offer used: the recency-weighted median, its sale count and window, read from the comps stored with the offer, never a fresh query. `pokemans.offers.show-sold-prices` (`SHOW_SOLD_PRICES`, default on) hides the column if the price data's licence turns out not to allow showing sold prices.
   - The confidence is worded for people beside its letter, with why it isn't an A, from the offer's stored trace: "Fairly sure (B), because there are only a few recent sales." The reasons are the photos, few sales, sales not refreshed lately, and a likely grade with no sales of its own.
2. **Selling.** "Sell" posts the price to every buyer org, after one confirm step. The seller needs a ship-from address on file first. Posting isn't a sale: the first buyer to accept buys the card at the price, and the page says so. A listing nobody accepts expires.
   - Buyers see the photos the offer was priced on: each side's current photo (front, back, close-up), labelled, never superseded attempts. An offer whose photos were retaken since can't be posted: the seller gets a new one.
   - Tapping a photo opens all of them full screen in the app, to swipe between and see at actual size (UX review finding 8, 2026-11-02). These are the photos as taken: a copy of the upload's own image data, byte for byte, with its metadata (location, camera, comments) cut out and only the EXIF orientation kept. No crop, no re-encode. The copy is made when a photo passes, and at posting for photos that passed before this; thumbnails stay the smaller cropped copy.
   - Each listing shows the most likely grade with its chance ("Most likely PSA 9 (50%)", a tie going to the lower grade) and "Close-up included" when there is one.
   - A buyer gives their ship-to address before the market shows anything to buy.
   - "Buy" shows the estimated postage and insurance, billed monthly, and the 7 days to pay the seller from arrival, before the confirm. The estimate is a live EasyPost rate for the cheapest service from the seller's address to the buyer's: a shipment is created to read its rates and no label is bought. Without an address, or if the quote fails, it's our usual $6.00 postage (`pokemans.shipping.estimate.postage`). Insurance is estimated at 1% of the price, $1 minimum. Nothing is stored.
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

   **What reaches the admin.** "Needs you" on `/admin/deals`, counted on `/admin` beside labels past their ship-by date (`GET /api/admin/deals/overdue`), lists every seller who says they weren't paid, every return, and every seller who disagrees with one, with both sides' contact details. The admin acts off the app, then closes the item with a note both sides see. A seller's own "I got paid" closes a payment item too.

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
  - **The 10 rung is capped** at M times the 9 rung: `offer_10 = min(offer_10, M · offer_9)`, both after the net price, the cap at the grade above, f, d and m (Zach, 2026-11-09, parameter set 1.4.0; not yet in the methodology doc). A PSA 10 often sells for 2–8× a 9, so a modest P(10) lifted the offer far above what a sure 9 earns. Clean cards keep a premium, a bounded one. M is `offers.maxTenToNineMultiple`, 2.5 by default (proposed, not yet confirmed by Zach). Example: 25% grade 10, 50% grade 9 and 25% grade 8, with the 10 at 5× the 9 and the 8 at half of it, drops the offer from 1.875 to 1.25 nine rungs.
    - Offers only. FMV, RV, QSV, FLV and the grade-first list keep the full ladder.
    - A 9 rung of zero (it nets to nothing after grading cost) leaves the 10 alone. Every price table prices the 9, observed or derived, so this is the only "no 9 price" case.
    - When the cap binds, the trace has an `offer_ten_cap` step with the uncapped 10 rung, the cap and M, stored with the offer. The admin's offer preview on the review page shows it.
  - **The card's own step-down ladder leaves out the step up to 10** (Zach, 2026-11-09, parameter set 1.4.0; not yet in the methodology doc). With no era ratio, the steepest per-grade ratio between the card's observed grades steps its lower grades down. A steep 9-to-10 jump used to win, so a Base Charizard PSA 7 came out at 13% of its 8. A PSA 10 is its own market, like a PSA 1, so steps up to 10 are left out of the steepest ratio. Grade 10 still counts towards the grades and spread the ladder needs. If the step to 10 is the card's only step, it has no ladder and stays unpriced without an era ratio.
    - The era step-down table still has a 9-to-10 ratio. It only prices a missing 9 from an observed 10 and never steps lower grades down, so it is unchanged.
  - Parameter sets 1.2.0 and 1.3.0 have neither rule, and runs made with them replay unchanged. The app still runs on 1.2.0 (`pokemans.parameter-set`); the rules take effect when it moves to 1.4.0, which also brings 1.3.0's comps-confidence rules.
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

## Stale and low offers (deep review 2026-11-09, B1 and B3; defaults to confirm)

These defaults are the reviewing session's picks, not Zach's decisions yet.

- **Raw sanity band (B1 step 1).** When an offer is quoted, the card's raw near-mint comps from the last 90 days are looked up. These are what PokeTrace stores. A PokeTrace comp is a day's average, so "lowest" is the lowest day.
  - The lowest one, the count and the window start are stored on the offer (`card_offer.raw_nm_*`).
  - The firm price below the lowest one sets `below_raw_sales`.
  - **The flag never refuses the offer and never changes the formula.**
  - The admin's offer preview on the review page shows the raw comps and the flag.
  - The seller is told "This offer is below recent raw sales of $X" on the card's offer.
  - Buyers never see it: the market and their deals leave it out.
  - It is hidden with the sold prices (`pokemans.offers.show-sold-prices`).
  - Mapping raw conditions to grades (step 2) isn't done.
- **Re-pricing at post and accept (B3).** Posting an offer, and a buyer accepting one, both re-run the offer's own ladder against the card's current price table and sales rate.
  - "The offer's own ladder" means the stored grade probabilities and buyer margin.
  - The current parameter set is used, and the default grading cost.
  - So only the market can move the price here: photo and condition changes have their own checks, which run first.
  - If the price moved more than `pokemans.offers.reprice-threshold` either way, the request is refused with "The price has changed. Get a new offer." The threshold defaults to 10%. It is a property, not part of the parameter set, and exactly 10% still holds.
  - The same refusal applies when the card can't be priced today.
  - The refusal appends rows and never edits one. At post, it appends a `card_offer_void`. At accept, it appends a `VOIDED` listing event plus the offer void, so the card comes off the market.
  - The void rows commit although the request is refused.
  - Their `voided_by` is the user whose request found the move: the seller or the buyer.
  - The self-dealing and address refusals at accept come before the re-price, so a refused buyer doesn't take the card off the market.
  - A listing voided this way counts as `VOIDED` in the track record.

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
    - `GET /api/orgs/{org}/market`: each photo has `url` (the thumbnail) and `fullUrl` (as taken)
    - `GET .../market/{id}/postage`: the estimate, stored nowhere and with no label bought; 409 once the card is taken
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
  - "Cards for sale", behind the ship-to address: full-screen photos, the likely grade, and a two-step "Buy" that shows the postage estimate;
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
  - Grade accuracy comes from what buyers say their cards graded (see Deal outcomes): only the cards a buyer chose to grade.

## Deal outcomes (deep review 2026-11-09, C1)

Closes the calibration loop: after a deal, the buyer says what happened to the card, so the condition we predicted can be compared with real grades.

- **Who:** only a member of the buying org, once the card has arrived and stayed (not cancelled, not returned). Nobody else can record one, the admin included, since it's the buyer's word (`POST /api/orgs/{orgId}/deals/{listingId}/outcomes`, `OrgAccess.requireMember`, which gives the admin no pass).
- **What:** "What happened to this card?" on the buyer's deals, with three answers:
  - **Graded:** grader (PSA, BGS, CGC, SGC, TAG), grade as on the label (half grades stored as given, folded down only when compared), cert number, optional label wording and date. It goes into `grade_outcome` (source `BUYER`, filed under the seller's org, which owns the card), so the admin checks the cert on Graded results as for any other. A verified cert values the card as graded.
  - **Resold raw:** the price.
  - **Kept.**
- **Append-only (V28 `deal_outcome`):** a correction is a new row, and the newest row is the answer. A buyer grade a later answer replaces stops valuing the card, but stays stored. The same answer twice is refused, and a cert can belong to only one card.
- **Who sees it:** the buyer org sees its own answers and its record: of the cards it graded, how many came back within one grade of our predicted median. The seller doesn't see the buyer's grade.
- **Admin "Deal outcomes"** (`/admin/deal-outcomes`, `GET /api/admin/deal-outcomes`): every deal's latest outcome against its offer's stored prediction: the distribution and median, the stressed median (the shift named in the offer's trace, else 2 grades, 3 at confidence C or D), the returned grade folded down (a result without a number counts as U), whether it is within ±1, and whether it is below the stressed median. Totals are shown for all grades and for checked certs only, against the targets (within ±1 at least 80%, below the stressed median under 5%). Certs that didn't match, and offers on cards already graded (which predicted no condition), are left out of the counts.
- **Tests:** `DealOutcomesTest` (only the buying org's members, before arrival, the comparison and corrections, a verified cert valuing the card until it's taken back, resale and cert rules, append-only), `CalibrationTest`, `RouteSweepTest`; Vitest for the buyer form and the admin page; the e2e beta flow records a grade and finds it on the admin page.

## Open

- **Buyer terms wording:** review before inviting the first buyer.
- **EasyPost account:** create one, test with a test key (labels are fake), then set the production key. EasyPost insures up to $5,000 a parcel, and the offer maximum is set to that limit. Read its insurance terms (what's excluded, how claims are paid) before the first expensive card ships.
- **After the sale, open (2026-11-01):** Shipped is the seller's word, since we don't read tracking yet. Nobody is told when a pay-by date passes: the seller has to look. "Arrived as shown" is final in the app, so a buyer who taps it and then finds a problem needs the admin.
- **Undo, open (2026-10-21):** the seller sees a voided offer's reason as the admin typed it, so write it for them; a cancelled deal after the card shipped needs the card back by hand; a refund's later status (EasyPost moves `submitted` to `refunded` or `rejected`) isn't fetched yet.
- **Invoices:** monthly postage bills are produced by hand from "Postage to bill". There is no invoicing or payment collection.
- **Deal outcomes, open (2026-11-09):** buyers grade the cards they think will grade well, so the sample leans high. Nobody is reminded to record an outcome. A cert the buyer typed is UNVERIFIED until the admin checks it on Graded results.
- **Notifications:** buyers aren't told when a card is posted, and sellers aren't emailed their label. Email waits for the notifier (M6). Until then a seller's home page lists what needs them across their orgs (`GET /api/orgs/{orgId}/needs-you`): a label to use with its ship-by date, an offer to get or take up with its end date, failed photos, and after a sale, a pay-by date to say they've been paid by, a payment the buyer recorded to confirm, and a returning card to say is back. A label stays listed until the seller says it shipped, or an arrival or return is recorded. A deal raised with the admin leaves the list until it's closed.
- **"Instant" depends on the review queue.** An offer needs an identified, assessed card, and identification and condition review are still done by hand.
- **Outside interest:** a marketplace for shops and collectors isn't "primarily to the financial services industry", but the employer's general outside-interest disclosure may still apply. Raise it in the lawyer hour.
- **Not checked in the running app:** logging in needs Zach's Auth0 MFA.

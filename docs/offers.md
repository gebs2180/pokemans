# Instant offers

Status 2026-10-19 · branch `claude/instant-offers`. Built on fixture and hand-entered data; no buyer has used it yet.

The lender and insurer side is paused while Zach clears his outside interest with his employer (see [m4-progress.md](m4-progress.md)). Instant offers reuse the same valuation engine for a different customer: collectors selling raw cards, and shops and dealers buying them.

## How it works

1. **The offer.** A member of a collector or contributor org opens a card and asks for an offer. The card is valued as of now:
   - its current condition distribution;
   - its PSA price table, net of the default grading cost;
   - its provenance.

   The offer is a price for every grade the card could inspect at, best grade first.
2. **Selling.** "Sell at this offer" posts it to every buyer org. Posting isn't a sale: the first buyer to accept takes the card, and the page says so. A listing nobody accepts expires.
3. **The deal.** The buyer gets the seller's org name and email, and the seller gets the buyer's. Shipping, inspection and payment happen between them. We hold no cards or money (Zach doesn't rule out becoming the middleman later).
4. **The inspection.** Once the card is in hand, the buyer records:
   - the grade found (PSA scale, half steps, 0 for ungradable);
   - whether it is genuine;
   - the price paid, or why they declined. The only reasons allowed are: not genuine, not the listed card, or doesn't match its photos.

   The record shows the ladder price at that grade, so a dispute starts from the facts.

## Decisions (Zach, 2026-10-19)

- **We compute the offer**; buyers don't set rules. First come, first served.
- **Rung price:** `offer_g = P↓_g · (1 − f) · (1 − d) · (1 − m)`.
  - P↓ is the grade's net price (net of grading cost), capped at the grade above, so a PSA 1 premium never pays more for a worse card.
  - f = 15% fees; d = the speed discount for the card's liquidity (5/10/20%); m = a 10% buyer margin.
  - For a verified graded card the ladder is one rung, at gross prices.
  - The expected offer, Σ p·offer, is shown for orientation only.
- **Validity:** an offer stands for 7 days, and a posted listing for 14 (`pokemans.offers.*`).
- **No offer for:**
  - lots, unidentified or unassessed cards;
  - cards without a PSA table;
  - cards whose authenticity is doubted;
  - confidence D;
  - FMV under $25.

  Unverified high-risk cards (vintage holos, Japanese promos) do get an offer, marked "subject to the buyer authenticating the card in hand".
- **Calibration:** the buyer's inspected grade is stored as its own source (BUYER_INSPECTION, in `listing_event`), kept apart from graders' results because a buyer has a reason to grade low.

## Built

- **valuation-core:** `OfferLadder`, with tests (`OfferLadderTest`).
- **Valuation:** `Valuations.current(cardItemId)` values one card as of now with the run engine, so offers and valuation runs can't drift apart.
- **identity:**
  - a `BUYER` org type and role;
  - buyer terms, a versioned text in `Consents`. It is a plain-language **draft for Zach**, not reviewed legal wording;
  - `Directory` for contact details.
- **`offers` module, V14 (all append-only):**
  - `card_offer`: every input, the ladder and the trace;
  - `sale_listing`;
  - `listing_event`: ACCEPTED, WITHDRAWN, INSPECTED. A partial unique index lets a listing close only once, so two buyers racing, or a buyer and a seller withdrawing, can't both win. Posting is serialized per card with an advisory lock.
- **API:**
  - Seller:
    - `GET /api/orgs/{org}/cards/{card}/sale`
    - `POST .../offers`
    - `POST .../listings`
    - `POST /api/orgs/{org}/listings/{id}/withdraw`
  - Buyer (buyer org, terms accepted):
    - `GET /api/orgs/{org}/market`
    - `POST .../market/{id}/accept`
    - `GET .../deals`
    - `POST .../deals/{id}/inspection`
  - The market never names the seller. Contact details appear only to the two parties once a buyer accepts.
- **Web:**
  - an "Instant offer" panel on the card page;
  - a buyer org page behind the buyer terms;
  - "Cards for sale", with a two-step accept;
  - "Your deals", with the inspection form;
  - BUYER in the admin's org form.
- **Tests:**
  - `OffersFlowTest` covers the race between two buyers, withdrawal and reposting, every refusal, the vintage-holo condition and append-only rows.
  - `CrossOrgAccessTest` covers every new route.
  - Vitest covers `OfferPanel`, `MarketPage`, `InspectionForm` and `likelyRungs`.

## Open

- **Buyer terms wording:** review before inviting the first buyer.
- **Notifications:** buyers aren't told when a card is posted; they check "Cards for sale". Email waits for the notifier (M6).
- **"Instant" depends on the review queue.** An offer needs an identified, assessed card, and identification and condition review are still done by hand.
- **Buyers' honesty:** inspected grades and prices paid are self-reported. Compare them per buyer once there are enough, and with graders' results where a card is later graded.
- **Outside interest:** a marketplace for shops and collectors isn't "primarily to the financial services industry", but the employer's general outside-interest disclosure may still apply. Raise it in the lawyer hour.
- **Not checked in the running app:** logging in needs Zach's Auth0 MFA.

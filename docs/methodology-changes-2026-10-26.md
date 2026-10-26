# Methodology doc catch-up

2026-10-26 · for Zach to review and apply. Nothing here has been written into the Claude Doc.

`CLAUDE.md` marks 15 decisions "not yet in the methodology doc". This file lists each one with its date, the exact `CLAUDE.md` text, the doc section it belongs in, and suggested wording in the doc's own style. A second part lists places where `CLAUDE.md` and the doc disagree, or where the doc is silent, without a marker.

**Compared:** the [Raw Collection Valuation Methodology](https://claude.ai/artifact/1XVmPmLtueJk37u2hXgDmL) Claude Doc at revision 46 (header date 2026-10-18; its "Changes since 2026-10-15" table ends with the 2026-10-18 changes) against `CLAUDE.md` on main at `15a85f2`.

**How to apply:** each suggestion says whether it replaces a sentence or adds one. After applying, add one row per change to the doc's "Changes since 2026-10-15" table (a row is suggested for each), update the header date, then remove the "not yet in the methodology doc" marker from `CLAUDE.md`.

## Part 1: the 15 marked decisions

### 1. Per-card photos only, no bulk lots (2026-10-18)

**`CLAUDE.md` (Pipeline 1):**
> Per-card front and back photos for every card at or above $25 est. raw value; bulk lots aren't supported (Zach, 2026-10-18; not yet in the methodology doc).

**Doc section:** Step 1: Identification and inventory → **Capture.** It contradicts the doc, which says "bulk lots below it are counted and photographed as lots".

**Suggested wording (replaces the first sentence of Capture):**
> **Capture.** Front and back photos for every card at or above a value threshold (start at $25 estimated raw value). Bulk lots aren't valued: a card below the threshold is left out rather than counted in a lot.

**Changes row:** 2026-10-18 · Step 1 · Bulk lots are no longer counted or photographed as lots; only per-card photos at or above $25 are supported.

### 2. Verifying an authenticity flag (2026-10-18)

**`CLAUDE.md` (Pipeline 1):**
> Verifying a flag needs an in-hand inspection, a grader's slab or third-party authentication. High-risk cards (vintage holos and 1st Editions, Japanese promos) can't be verified from photos. A recent chase card can be verified by a photo review. The review names the photos it used, which must include a passing close-up. A finding, verification or doubt, counts only for the identity it was made against, so re-identifying a card resets it (Zach, 2026-10-18; not yet in the methodology doc).

**Doc section:** Step 1 → **Flag authenticity risk.** The doc says flagged cards are zero "until verified" but never says what verifies one. Also "Validation plan, limitations and open questions" → Limitations, which says "Authenticity is flagged, not verified" (see Part 2).

**Suggested wording (added after "Flagged cards carry zero collateral value until verified."):**
> A flag is cleared by an in-hand inspection, a grader's slab or third-party authentication. Vintage holos, 1st Editions and Japanese promos can't be cleared from photos. A recent chase card can be, by a photo review that names the photos it used, including a passing close-up. A finding, whether it clears the card or doubts it, holds only for the identity it was made against: re-identifying the card resets it.

**Changes row:** 2026-10-18 · Step 1 · What clears an authenticity flag; photo review only for recent chase cards; findings reset when a card is re-identified.

### 3. Who records and who verifies custody (2026-10-18)

**`CLAUDE.md` (Pipeline 1):**
> Partners and collectors record their own custody, but only an admin verifies it, and a check lapses after 90 days (Zach, 2026-10-18; not yet in the methodology doc).

**Doc section:** Step 1 → **Record custody.**

**Suggested wording (added at the end of Record custody):**
> Partners and collectors record their own custody; only we verify it, and a verification lapses after 90 days.

**Changes row:** 2026-10-18 · Step 1 · Custody is self-recorded, admin-verified, and lapses after 90 days.

### 4. Raw cards on PSA's table only (2026-10-18)

**`CLAUDE.md` (Pipeline 3):**
> A raw card is valued on PSA's table only: no PSA table means unpriced and excluded, with no fallback to another grader (Zach, 2026-10-18; not yet in the methodology doc).

**Doc section:** Step 3: Pricing inputs and comps → **Grading companies.**

**Suggested wording (added at the end of Grading companies):**
> A raw card is valued on PSA's prices only. A card with no PSA prices is unpriced and left out; it never falls back to another grader's.

**Changes row:** 2026-10-18 · Step 3 · Raw cards are priced on PSA's table only, with no fallback.

### 5. Grading cost of the day is a run input (2026-10-18)

**`CLAUDE.md` (Pipeline 3):**
> The grading cost of the day is an input to each valuation run, starting at $25 fee, $5 shipping, $2 insurance per card and 2% time-to-cash (Zach, 2026-10-18; not yet in the methodology doc).

**Doc section:** Step 3 → **Net of grading cost.**

**Suggested wording (added at the end of Net of grading cost):**
> The grading cost is an input to each valuation, so a report uses the cost of its day: start at a $25 fee, $5 shipping and $2 insurance per card, and a 2% time-to-cash discount.

**Changes row:** 2026-10-18 · Step 3 · Grading cost is set per valuation, with starting values.

### 6. Fallback volatility (2026-10-18)

**`CLAUDE.md` (Pipeline 3):**
> With too few changes to measure it, a valuation uses a fallback σ of 20% a month and drops confidence one level (Zach, 2026-10-18; not yet in the methodology doc).

**Doc section:** Step 3 → **Volatility.**

**Suggested wording (added at the end of Volatility):**
> A card with too few price changes to measure uses a fallback of 20% a month, and its confidence drops one level.

**Changes row:** 2026-10-18 · Step 3 · Fallback σ of 20% a month, with a one-level confidence drop.

### 7. Cards with a verified cert are valued as graded (2026-10-18, confirmed 2026-10-19)

**`CLAUDE.md` (Pipeline 4):**
> A card whose returned grade's cert is verified is valued as graded: certain at that grade (half grades fold down), on its own grader's table at gross prices, with no grading cost and no condition stress shift; it counts in the graded share and is left out of grade-first (Zach, 2026-10-18; no condition stress confirmed 2026-10-19: a graded card is priced at its grade, whatever its condition; not yet in the methodology doc).

**Doc section:** Step 4: The four values. It fits best as a new paragraph after the FLV bullets, before "Each value is reported with a confidence grade". Step 5 → **Raw share** and Step 6 need a pointer each.

**Suggested wording (new paragraph in Step 4):**
> **Graded cards.** A card whose grade comes back with a verified cert is valued as graded: certain at that grade (half grades fold into the grade below), on its own grader's prices, gross, with no grading cost. There is no condition stress: a graded card is priced at its grade, whatever its condition. It counts in the graded share and is left out of the grade-first list.

**Changes row:** 2026-10-18 (stress confirmed 2026-10-19) · Step 4 · Verified graded cards are certain at their grade, gross, unstressed.

### 8. Concentration caps against the eligible FLV, at every size (2026-10-19)

**`CLAUDE.md` (Pipeline 5):**
> Single card ≤ 20% and top five ≤ 50% of the eligible FLV before caps, applied by capping every card at one level, at every collection size with no minimum card count (Zach, 2026-10-19; replaces the 2026-10-18 final-base rule, which squeezed 10–15 card pledges down to their smallest card's FLV and had a cliff at 10 cards; not yet in the methodology doc). So one card counts at most 20% of its FLV, and five or fewer at most 50%;

**Doc section:** Step 5: Portfolio risk and advance rate → **Single-card cap**, **Top-five cap** and **Small collections.** It contradicts all three, which still say "final, capped base" and "Below 10 they are not applied". It also breaks the worked example (see Part 2, item A).

**Suggested wording (replaces the three bullets):**
> **Single-card cap.** No card counts for more than 20% of the eligible FLV before caps; value above the cap is excluded.
>
> **Top-five cap.** The five largest cards together count for no more than 50% of the eligible FLV before caps. Both caps work through one cap level for every card: the highest level that keeps each card within 20% and the top five within 50%.
>
> **Small collections.** The caps apply at every size, with no minimum card count, so there's no cliff where one more card switches them on. A small pledge is held back by its concentration instead: one card counts for at most 20% of its FLV, and five or fewer for at most 50% of theirs.

Keep the **Correlation haircut** sentence "The haircut comes after the caps": the code still applies it to the capped values.

**Changes row:** 2026-10-19 · Step 5 · Caps are measured against the eligible FLV before caps, at every size; replaces the 2026-10-18 final-base rule and its 10-card minimum.

### 9. What counts as a set or a character (2026-10-19)

**`CLAUDE.md` (Pipeline 5):**
> A set is language plus set name. A character is the Pokédex species in any language, form or owner (Charizard ex, Dark and Mega Charizard and リザードン are one slice), and a tag team counts toward its more popular partner (Zach, 2026-10-19; not yet in the methodology doc).

**Doc section:** Step 5 → **Correlation haircut.**

**Suggested wording (added after "Hype cycles move these together."):**
> A set is its language plus its name. A character is the Pokédex species in any language, form or owner, so Charizard ex, Dark Charizard, Mega Charizard and リザードン are one slice. A tag team counts toward its more popular partner.

**Changes row:** 2026-10-19 · Step 5 · Definitions of a set and a character slice.

### 10. Grade-first uses gross prices for FLV raw (2026-10-18)

**`CLAUDE.md` (Pipeline 6):**
> FLV_raw here is computed from gross prices, so grading cost counts once, in C (Zach, 2026-10-18; not yet in the methodology doc).

**Doc section:** Step 6: Grade-first recommendations, the bullet list under the Lift formula.

**Suggested wording (new bullet after "FLV graded = …"):**
> FLV raw = the card's FLV computed from gross prices, before grading cost, so grading cost counts once, in C.

**Changes row:** 2026-10-18 · Step 6 · FLV raw in the lift uses gross prices.

### 11. Buyer margin by confidence (2026-10-21)

**`CLAUDE.md` (Pipeline 8):**
> the net price capped at the grade above and m the buyer margin by the card's confidence: 10% at A, 13% at B, 17% at C, so a less certain condition leaves the buyer more room (Zach, 2026-10-21; parameter set 1.2.0; not yet in the methodology doc).

**Doc section:** none yet. The doc has no instant-offer section at all (see Part 2, item B). Proposed new section **"Step 7: Instant offers"**, after Step 6 and before the worked example. Items 11 to 15 all go there.

**Suggested wording (the formula and parameters, as the opening of that section):**
> A collector can sell a raw card for one firm price. The price is the probability-weighted offer across grades:
>
> `price_i = Σ_g p_{i,g} · offer_{i,g}`, with `offer_{i,g} = P↓_{i,g} · (1 − f) · (1 − d_i) · (1 − m_i)`
>
> - P↓ = the grade's net price, capped at the price of the grade above.
> - f and d = the fees and speed discount from QSV.
> - m = the buyer's margin, by the card's confidence: 10% at A, 13% at B, 17% at C. A less certain condition is a bigger risk for the buyer under a firm price, so it leaves the buyer more room.

**Changes row:** 2026-10-21 · Step 7 · Buyer margin by confidence (parameter set 1.2.0).

### 12. Offer confidence leaves out FLV-only penalties (2026-10-21)

**`CLAUDE.md` (Pipeline 8):**
> The confidence an offer is priced at leaves out the penalties that only bear on FLV: a fallback σ, and derived prices that only the stress reaches. It keeps the photo and comps base, a stale table, and a derived price where the central distribution puts probability.

The marker for this sentence and the next is on item 13's sentence: "(Zach, 2026-10-21; not yet in the methodology doc)".

**Doc section:** new Step 7: Instant offers.

**Suggested wording:**
> **Confidence for an offer.** An offer is priced at the card's confidence without the penalties that only bear on FLV: the fallback volatility, and derived prices that only the stressed distribution reaches. It keeps the rest: photo quality and comp counts, a stale price table, and a derived price for a grade the central distribution gives some probability.

**Changes row:** 2026-10-21 · Step 7 · Offer confidence leaves out FLV-only penalties.

### 13. Derived grades still price offers (2026-10-21)

**`CLAUDE.md` (Pipeline 8):**
> Derived (extrapolated) grades still price offers (Zach, 2026-10-21; not yet in the methodology doc).

**Doc section:** new Step 7: Instant offers.

**Suggested wording (added after the confidence paragraph):**
> Derived prices, including those stepped down from higher grades, still price an offer.

**Changes row:** 2026-10-21 · Step 7 · Derived grades price offers.

### 14. The $500 maximum offer (2026-10-21)

**`CLAUDE.md` (Pipeline 8):**
> No offer for lots, doubted cards, confidence D, FMV under $25, or a price above the $500 maximum (`pokemans.offers.max-price`, which also keeps every label under EasyPost's $5,000 insurance cap; Zach, 2026-10-21; not yet in the methodology doc); unverified high-risk cards get one subject to authentication in hand.

**Doc section:** new Step 7: Instant offers. The doc doesn't need the config key.

**Suggested wording:**
> **No offer.** Lots, cards with an authenticity doubt, confidence D, FMV under $25, and any card whose price would be above the maximum (start at $500) get no offer. The maximum limits the harm a miscalibrated price can do and keeps every shipping label within the carrier's insurance cap. An unverified high-risk card gets an offer subject to authentication in hand.

**Changes row:** 2026-10-21 · Step 7 · A $500 maximum offer.

### 15. Admin undo by appending (2026-10-21)

**`CLAUDE.md` (Pipeline 8):**
> The admin undoes by appending, never editing: a voided offer comes off the market and can't be sold; a deal cancelled before arrival frees the card for a new offer, its label isn't billed to the buyer and can be marked for a refund; each is a new row with who, when and why (Zach, 2026-10-21; not yet in the methodology doc).

**Doc section:** new Step 7: Instant offers.

**Suggested wording:**
> **Undo.** Mistakes are undone by adding a record, never by editing one. A voided offer comes off the market and can't be sold. A deal cancelled before the card arrives frees the card for a new offer at that day's price; its label isn't billed to the buyer and can be refunded. Each undo records who, when and why.

**Changes row:** 2026-10-21 · Step 7 · Admin undo by appending.

## Part 2: other gaps and disagreements

These have no "not yet in the methodology doc" marker, but the doc and `CLAUDE.md` don't match.

### A. The worked example's advance no longer follows the caps (needs a decision)

The doc's worked example says: "A one-card collection is under 10 eligible cards, so the concentration caps don't apply and the advance is α × FLV", and gives an advance of $64. Under the 2026-10-19 rule (item 8), one card counts for at most 20% of its FLV, so a one-card pledge would count $107 × 20% ≈ $21 and advance about $13 at α = 60%.

`CLAUDE.md`'s regression check still says "advance at α=60% $64", and `WorkedExampleGoldenTest` gets $64 by calling `PortfolioEvaluator.advance` on the card's FLV directly, without the caps. So the test passes, but the example's "Advance" line no longer describes what a one-card collection would get.

**Options for Zach:**
1. Keep $64 and reword the example as a single card's contribution before caps: "Advance on this card's FLV at α = 60%, before concentration caps: $64. Pledged alone, it would count for 20% of its FLV ($21), an advance of $13."
2. Change the example and `CLAUDE.md` to $13.

Option 1 keeps the regression numbers and the test unchanged, so it's the smaller change. Either way, delete the "under 10 eligible cards" sentence.

### B. Instant offers aren't in the doc at all (2026-10-19)

`CLAUDE.md` Pipeline 8 (Zach, 2026-10-19) describes the offer flow; the doc's purpose still reads as lender-only ("The goal is a valuation a lender can advance against"). Items 11 to 15 cover the parameters. The section also needs the parts of Pipeline 8 that carry no marker, which are product rules more than valuation rules:
- Selling posts the offer to every buyer org; the first to accept buys at the price; posting isn't a sale.
- Offers last 7 days, listings 14.
- On accept, a prepaid label insured for the price is bought on our shipping account and billed to the buyer monthly.
- The buyer pays the seller off-platform within 7 days of a card arriving as shown, or returns it only if it's not genuine, not the listed card, or doesn't match its photos. No grade is judged on arrival.

**Suggested purpose sentence (added after the first paragraph of "Purpose and principles"):**
> Since 2026-10-19 the same valuation also prices instant offers: one firm price for a raw card, taken by a buyer at that price (Step 7). The lender and insurer reports are paused meanwhile.

Whether the operating rules (expiry, labels, payment, returns) belong in the methodology or only in `docs/offers.md` is Zach's call. A short paragraph with a pointer to the repo is enough.

### C. Reports aren't in the doc (2026-10-18)

`CLAUDE.md` Pipeline 7 has no counterpart in the doc:
- A valuation run stores every input and the full trace, and reports render from the run alone.
- Lender report contents, and "a lender-given α adds one line".
- Insurer report: RV for every identified, priced card with its photos; counterfeit or suspected cards get RV $0 with the reason; custody and confidence D don't exclude a card.
- Only the admin issues reports; members of the collection's org can download them (Zach, 2026-10-18).

The insurer rule matters most: the doc's Step 4 says RV is "reported per card and in total" but not which cards are in it. **Suggested wording (new bullet under RV in Step 4):**
> The insurer's schedule lists RV for every identified, priced card, with its photos as pre-loss proof. A counterfeit or suspected card is listed at $0 with the reason. Custody and confidence D don't remove a card from it.

### D. Eligibility in Step 5 misses custody and lots

`CLAUDE.md`: "Eligible = not D, no unresolved auth flag, verified custody, FMV ≥ $25. Lots are never valued." The doc's **Eligibility** bullet lists D, flags and FMV only; custody appears in Step 1 instead.

**Suggested wording (replaces Eligibility):**
> **Eligibility.** Exclude cards with confidence grade D, unresolved authenticity flags, unverified custody, or FMV below $25. Lots are never valued.

### E. "Authenticity is flagged, not verified" (Limitations)

With item 2 there is a verification path, so this limitation now overstates it. **Suggested wording:**
> Authenticity is flagged, and cleared only by a slab, third-party authentication, an in-hand inspection, or (for recent chase cards only) a photo review. Counterfeits and resealed product still need physical inspection.

### F. Doubted cards in the grade-first list

`CLAUDE.md` Pipeline 6 ends "Doubted cards are left off." The doc's Step 6 doesn't say so. Add to the last bullet: "Cards with an authenticity doubt are left off."

### G. Smaller wording differences (no decision needed)

- **Threshold:** the doc says "above a value threshold"; `CLAUDE.md` says "at or above $25". Item 1's wording uses "at or above".
- **Nightly refresh:** the doc's Stale prices bullet says "Prices are refreshed nightly." The nightly job exists but is off by default until a price source is licensed (`PricingProperties`, `PricingScheduler`), and the beta uses hand-entered comps. Suggest "Prices are refreshed on a schedule once a licensed source is connected; until then, when comps are entered."
- **Detail only in the doc:** the interpolation cap at the higher grade's price, ratios capped at 1, and the hand-entered release-date rule. `CLAUDE.md` omits these; that's fine, the doc wins, and nothing contradicts them.
- **Changes table intro:** "Eleven rules and the worked example changed between 2026-10-17 and 2026-10-18" needs updating to cover 2026-10-19 and 2026-10-21, and to add `docs/offers.md` and `docs/e2e-audit-2026-10-21.md` as sources.

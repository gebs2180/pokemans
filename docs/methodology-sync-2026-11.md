# Methodology doc sync, November 2026

2026-11-18 · approved by Zach on 2026-11-18, with the four decisions below, and applied to the Claude Doc the same day (revision 46 → 73). The doc's "Changes since 2026-10-15" table has one row per date and section, citing these item numbers. Dates and sources are in that table; "defaults to confirm" and starting-guess labels stay inline next to each rule.

**Zach's decisions (2026-11-18):**
1. **Repricing (item 34):** the 10% threshold is Zach's decision, confirmed 2026-11-18. The rule came from the 2026-11-09 deep review (B3).
2. **Tighter spread (item 9):** the rule is Zach's (2026-11-10); its weights are defaults to confirm, as the code says.
3. **Code-only rules (items 37–46):** all ten belong in the methodology. The numbers in 37–40 and 43 are labelled starting guesses to calibrate against returned grades.
4. **Worked example (item 36A):** option 1. Keep $64 as the advance on the card's FLV before concentration caps, and show that pledged alone it counts $21, an advance of $13.

The [Raw Collection Valuation Methodology](https://claude.ai/artifact/1XVmPmLtueJk37u2hXgDmL) Claude Doc (revision 46, header date 2026-10-18) is behind `CLAUDE.md`: 34 rules are missing from it or disagree with it (item 21 was withdrawn), and 10 more are in the code but in neither (items 37–46). This file lists each one under the doc section it belongs in, with the text to add or replace. It supersedes [methodology-changes-2026-10-26.md](methodology-changes-2026-10-26.md), which covered 15 of them and was never applied; its wording is reused here where it still holds.

**Compared:** the doc at revision 46 against `CLAUDE.md` on main at `796c4cc`, then each item checked against the backend code at the same commit: parameter set 1.8.0 (`backend/valuation-core/src/main/resources/parameter-sets/`), `application.yml` and the services named under [Checked against the code](#checked-against-the-code). That check corrected items 4, 21 and 34 and found items 37–46, which are built but in neither `CLAUDE.md` nor the doc.

**Rules for this file:**
- Each item keeps its attribution and date as `CLAUDE.md` gives them.
- "Defaults to confirm" labels are kept as they are. This file doesn't resolve them; they stay marked in the doc too, so a reader can tell a confirmed rule from a starting value.
- Config keys and parameter-set versions are kept in brackets where `CLAUDE.md` gives them, so code and doc can be matched.
- Where the doc and `CLAUDE.md` disagree, `CLAUDE.md` holds the later decision; the proposal follows it and says what it replaces.

**How to apply (once approved):** make the edits section by section, add one row per item to the doc's changes table (item 36 rewrites its intro), and move the header date to the day of the edit. Then drop the "not yet in the methodology doc" markers; `CLAUDE.md` no longer carries the rule text (see the PR that added this file).

## Summary

| # | Doc section | Rule | Date | Kind |
| --- | --- | --- | --- | --- |
| 1 | Purpose and principles | Instant offers are a use of the valuation; lender/insurer side paused | 2026-10-19 | Add |
| 2 | Purpose and principles | Store everything, including wrong predictions | — | Add |
| 3 | Purpose and principles | Conservative: never shift upward for prices, grades or photo confidence | — | Replace |
| 4 | Step 1 | Per-card photos at or above $25; a lot is recorded but never identified, valued or offered | 2026-10-18 | Replace |
| 5 | Step 1 | What verifies an authenticity flag; findings reset on re-identification | 2026-10-18 | Add |
| 6 | Step 1 | Custody self-recorded, admin-verified, lapses after 90 days | 2026-10-18 | Add |
| 7 | Step 2 | Half grades always fold down until half-grade comps exist | 2026-11-04 | Replace |
| 8 | Step 2 | Pre-grade providers: one provider is A (B above $1,000 FMV); B and C triggers | 2026-11-10 | Add |
| 9 | Step 2 | Tighter spread when the lowest subgrade is at or above the overall grade | 2026-11-10 | Add |
| 10 | Step 2 | No default surface shift for holos or full arts | 2026-11-04 | Replace |
| 11 | Step 2 | Our centering measurement caps the distribution; reviewer override | 2026-10-31, 2026-11-01 | Add |
| 12 | Step 2 | An assessment counts only for its front and back photos | 2026-11-04 | Add |
| 13 | Step 3 | PokeTrace: sale days as comps, count never used, admin-confirmed mappings | 2026-11-07 | Add |
| 14 | Step 3 | PokeTrace high-day guards | 2026-11-09 | Add |
| 15 | Step 3 | PokeTrace revisions appended; pull gaps flagged | 2026-11-11 | Add |
| 16 | Step 3 | Step up to grade 10 left out of the card's steepest ratio | 2026-11-09 | Add |
| 17 | Step 3 | Raw cards valued on PSA's table only | 2026-10-18 | Add |
| 18 | Step 3 | Grading cost of the day is a run input | 2026-10-18 | Add |
| 19 | Step 3 | Raw-sale floor (P_g = max(net graded, raw floor)) | 2026-11-11 | Replace |
| 20 | Step 3 | Fallback σ; measured σ above 1.0 treated the same | 2026-10-18, 2026-11-09 | Add |
| 21 | Step 3 | *Withdrawn: the doc's "refreshed nightly" matches the code* | — | None |
| 22 | Step 4 | Verified graded cards valued as graded | 2026-10-18, 2026-10-19 | Add |
| 23 | Step 5 | Eligibility adds custody and "lots are never valued" | 2026-10-18 | Replace |
| 24 | Step 5 | Caps against eligible FLV before caps, at every size | 2026-10-19 | Replace |
| 25 | Step 5 | What a set and a character are | 2026-10-19 | Add |
| 26 | Step 6 | FLV raw from gross prices | 2026-10-18 | Add |
| 27 | Step 6 | Doubted cards left off | — | Add |
| 28 | New Step 7: Reports | Run stores inputs and trace; lender and insurer report contents; who issues | 2026-10-18 | Add |
| 29 | New Step 8: Instant offers | Offer formula and buyer margin by confidence | 2026-10-21 | Add |
| 30 | New Step 8 | Offer confidence; derived grades price offers | 2026-10-21 | Add |
| 31 | New Step 8 | 10 rung capped at 2.5 × the 9 rung | 2026-11-09 | Add |
| 32 | New Step 8 | Selling, expiry, labels, payment, returns; no self-buying; doubts void offers | 2026-10-19, 2026-11-02 | Add |
| 33 | New Step 8 | No-offer list and the $5,000 maximum | 2026-10-21, 2026-10-26 | Add |
| 34 | New Step 8 | Reprice at post and accept; below-raw-comp flag | 2026-11-09 | Add |
| 35 | New Step 8 | Admin undo by appending | 2026-10-21 | Add |
| 36 | Worked example, Limitations, changes table | Advance line vs the caps; limitation wording; table intro | — | Decision + replace |
| 37 | Step 2 | Default spread of a provider grade; providers averaged | — | Add (code only) |
| 38 | Step 2 | P(10) capped at 25%, excess to 9 | review B2 | Add (code only) |
| 39 | Step 2 | U gets at least 2% | review B2 | Add (code only) |
| 40 | Step 2 | Penalty sizes (B 10%, C 25%); providers more than 1.5 apart are C | — | Add (code only) |
| 41 | Step 2 | Assessments on a retired provider wait to be assessed again | 2026-11-10 | Add (code only) |
| 42 | Step 3 | Outlier trim is 10% from each end | — | Add (code only) |
| 43 | Step 3 | An era step-down ratio needs 3 cards | — | Add (code only) |
| 44 | Step 4 | Comps confidence thresholds: 10 / 5 / 2 sales for A / B / C | — | Add (code only) |
| 45 | Step 4 | Thin grades count as derived; modal-grade cap on comps confidence | review B5, set 1.3.0 | Add (code only) |
| 46 | Step 5 | How a character is found; a card with no species is keyed by its name | 2026-10-19 | Add (code only) |

## Purpose and principles

### 1. Instant offers (Zach, 2026-10-19)

**Add after the first paragraph:**
> Since 2026-10-19 the same valuation also prices instant offers: one firm price for a raw card, taken by a buyer at that price (Step 8). The lender and insurer side is paused meanwhile.

### 2. Store everything (`CLAUDE.md` principle, no date)

**Add a principle bullet:**
> **Store everything, including wrong predictions.** Photos, model and provider predictions, our distribution, human adjustments and returned grades are all calibration and training data.

### 3. Conservative by default (`CLAUDE.md` principle, no date)

The doc says "It never interpolates upward." `CLAUDE.md` is broader.

**Replace the bullet's last sentence with:**
> It never interpolates or shifts upward, whether for prices, grades or photo confidence.

## Step 1: Identification and inventory

### 4. Per-card photos only, no bulk lots (Zach, 2026-10-18)

Contradicts the doc's "bulk lots below it are counted and photographed as lots".

**Replace the first sentence of Capture with:**
> **Capture.** Front and back photos for every card at or above $25 estimated raw value. Bulk lots aren't supported: a lot can be recorded, with a description and a count, but it is never identified card by card, valued or offered.

*Code check:* `CLAUDE.md` says only "bulk lots aren't supported". The intake API still accepts a lot (`IntakeController.CreateCard`: `lot`, `quantity`, a required description), and identification, valuation and offers each refuse it (`IdentifyService`, `UnvaluedReason.LOT`, `OfferRefusal.LOT`). The wording above describes what the code does.

### 5. Verifying an authenticity flag (Zach, 2026-10-18)

**Add after "Flagged cards carry zero collateral value until verified.":**
> Verifying a flag needs an in-hand inspection, a grader's slab or third-party authentication. High-risk cards (vintage holos and 1st Editions, Japanese promos) can't be verified from photos. A recent chase card can be verified by a photo review, which names the photos it used; they must include a passing close-up. A finding, verification or doubt, counts only for the identity it was made against, so re-identifying a card resets it.

### 6. Custody (Zach, 2026-10-18)

**Add at the end of Record custody:**
> Partners and collectors record their own custody, but only an admin verifies it, and a check lapses after 90 days.

## Step 2: Condition as a probability distribution

### 7. Half grades (Zach, 2026-11-04)

The doc folds a half grade down "unless that half grade has its own sales history". `CLAUDE.md` folds always, for now.

**Replace the Half grades bullet with:**
> **Half grades.** Half grades (8.5, 9.5) are always folded into the grade below, until half-grade comps exist to price them.

### 8. Pre-grade providers and photo confidence (Zach, 2026-11-10; parameter set 1.6.0; the threshold and FMV as the measure are defaults to confirm)

The doc describes only "a photo-based model". **Add after Photo confidence:**
> **Pre-grade providers.** A single provider moves no probability down for that alone, and its answer on whole-card photos is confidence A. A valuation or offer counts that A as B when the card's FMV (central distribution, net prices, same run) is above $1,000 (`confidence.oneProviderMaxFmvForA`; the threshold and FMV as the measure are defaults to confirm), and the trace records when it does. Providers more than a grade apart (B) and photos not showing the whole card (C) still move probability down.

### 9. Spread from subgrades (Zach, 2026-11-10; weights are defaults to confirm, decided 2026-11-18)

**Add after item 8:**
> **Spread from subgrades.** When the provider's lowest of corners, edges and surface, folded down, is at or above its overall grade, its grade gets a tighter spread: +1 10%, 0 60%, −1 25%, −2 5% (weights are defaults to confirm). A lower subgrade, or none given, keeps the default spread. The provider's centering subgrade is never read.

### 10. No default surface shift (Zach, 2026-11-04)

Contradicts the doc's Surface penalty ("Modern holos and full-art cards get a default downward shift unless a close-up clears them").

**Replace the Surface penalty bullet with:**
> **Surface.** There is no default surface shift for holos or full arts. Surface comes from the pre-grade providers' grades, or our own pre-grading once we have it.

### 11. Our centering cap (Zach, 2026-10-31; side by side and the reviewer rule 2026-11-01)

**Add:**
> **Centering.** Our own centering measurement caps a system distribution at the best grade PSA's table allows, and is never averaged in. A later "not measured" leaves the cap in place, side by side, so a retake that measures only the back keeps the last front's limit. A reviewer's distribution above the cap is refused unless they override it, which is recorded with their reason. Another provider's centering is never used.

### 12. Assessments belong to their photos (Zach, 2026-11-04)

**Add:**
> **Retakes.** An assessment counts only for the front and back it was made from. Once either is retaken, the card gets no offer until it is assessed again (by asking the providers again or by a reviewer). The centering cap carries over.

## Step 3: Pricing inputs and comps

### 13. PokeTrace as the live source (Zach, 2026-11-07)

**Add under Sources:**
> **PokeTrace** (live since 2026-11-07, with written permission for offers, display and keeping the data) gives daily rows with a deliberately noised count. Each day with a sale is one comp at the day's average, and the count is never used. Counts are sale days, a lower bound on the sales. An admin confirms every card mapping; one that disagrees with our card needs a reason.

### 14. PokeTrace high-day guards (deep review B4, 2026-11-09; parameter set 1.5.0; thresholds are defaults to confirm)

**Add after item 13:**
> A PokeTrace day is left out of pricing, never shifted, when its average is outside its own low–high, its high is over 3× its low, or its average is over 3× the median of at least 2 other sale days of its tier within 90 days. Only high days are caught, and each one is stored with its reason. (Thresholds are defaults to confirm.)

### 15. PokeTrace revisions and pull gaps (PR #114 follow-ups, 2026-11-11; parameter set 1.7.0; both defaults to confirm)

**Add after item 14:**
> When PokeTrace sends a different average, low or high for a stored day, the new values are appended, never edited in. The newest revision prices the day and the guards judge it; older rows are kept and traced as superseded. A mapped card with no successful pull for 2 days is flagged to the admin and fails the pricing healthcheck. (Both are defaults to confirm.)

### 16. Steepest ratio leaves out the step up to 10 (Zach, 2026-11-09; parameter set 1.4.0)

**Add at the end of "Sales only above":**
> The step up to grade 10 is left out of the card's steepest ratio, since a PSA 10 is its own market like a PSA 1. Grade 10 still counts towards the 2 grades and the spread, so a card whose only step ends at 10 stays unpriced.

### 17. Raw cards on PSA's table only (Zach, 2026-10-18)

**Add at the end of Grading companies:**
> A raw card is valued on PSA's table only: no PSA table means unpriced and excluded, with no fallback to another grader.

### 18. Grading cost of the day (Zach, 2026-10-18)

**Add at the end of Net of grading cost:**
> The grading cost of the day is an input to each valuation run, starting at a $25 fee, $5 shipping, $2 insurance per card and 2% time-to-cash.

### 19. Raw-sale floor (Zach, 2026-11-11; parameter set 1.8.0, the default; the quantile, sale-day minimum and MP/HP mapping are defaults to confirm)

Replaces the doc's "If the raw-sale price for that condition is higher, use it instead."

**Replace that sentence with:**
> **Raw-sale floor.** The raw-sale price is a floor: P_g = max(net graded, raw floor). Near-mint raw sales floor grades 7 to 10 (a likely 9 or 10 is certainly near mint, and an unfloored 9 below the floor would cap the 8 back down); lightly played floors 5 and 6. Moderately and heavily played floor nothing for now, grade 1 is never floored, and damaged raw sales still price U. The floor is the recency-weighted 25th percentile of the condition's raw sale days in the 90-day window, extended to 180 with fewer than 2 sale days, else no floor. It is never the average or an asking price, and a worse condition's floor is capped at a better one's. The floor needs no grading cost, but f, d and m still apply, and each grade stays at most the grade above. A floored grade is not derived: its confidence comes from the comps as usual. Graded cards get no floor. (The quantile, sale-day minimum and MP/HP mapping are defaults to confirm.)

### 20. Volatility fallback and cap (Zach, 2026-10-18; cap from deep review B4, 2026-11-09, parameter set 1.5.0, a default to confirm)

**Add at the end of Volatility:**
> With too few changes to measure it, a valuation uses a fallback σ of 20% a month and drops confidence one level. A measured σ above 1.0 is treated the same way (the cap is a default to confirm).

### 21. Refresh cadence: withdrawn

An earlier draft proposed replacing "Prices are refreshed nightly." The code matches the doc: comp ingestion runs nightly at 06:30 UTC from every enabled source, PokeTrace included (`pokemans.pricing.ingestion.cron`). It is off unless `PRICING_INGESTION_ENABLED` is set, which is a deployment setting, not a methodology rule. No edit.

## Step 4: The four values

### 22. Verified graded cards (Zach, 2026-10-18; no condition stress confirmed 2026-10-19)

**Add a paragraph before "Each value is reported with a confidence grade":**
> **Graded cards.** A card whose returned grade's cert is verified is valued as graded: certain at that grade (half grades fold down), on its own grader's table at gross prices, with no grading cost and no condition stress shift. A graded card is priced at its grade, whatever its condition. It counts in the graded share and is left out of the grade-first list.

## Step 5: Portfolio risk and advance rate

### 23. Eligibility (`CLAUDE.md` Pipeline 5; custody from Step 1)

The doc's Eligibility misses custody and lots.

**Replace Eligibility with:**
> **Eligibility.** Exclude cards with confidence grade D, unresolved authenticity flags, unverified custody, or FMV below $25. Lots are never valued.

### 24. Caps against the eligible FLV, at every size (Zach, 2026-10-19; replaces the 2026-10-18 final-base rule, which squeezed 10–15 card pledges down to their smallest card's FLV and had a cliff at 10 cards)

Contradicts the doc's Single-card cap, Top-five cap and Small collections bullets.

**Replace those three bullets with:**
> **Single-card cap.** No card counts for more than 20% of the eligible FLV before caps; value above the cap is excluded.
>
> **Top-five cap.** The five largest cards together count for no more than 50% of the eligible FLV before caps. Both caps work by capping every card at one level, at every collection size, with no minimum card count. So one card counts at most 20% of its FLV, and five or fewer at most 50%.

Keep "The haircut comes after the caps" in Correlation haircut. The advance formula stays: Advance = α · Σ min(FLV_i, cap_i).

### 25. Set and character slices (Zach, 2026-10-19)

**Add after "Hype cycles move these together.":**
> A set is language plus set name. A character is the Pokédex species in any language, form or owner (Charizard ex, Dark and Mega Charizard and リザードン are one slice), and a tag team counts toward its more popular partner.

## Step 6: Grade-first recommendations

### 26. FLV raw from gross prices (Zach, 2026-10-18)

**Add a bullet after "FLV graded = …":**
> FLV raw = the card's FLV computed from gross prices, so grading cost counts once, in C.

### 27. Doubted cards (`CLAUDE.md` Pipeline 6, no date)

**Add to the last bullet:**
> Doubted cards are left off.

## New Step 7: Reports

### 28. Reports (Zach, 2026-10-18 for who issues and downloads; the rest from `CLAUDE.md` Pipeline 7, no date)

**New section after Step 6:**
> A valuation run stores every input and the full trace, and the reports render from the run alone.
>
> **Lender report.** Collateral base, a suggested α range (the lender sets α; a lender-given α adds one line), caps and haircuts with reasons, exclusions with reasons, raw vs graded share, confidence, the grade-first top 20, and traceability.
>
> **Insurer report.** RV for every identified, priced card, with its photos as pre-loss proof. Counterfeit or suspected cards get RV $0 with the reason. Custody and confidence D don't exclude a card.
>
> Only the admin issues reports; members of the collection's org can download them.

## New Step 8: Instant offers

All of `CLAUDE.md` Pipeline 8. `docs/offers.md` holds the implementation detail; the doc needs the rules.

### 29. Offer formula and buyer margin (Zach, 2026-10-19; margin 2026-10-21, parameter set 1.2.0)

**Opening of the section:**
> A collector gets one firm price for a raw card:
>
> price = Σ_g p_g · offer_g, with offer_g = P↓_g · (1 − f) · (1 − d) · (1 − m)
>
> - P↓ = the grade's net price, capped at the grade above.
> - f and d = the fees and speed discount from QSV.
> - m = the buyer margin by the card's confidence: 10% at A, 13% at B, 17% at C, so a less certain condition leaves the buyer more room.

### 30. Offer confidence and derived grades (Zach, 2026-10-21)

**Add:**
> The confidence an offer is priced at leaves out the penalties that only bear on FLV: a fallback σ, and derived prices that only the stress reaches. It keeps the photo and comps base, a stale table, and a derived price where the central distribution puts probability. Derived (extrapolated) grades still price offers.

### 31. The 10 rung cap (Zach, 2026-11-09; parameter set 1.4.0; M = 2.5 is a default to confirm)

**Add:**
> The 10 rung is capped: offer_10 = min(offer_10, M · offer_9), both after the net price, the cap at the grade above, f, d and m, with M = 2.5 (`offers.maxTenToNineMultiple`, a default to confirm). This applies to offers only, so FMV, RV, QSV, FLV and the grade-first list keep the full ladder. A 9 rung of zero leaves the 10 alone, and the trace records when the cap binds.

### 32. Selling, delivery and doubts (Zach, 2026-10-19; self-buying and doubts 2026-11-02)

**Add:**
> The buyer keeps the upside of a 10, up to that cap, and carries the downside. No grade is judged on arrival, so there is no back-and-forth.
>
> Selling posts the offer to every buyer org; the first to accept buys it at the price, and posting is not a sale. Offers last 7 days, listings 14. On accept, a prepaid label insured for the price is bought on our EasyPost account and billed to the buyer monthly. The buyer pays the seller off-platform within 7 days of a card arriving as shown, or returns it only if it's not genuine, not the listed card, or doesn't match its photos.
>
> Nobody buys their own card: a buyer org sharing a member with the seller's org is refused, to keep the track record honest. Recording a doubt voids the card's live offers and takes it off the market; a card already taken stays a deal for the admin.

### 33. No offer, and the maximum (Zach, 2026-10-21; the $500 beta maximum lifted 2026-10-26)

**Add:**
> No offer for lots, doubted cards, confidence D, FMV under $25, or a price above the maximum, EasyPost's $5,000 insurance limit, so every label is insured for the full price (`pokemans.offers.max-price`; the $500 beta maximum of 2026-10-21 was lifted 2026-10-26 because too many cards were above it). Unverified high-risk cards get an offer subject to authentication in hand.

### 34. Repricing and the raw-comp flag (both from the 2026-11-09 deep review; 10% confirmed by Zach 2026-11-18; the flag is a default to confirm, not Zach's decision yet)

*Code check, attribution:* an earlier draft credited repricing to "Zach, 2026-10-21", reading `CLAUDE.md`'s closing attribution as covering it. The code says otherwise: `OffersProperties.repriceThreshold` cites "Deep review 2026-11-09, B3", and both were built in the commit "Offers: flag offers below raw sales, re-price at post and accept" (2026-11-09). Zach confirmed the 10% on 2026-11-18.

**Add:**
> At post and at accept, the offer's ladder is re-run on today's prices. A move of more than 10% either way (`pokemans.offers.reprice-threshold`) voids the offer by appending rows and refuses with "The price has changed. Get a new offer." (Deep review B3, 2026-11-09; 10% confirmed by Zach, 2026-11-18.)
>
> An offer below the card's lowest raw near-mint comp of the last 90 days is flagged to the admin and seller, never refused. (Default to confirm from the 2026-11-09 deep review; not Zach's decision yet.)

### 35. Admin undo (Zach, 2026-10-21)

**Add:**
> The admin undoes by appending, never editing. A voided offer comes off the market and can't be sold. A deal cancelled before arrival frees the card for a new offer; its label isn't billed to the buyer and can be marked for a refund. Each is a new row with who, when and why.

## Worked example, limitations and changes table

### 36. The advance line, plus two wording fixes

**A. The advance line no longer follows the caps (open since 2026-10-26; Zach chose option 1 on 2026-11-18).** The example says "A one-card collection is under 10 eligible cards, so the concentration caps don't apply and the advance is α × FLV" and gives $64. Under item 24 one card counts at most 20% of its FLV: $107 × 20% ≈ $21, an advance of about $13 at α = 60%. `CLAUDE.md`'s regression check keeps $64, and the golden test gets it by applying α to the card's FLV directly. Options:
1. Keep $64 and reword: "Advance on this card's FLV at α = 60%, before concentration caps: $64. Pledged alone, it would count for 20% of its FLV ($21), an advance of $13."
2. Change the example and the regression check to $13.

Option 1 changes no numbers or tests. Either way, delete the "under 10 eligible cards" sentence.

**B. Limitations.** With item 5 there is a verification path. **Replace "Authenticity is flagged, not verified. Counterfeits and resealed product need physical inspection." with:**
> Authenticity is flagged, and verified only by a slab, third-party authentication, an in-hand inspection, or (recent chase cards only) a photo review. Counterfeits and resealed product still need physical inspection.

**C. Changes table intro.** Rename the section "Changes since 2026-10-15" intro to cover 2026-10-17 to the apply date, list sources as `CLAUDE.md`, `docs/m3-progress.md`, `docs/review-2026-10-17.md`, `docs/offers.md`, `docs/poketrace-pilot.md` and the 2026-11-09 deep review, and add one row per item above.

## Rules in the code but in neither `CLAUDE.md` nor the doc

Found by the code check. Each one is built and running, but no written rule covers it. The code comments call most of these numbers starting guesses to calibrate against returned grades; they're labelled that way below. Zach confirmed on 2026-11-18 that all ten belong in the methodology.

### 37. Default spread of a provider grade (`ConditionProperties.DEFAULT_SPREAD`)

**Add to Step 2, before item 9's tighter spread:**
> **Spread.** Each provider's grade, half grades folded down, is spread over neighbouring grades, more below than above: +1 10%, 0 50%, −1 25%, −2 10%, −3 5%. Providers that answered are averaged. (Starting values, to calibrate against returned grades.)

*Also:* the code labels item 9's tighter spread weights "defaults to confirm" (`ConditionProperties`, `tightSpread`); `CLAUDE.md` didn't. Zach chose the code's label on 2026-11-18, so item 9 now carries it.

### 38. Cap on the probability of a 10 (review B2; `pokemans.condition.max-ten-probability` 0.25)

**Add to Step 2:**
> **Grade 10.** A system distribution puts at most 25% on 10; the rest moves down to 9. Raw cards that look perfect in photos grade 10 less often than the spread implies. Reviewer distributions and graded cards are exempt. (A starting guess, not calibrated.)

### 39. Floor on ungradable (review B2; `pokemans.condition.ungradable-floor` 0.02)

**Add to Step 2:**
> **Ungradable floor.** A system distribution puts at least 2% on U, taken proportionally from the other grades: a photo model can't see trimming or recolouring. Reviewer distributions and graded cards are exempt. (A starting guess, not calibrated.)

### 40. Confidence penalty sizes and the C trigger (`ConditionProperties`, `DistributionBuilder`)

`CLAUDE.md` says providers "more than a grade apart (B)" and partial photos "(C)" move probability down, without saying how much, and leaves out a second trigger for C.

**Add to item 8's paragraph:**
> Providers more than 1.5 grades apart, or any photo not showing the whole card, give C. At B, 10% of each grade's probability moves one grade down; at C, 25%. (Starting values.)

### 41. Retired provider (2026-11-10; `UnvaluedReason.PROVIDER_RETIRED`, `OfferRefusal.PROVIDER_RETIRED`)

**Add to item 12 (Retakes):**
> An assessment built partly on a grading provider we no longer use (Ximilar, from 2026-11-10) also waits to be assessed again; until then the card is unvalued and gets no offer.

### 42. How much the outlier trim removes (`pricing.outlierTrimFraction` 0.10)

**Add to Price per grade:**
> Trimming removes 10% of sales from each end.

### 43. Cards an era ratio needs (`pokemans.pricing.min-cards-per-ratio` 3)

**Add to "Sales only above":**
> An era step-down ratio needs at least 3 comparable cards. (A starting value, to set from real data.)

### 44. Comps confidence thresholds (`confidence.compsForA/B/C` 10, 5, 2)

The doc says confidence comes from "photo quality and comp counts" but gives no counts.

**Add to Step 4, after "Each value is reported with a confidence grade":**
> Comps confidence comes from the probability-weighted count of sales behind the grade prices: 10 or more for A, 5 for B, 2 for C, else D. A card's confidence is the worse of its comps and photo confidence, and any derived price in play drops it one level.

### 45. Thin grades and the modal grade (deep review B5, 2026-11-09; parameter set 1.3.0)

**Add after item 44:**
> A grade with fewer than 2 sale days even in the 180-day window is thin: its price is still used, but for confidence it counts as derived. It drops its own confidence one level, drops the card's one level where it carries probability, and is left out of the weighted count. Comps confidence is also no better than the sales behind the modal grade's price (for a derived grade, the thinnest grade it came from), so a deep neighbour can't hide a thin price where the card most likely grades.

### 46. How a character is found (Zach, 2026-10-19, detail from `CorrelationKeys`)

**Add to item 25:**
> The species comes from the catalog's Pokédex numbers where given, else from the card's name (the longest English or Japanese species name in it). A tag team's more popular partner comes from a fixed popularity list. A card with no species, such as a trainer, is keyed by its own name.

## Checked against the code

Each item checked against the backend at `796c4cc`. Matches = the code does what the item says, with the same numbers.

| # | Result | Where |
| --- | --- | --- |
| 1 | Matches: the lender and insurer APIs aren't registered unless `COLLATERAL_ENABLED` is on (off by default) | `pokemans.collateral.enabled` |
| 2–3 | Principles, not code | — |
| 4 | **Differs: lots can be recorded (wording corrected)** | `IntakeController`, `UnvaluedReason.LOT`, `OfferRefusal.LOT` |
| 5 | Matches | `Provenance`, `ProvenanceService` |
| 6 | Matches (90 days) | `ProvenanceController`, `pokemans.provenance.custody-check-valid-for` |
| 7 | Matches | `PriceSnapshotService` ("half grades fold down"), `Grade.foldHalf` |
| 8 | Matches ($1,000, set 1.6.0); CardGrade is off unless `CARDGRADE_ENABLED` | `DistributionBuilder`, parameter set |
| 9 | Matches; **code labels the weights defaults to confirm** (item 37) | `ConditionProperties` |
| 10 | Matches | `DistributionBuilder` |
| 11 | Matches, with reviewer override and reason; centering is off unless `CENTERING_ENABLED` | `ConditionController`, `CenteringPreGradeProvider` |
| 12 | Matches | `UnvaluedReason.PHOTOS_CHANGED`, `OfferRefusal.PHOTOS_CHANGED` |
| 13 | Matches, count never used, mapping override reason stored | `PokeTraceDays`, `PokeTraceMappings` |
| 14 | Matches; code also drops a day whose low isn't positive, and the 90 days are either side, across all sources | `PokeTraceDayGuard`, set 1.5.0 |
| 15 | Matches (2 days) | `PokeTraceRevisions`, `pull-gap-days`, set 1.7.0 |
| 16 | Matches | `pricing.cardLadder.excludeTen`, set 1.4.0 |
| 17 | Matches | `pokemans.valuation.grading-company: PSA` |
| 18 | Matches ($25, $5, $2, 2%; the admin can override them per run) | `pokemans.valuation.grading-cost` |
| 19 | Matches; a raw condition is the seller's listing label | `RawFloors`, set 1.8.0 |
| 20 | Matches (20%, 1.0) | `values.fallbackSigma`, `values.maxSigma` |
| 21 | **Withdrawn**: doc already right | `pokemans.pricing.ingestion.cron` |
| 22 | Matches | `ValuationEngine` (gross, no grading cost) |
| 23 | Matches | `PortfolioEvaluator.Card` (custody), `UnvaluedReason.LOT` |
| 24 | Matches; correlation haircut after the caps | `PortfolioEvaluator` |
| 25 | Matches; more detail in item 46 | `CorrelationKeys` |
| 26 | Matches | `GradeFirstRanker` |
| 27 | Matches | `ValuationEngine` |
| 28 | Matches: admin issues, org members download | `ReportController` |
| 29 | Matches (10 / 13 / 17%, set 1.2.0) | `OfferLadder`, parameter set |
| 30 | Not checked line by line; the confidence split is in `ValuationService` | — |
| 31 | Matches (2.5, trace records when it binds) | `OfferLadder.TenCap`, `OfferService` |
| 32 | Matches: 7 / 14 days, pay within 7 days of arrival, shared member refused, doubt voids live offers but not a taken one | `OffersProperties`, `ListingStateMachine`, `ListingService`, `OfferAdminService` |
| 33 | Matches ($5,000, subject to authentication) | `pokemans.offers.max-price`, `CardOffer.subjectToAuthentication` |
| 34 | Matches (10%, 90 days); **attribution corrected** | `OffersProperties`, `OfferService.RAW_SALES_DAYS` |
| 35 | Matches | `OfferAdminService`, `ShippingBilling` |
| 36A | Confirmed: the golden test applies α to the card's FLV without caps | `WorkedExampleGoldenTest` line 141 |

Not checked: the web app, and whether production has CardGrade, centering, PokeTrace and ingestion switched on.

## Left in `CLAUDE.md` on purpose

Not methodology rules, so they stay in `CLAUDE.md` and need no doc edit: product name, working-here notes, compact instructions, the regression-check numbers, validation targets (already in the doc's Validation plan), and the constraints (licensable price data, checking pre-grading API terms, personal accounts only).

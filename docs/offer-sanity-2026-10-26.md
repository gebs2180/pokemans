# Offer sanity check, 2026-10-26

Before the vendor spot check (show ~20 offers to card vendors, then tune the buyer margin), every card in `comps.csv` went through the real offer engine. Nothing in pricing was changed. Every finding below is for Zach to decide.

## How it was run

- **Runner:** `backend/app/src/test/java/com/pokemans/app/offers/OfferSanityRunner.java`. It's a Testcontainers flow test that asserts nothing and only runs when `POKEMANS_OFFER_SANITY_CSV` names a comps CSV:
  ```
  cd backend; $env:POKEMANS_OFFER_SANITY_CSV="C:\Users\chief\Pictures\pokemans-test-cards\comps.csv"; .\gradlew.bat :app:test --tests "*OfferSanityRunner" --rerun
  ```
  It writes the table to `backend/app/build/reports/offer-sanity.md`. Run it on its own: the refresh prices every card in the database, so another test class's comps would change the era ratios.
- **Path through the code:**
  1. Each of the 5 cards is identified through the admin API.
  2. Each of the 42 comps is posted to `/api/admin/catalog-cards/{id}/comps`, the same call the comp form's CSV import makes, with the same columns.
  3. One `/prices/refresh` runs `PriceSnapshotService`.
  4. Each card is assessed by a reviewer at each distribution and photo confidence, then quoted with `OfferService.quote`. That's the code behind the admin preview and the seller's offer, built on `Valuations.current` and `OfferLadder`.
  5. For refused rows, the runner also runs the same `OfferLadder` at each margin. That shows what the price would have been. This is reporting only; the engine wasn't changed.
- **Distributions:** `c10` = 9: 0.3, 10: 0.7. `c9` = 8: 0.2, 9: 0.6, 10: 0.2. `c8`, `c7` and `c6` use the same 0.2/0.6/0.2 shape, one grade lower each time. `wide5-10` = 5: 0.10, 6: 0.15, 7: 0.20, 8: 0.25, 9: 0.20, 10: 0.10. Each one ran at photo confidence A, B and C, for 90 quotes.
- **Data:** `comps.csv` (outside the repo, in `Pictures/pokemans-test-cards`) has 42 PSA sales from 2026-08-12 to 2026-10-20. Every card has 2–3 sales at each of PSA 8, 9 and 10. There are **no raw sales and nothing below PSA 8.** Most findings come from that.

## Headline

**Of 90 quotes, 3 made an offer:** Pikachu VMAX c10 $123.54, Pikachu VMAX c9 $92.28 and Lugia V c9 $306.06, the same at every photo confidence. Of the other 87:

- **60 are confidence D.** Any probability on a grade below 8 lands on a derived price, and that drops C to D.
- **27 are over the $500 maximum.**

Every offer that was made is priced at C (17%), so **the A and B margins were never used.** As it stands, the vendor spot check can't test 10/13/17% with this data. Findings 1–3 explain why.

## Flagged rows and their causes

### 1. Photo confidence never matters: every card tops out at C (all 90 rows)

| | |
|---|---|
| Rows | Every card and distribution: A, B and C photo confidence give the same confidence and the same price. |
| Cause | The offer confidence is the photo confidence or the comps confidence, whichever is worse (CLAUDE.md step 8: "It keeps the photo and comps base"; step 4: "Confidence A–D from photo quality and comp counts"). Comps confidence comes from the probability-weighted count of observed sales: 10 for A, 5 for B and 2 for C (parameter set 1.2.0, `confidence.compsFor*`). With 3 sales per grade the weighted count is at most 3.00, so the comps confidence is C at best. |
| Vendor view | Not a wrong price, but a sharp photo earns the seller nothing. The 10%/13% margins are untested. |
| Inversions | **None.** No higher-confidence card got a lower price than a lower-confidence one with the same distribution. The margin depends only on the offer confidence, and that never differed between rows with the same distribution. With the margin fixed, the ladder at m(A) / m(B) / m(C) always comes out A > B > C. |

### 2. Refusals that look wrong: 8 sales and no offer because 20% sits on a 7 (c8 on every card)

| | |
|---|---|
| Rows | Charizard c8 (8 sales, $890 before the refusal, of which 2% comes from the derived grade 7); Umbreon c8 ($912, 12% derived); Giratina c8 ($338); Lugia c8 ($172); Pikachu c8 ($51). All c7, c6 and wide rows too. |
| Cause | CLAUDE.md step 8: the offer confidence keeps "a derived price where the central distribution puts probability", and step 3: "Any derived price → confidence drops one level". Base C − 1 = D, and D gets no offer (step 8, docs/offers.md "confidence D"). The drop applies whatever the derived grade's weight: Charizard c8 is refused over a grade that carries 2% of its price. For c7 and c6 the comps base is D on its own as well (weighted sales 0.6 and 0). |
| Vendor view | A seller with a clean-looking 8 gets "There isn't enough sales data" on a card with 8–9 recent graded sales. The cliff from offer to no offer between c9 and c8 is the biggest jump in the table. |

### 3. Most of the rest is over the $500 maximum (27 rows)

| | |
|---|---|
| Rows | Charizard c10 and c9; Umbreon c10 and c9; Giratina c10 and c9; Lugia c10. |
| Cause | CLAUDE.md step 8 and docs/offers.md: "a firm price above the maximum, $500 by default (`pokemans.offers.max-price`)". |
| Vendor view | Works as designed. Four of the five cards in comps.csv are worth more than $500 at a 9, so the sample barely covers the beta's price range. |

### 4. Offers above the card's own lowest recent PSA 9 sale (6 rows, all currently stopped by the $500 cap)

| Card, dist | Engine price (C) | Lowest PSA 9 sale | Why |
|---|---|---|---|
| Charizard c10 | $11,735.57 | $3,451.01 | 70% at a $28,750 PSA 10 |
| Charizard c9 | $4,564.47 | $3,451.01 | the 20% on 10 adds $3,177 |
| Giratina c10 | $1,291.16 | $686.14 | |
| Giratina c9 | $687.40 | $686.14 | the PSA 10 sells at 3.4× the 9 |
| Lugia c10 | $515.14 | $412.00 | |
| Umbreon c10 | $1,750.13 | $1,875.00 | under, but close |

- **Cause:** step 8, `price = Σ p_g · offer_g`, and "The buyer keeps the upside of a 10 and carries the downside". When a 10 sells at a large premium (Charizard 7.7× the 9, Giratina 3.4×), even a 20% chance of a 10 lifts a raw offer above a slabbed 9.
- **Vendor view:** no vendor pays more for a raw card than for the same card already in a PSA 9 slab. A raw card still has to be graded, waited on and risked. The engine is doing what the formula says, but this is the first thing a vendor would object to.
- **Today:** the $500 cap hides all six. They come back if the cap goes up.
- **For Zach:** options include capping the firm price at the lowest recent PSA 9 (or at its net), or haircutting the 10 rung. Not changed here.

### 5. Below the card's lowest raw sale: can't be checked; low grades price at about $0

- comps.csv has no raw sales, so no row could be compared with one.
- The related problem shows in the ladders. Each grade's price is net of $25 + $5 + $2 and 2%, so grades 5–6 net to almost nothing:

  | Card | PSA 6 net | PSA 5 net |
  |---|---|---|
  | Pikachu VMAX | $7.75 | $0 |
  | Giratina V | $29.00 | $0 |
  | Lugia V | $23.11 | $0 |
  | Charizard | $0 | $0 |

  U is $0 because there are no damaged sales and no bulk floor.
- **Cause:** CLAUDE.md step 3: "Each grade's price is **net** of grading fee, shipping, insurance and a time-to-cash discount, but use the raw-sale price for that condition if it's higher". With no raw comps, nothing triggers that "if higher" fallback. Also "U priced from damaged/altered raw sales, else $0. No bulk floor".
- **Vendor view:** a played Pikachu VMAX rainbow still sells raw for real money, so a ladder rung of $0 at 5 looks wrong. That only holds until raw comps exist; then the raw price takes over.

### 6. Extrapolated grades carry most of the price, priced from a 10 premium (c7, c6 and wide rows)

| Card | Card-step ratio | PSA 8 (observed) | PSA 7 (derived) | Observed PSA 8/9 ratio |
|---|---|---|---|---|
| Charizard | 0.130 | $1,425 | $186 | 0.38 |
| Giratina V | 0.297 | $707 | $210 | 0.79 |
| Lugia V | 0.390 | $370 | $144 | 0.80 |
| Pikachu VMAX | 0.588 | $117.50 | $69 | 0.59 |
| Umbreon VMAX | 0.547 | $1,800 | $985 | 0.89 |

- Derived grades carry 45–66% of the price at c7 and 100% at c6. The ratio comes from the 9-to-10 step on every card except Pikachu.
- **Cause:** CLAUDE.md step 3: "Sales only above → higher grade × era step-down ratio … With no era ratio yet, use the card's own steepest per-grade ratio". The era ratios need 3 cards with both adjacent grades observed (`min-cards-per-ratio: 3`). Nobody has a 7, so every grade at 7 or below uses the card's own **steepest** ratio. For most cards the steepest step is 9→10, the PSA 10 scarcity premium, and the engine repeats it all the way down.
  - Charizard PSA 7 comes out at 13% of its PSA 8.
  - Giratina PSA 7 comes out at 30% of its PSA 8, when its own 8-to-9 ratio is 0.79.
- **Vendor view:** these are conservative, as the rule intends, but far enough off that a vendor would call the c7 offers lowball (Charizard c7 $205, Giratina c7 $137). Today D hides them. They show up once more comps lift these cards to C.
- **For Zach:** options include:
  - excluding the 9→10 step from the "steepest ratio" choice (PSA 10s are their own market, like PSA 1s);
  - getting 7s into the comps so era ratios exist.

### 7. Big jumps between adjacent distributions

Engine price at C, one grade of centre at a time (✗ = refused today):

| Card | c10 | c9 | c8 | c7 | c6 |
|---|---|---|---|---|---|
| Charizard | 11,735.57 ✗ | 4,564.47 ✗ (−61%) | 890.24 ✗ (**−80%**) | 204.87 ✗ (−77%) | 16.95 ✗ (−92%) |
| Umbreon VMAX | 1,750.13 ✗ | 1,262.28 ✗ (−28%) | 912.32 ✗ (−28%) | 567.75 ✗ (−38%) | 302.64 ✗ (−47%) |
| Giratina V | 1,291.16 ✗ | 687.40 ✗ (−47%) | 338.28 ✗ (−51%) | 136.67 ✗ (−60%) | 29.42 ✗ (−79%) |
| Lugia V | 515.14 ✗ | **306.06** (−41%) | 171.57 ✗ (−44%) | 76.96 ✗ (−55%) | 20.17 ✗ (−74%) |
| Pikachu VMAX | **123.54** | **92.28** (−25%) | 50.69 ✗ (−45%) | 22.33 ✗ (−56%) | 6.65 ✗ (−70%) |

Causes, in order of size:

1. **The offer/no-offer cliff between c9 and c8** (finding 2).
2. **Charizard's c9→c8 drop of 80%.** The PSA 10 premium is in play at c9 and gone at c8 (finding 4).
3. **Steeper drops each grade down.** Card-step ratios and the fixed $32 grading cost compound toward the bottom (findings 5 and 6).

Umbreon's even −28% steps come from a flat 8–9 ladder ($1,800 vs $2,025). That's what a sane ladder looks like.

### 8. The speed discount is at its maximum on every card

- comps.csv gives each card 2.67–3.00 sales a month, so every card is LOW liquidity and gets d = 20%.
- The offer factor at C is 0.85 × 0.80 × 0.83 = **0.564** of net. These cards sell far more than 3 a month. The CSV is a 9-sale sample, not the market.
- **Cause:** CLAUDE.md step 4: "d = 5% (>20 sales/mo), 10% (5–20), 20% (<5)". The sales rate is counted from the comps stored for the card (`PriceSnapshotService.salesPerMonth`).
- **Why it matters for the margin:** at HIGH liquidity the factor would be 0.85 × 0.95 × 0.83 = 0.670, **19% higher**. Moving m from C to A is only 0.90 / 0.83, **8% higher**. If vendors call the offers low, the sales rate is the first thing to fix, before the margin.

### Other notes

- **Charizard Base Set's PSA 10 rests on 2 sales.** They are $28,750 (Heritage, 2026-10-15) and $40,401 (eBay, 2026-10-11), and the recency-weighted median takes $28,750.
- **Charizard is a vintage holo, so it's high authenticity risk.** Its offer would come "subject to authentication in hand". `quote` doesn't show that.
- **The reviewer distributions here are taken as given.** A real collector's photo-model distribution for a modern holo gets the default downward surface shift (step 2), so the c9 and c10 rows on modern cards are on the generous side.
- **Pikachu c6 has an FMV of $11.78**, so it would also be refused under the $25 minimum.

## 20 offers to show vendors

Show these as: "raw card, we think it's most likely a PSA X, firm price". For refused rows, the price is the engine's figure at the margin the card would get (17%, C), so vendors react to the formula rather than to a refusal.

- **Live** = a seller would get this offer today.
- **D** = refused for confidence D today.
- **>$500** = refused over the cap today.

| # | Card | Era | Likely grade (dist) | Firm price | Status today | FMV | Lowest PSA 9 / 8 sale |
|---|---|---|---|---|---|---|---|
| 1 | Pikachu VMAX 188/185 | modern | 10 (c10) | $123.54 | Live | $218.88 | $190 / $117.50 |
| 2 | Pikachu VMAX 188/185 | modern | 9 (c9) | $92.28 | Live | $163.51 | $190 / $117.50 |
| 3 | Pikachu VMAX 188/185 | modern | 8 (c8) | $50.69 | D | $89.82 | $190 / $117.50 |
| 4 | Pikachu VMAX 188/185 | modern | 7 (c7) | $22.33 | D | $39.57 | $190 / $117.50 |
| 5 | Pikachu VMAX 188/185 | modern | anywhere 5–10 | $48.60 | D | $86.12 | $190 / $117.50 |
| 6 | Lugia V 186/195 | modern | 10 (c10) | $515.14 | >$500 | $912.71 | $412 / $370 |
| 7 | Lugia V 186/195 | modern | 9 (c9) | $306.06 | Live | $542.28 | $412 / $370 |
| 8 | Lugia V 186/195 | modern | 8 (c8) | $171.57 | D | $303.99 | $412 / $370 |
| 9 | Lugia V 186/195 | modern | 7 (c7) | $76.96 | D | $136.36 | $412 / $370 |
| 10 | Lugia V 186/195 | modern | anywhere 5–10 | $171.68 | D | $304.19 | $412 / $370 |
| 11 | Giratina V 186/196 | modern | 9 (c9) | $687.40 | >$500 | $1,217.92 | $686.14 / $698 |
| 12 | Giratina V 186/196 | modern | 8 (c8) | $338.28 | D | $599.36 | $686.14 / $698 |
| 13 | Giratina V 186/196 | modern | 7 (c7) | $136.67 | D | $242.16 | $686.14 / $698 |
| 14 | Umbreon VMAX 215/203 | modern | 9 (c9) | $1,262.28 | >$500 | $2,236.50 | $1,875 / $1,775 |
| 15 | Umbreon VMAX 215/203 | modern | 8 (c8) | $912.32 | D | $1,616.44 | $1,875 / $1,775 |
| 16 | Umbreon VMAX 215/203 | modern | 7 (c7) | $567.75 | D | $1,005.95 | $1,875 / $1,775 |
| 17 | Umbreon VMAX 215/203 | modern | anywhere 5–10 | $829.54 | D | $1,469.78 | $1,875 / $1,775 |
| 18 | Charizard Base 4/102 | vintage | 9 (c9) | $4,564.47 | >$500 | $8,087.30 | $3,451.01 / $1,300 |
| 19 | Charizard Base 4/102 | vintage | 8 (c8) | $890.24 | D | $1,577.33 | $3,451.01 / $1,300 |
| 20 | Charizard Base 4/102 | vintage | 7 (c7) | $204.87 | D | $362.99 | $3,451.01 / $1,300 |

Coverage:

- **Price range:** $22 to $4,564.
- **Vintage is thin.** It's one card, because comps.csv has one vintage card. Before the meeting, add comps for 2–3 more vintage cards around $50–$500, such as Base Set Blastoise or Venusaur, or Neo holos. Those are where beta offers will actually land.
- **Cheap cards are only Pikachu VMAX.** Add a $25–$75 modern card.

Expected objections, which is useful in itself:

- **#18:** a raw offer above a PSA 9 sale (finding 4).
- **#20 and #13:** lowball, because of the 10-premium step-down (finding 6).
- **#3 and #12:** "you'd refuse this?" (finding 2).

Best practice for the meeting: **ask each vendor for their own buy price first**, then show ours. Their price divided by the price at m = 0 gives the margin they actually work at.

## Is 10 / 13 / 17% right?

**Recommendation: keep 10/13/17 for the spot check, and don't tune it from this data.** Reasons:

1. **The data can only test 17%.** No card reaches B or A with 3 sales per grade (finding 1). Tuning the A and B margins needs cards with at least 5–10 weighted sales.
2. **Margin isn't the biggest lever.** On the three live offers the seller gets about **56% of FMV**: Pikachu c9 92.28 / 163.51, Lugia c9 306.06 / 542.28. FMV is already net of grading cost. The total haircut on the net ladder is 44%, and m = 17% is less than half of it. The rest is fees (15%) and the speed discount (20%, overstated here; finding 8). Fixing the sales rate moves prices 19%; moving m all the way from C to A moves them 8%.
3. **The live offers aren't out of line.**
   - Lugia c9 at $306 is 74% of the lowest PSA 9 sale and 83% of a PSA 8.
   - Pikachu c9 at $92 is 49% of the lowest PSA 9.
   - For a firm, no-recourse price on an ungraded card, both are inside the range a vendor might quote. The spot check is there to confirm that.
4. **The spread is narrow.** A to C is 0.90 vs 0.83, an 8% difference in price, while C carries much more grade risk than A. If vendors widen their price for less certain cards, the C margin may need to go up (20%+), not down. That's a question to ask in the spot check, not something to change now.

**Before the spot check, decide (Zach):**

- (a) whether to keep the derived-grade drop for offers, or limit it to grades with real weight (finding 2);
- (b) whether to cap the firm price at the card's lowest recent PSA 9 (finding 4);
- (c) whether the card-ladder fallback should skip the 9→10 step (finding 6);
- (d) to load enough comps that the sales rate reflects the market (finding 8).

None of these were changed here.

## Appendix: full table

Output of `OfferSanityRunner` (2026-10-26). Columns:

- **Offer conf:** the confidence the offer is priced at, with the trace's reason.
- **Engine price before refusal:** the `OfferLadder` price at that confidence's margin, at C for a D card.
- **Price at m(A/B/C):** the same ladder at each margin.
- **Derived share:** the part of the price carried by derived grades.
- **Ladder:** offer per grade from 10 down to 5, with the price method.


| Card | Dist | Photo conf | Offer conf | Firm price | Refusal | FMV | Engine price before refusal | Price at m(A/B/C) | Derived share | Lowest PSA 9 | Ladder 10..5 (method) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Charizard | c10 | A | C (photo A, comps C (weighted sales 2.30)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 20793.00 | 11735.57 | 12725.32 / 12301.14 / 11735.57 | 0% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c10 | B | C (photo B, comps C (weighted sales 2.30)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 20793.00 | 11735.57 | 12725.32 / 12301.14 / 11735.57 | 0% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c10 | C | C (photo C, comps C (weighted sales 2.30)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 20793.00 | 11735.57 | 12725.32 / 12301.14 / 11735.57 | 0% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c9 | A | C (photo A, comps C (weighted sales 2.80)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 8087.30 | 4564.47 | 4949.43 / 4784.45 / 4564.47 | 0% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c9 | B | C (photo B, comps C (weighted sales 2.80)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 8087.30 | 4564.47 | 4949.43 / 4784.45 / 4564.47 | 0% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c9 | C | C (photo C, comps C (weighted sales 2.80)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 8087.30 | 4564.47 | 4949.43 / 4784.45 / 4564.47 | 0% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c8 | A | D (photo A, comps C (weighted sales 2.40); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1577.33 | 890.24 | 965.32 / 933.15 / 890.24 | 2% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c8 | B | D (photo B, comps C (weighted sales 2.40); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1577.33 | 890.24 | 965.32 / 933.15 / 890.24 | 2% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c8 | C | D (photo C, comps C (weighted sales 2.40); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1577.33 | 890.24 | 965.32 / 933.15 / 890.24 | 2% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c7 | A | D (photo A, comps D (weighted sales 0.60); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 362.99 | 204.87 | 222.15 / 214.75 / 204.87 | 25% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c7 | B | D (photo B, comps D (weighted sales 0.60); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 362.99 | 204.87 | 222.15 / 214.75 / 204.87 | 25% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c7 | C | D (photo C, comps D (weighted sales 0.60); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 362.99 | 204.87 | 222.15 / 214.75 / 204.87 | 25% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c6 | A | D (photo A, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 30.03 | 16.95 | 18.38 / 17.77 / 16.95 | 100% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c6 | B | D (photo B, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 30.03 | 16.95 | 18.38 / 17.77 / 16.95 | 100% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | c6 | C | D (photo C, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 30.03 | 16.95 | 18.38 / 17.77 / 16.95 | 100% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | wide5-10 | A | D (photo A, comps D (weighted sales 1.55); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 3914.06 | 2209.09 | 2395.40 / 2315.56 / 2209.09 | 1% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | wide5-10 | B | D (photo B, comps D (weighted sales 1.55); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 3914.06 | 2209.09 | 2395.40 / 2315.56 / 2209.09 | 1% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Charizard | wide5-10 | C | D (photo C, comps D (weighted sales 1.55); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 3914.06 | 2209.09 | 2395.40 / 2315.56 / 2209.09 | 1% | 3451.01 | 10:15884(obs) 9:2056(obs) 8:770(obs) 7:85(card-step) 6:0(card-step) 5:0(card-step) |
| Umbreon VMAX | c10 | A | C (photo A, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 3100.86 | 1750.13 | 1897.73 / 1834.47 / 1750.13 | 0% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c10 | B | C (photo B, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 3100.86 | 1750.13 | 1897.73 / 1834.47 / 1750.13 | 0% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c10 | C | C (photo C, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 3100.86 | 1750.13 | 1897.73 / 1834.47 / 1750.13 | 0% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c9 | A | C (photo A, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 2236.50 | 1262.28 | 1368.74 / 1323.12 / 1262.28 | 0% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c9 | B | C (photo B, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 2236.50 | 1262.28 | 1368.74 / 1323.12 / 1262.28 | 0% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c9 | C | C (photo C, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 2236.50 | 1262.28 | 1368.74 / 1323.12 / 1262.28 | 0% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c8 | A | D (photo A, comps C (weighted sales 2.40); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1616.44 | 912.32 | 989.26 / 956.28 / 912.32 | 12% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c8 | B | D (photo B, comps C (weighted sales 2.40); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1616.44 | 912.32 | 989.26 / 956.28 / 912.32 | 12% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c8 | C | D (photo C, comps C (weighted sales 2.40); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1616.44 | 912.32 | 989.26 / 956.28 / 912.32 | 12% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c7 | A | D (photo A, comps D (weighted sales 0.60); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1005.95 | 567.75 | 615.64 / 595.12 / 567.75 | 66% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c7 | B | D (photo B, comps D (weighted sales 0.60); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1005.95 | 567.75 | 615.64 / 595.12 / 567.75 | 66% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c7 | C | D (photo C, comps D (weighted sales 0.60); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1005.95 | 567.75 | 615.64 / 595.12 / 567.75 | 66% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c6 | A | D (photo A, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 536.22 | 302.64 | 328.17 / 317.22 / 302.64 | 100% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c6 | B | D (photo B, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 536.22 | 302.64 | 328.17 / 317.22 / 302.64 | 100% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | c6 | C | D (photo C, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 536.22 | 302.64 | 328.17 / 317.22 / 302.64 | 100% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | wide5-10 | A | D (photo A, comps D (weighted sales 1.65); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1469.78 | 829.54 | 899.50 / 869.52 / 829.54 | 20% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | wide5-10 | B | D (photo B, comps D (weighted sales 1.65); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1469.78 | 829.54 | 899.50 / 869.52 / 829.54 | 20% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Umbreon VMAX | wide5-10 | C | D (photo C, comps D (weighted sales 1.65); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 1469.78 | 829.54 | 899.50 / 869.52 / 829.54 | 20% | 1875.0 | 10:2028(obs) 9:1102(obs) 8:978(obs) 7:527(card-step) 6:280(card-step) 5:145(card-step) |
| Pikachu VMAX | c10 | A | C (photo A, comps C (weighted sales 3.00)) | 123.54 |  | 218.88 | 123.54 | 133.96 / 129.49 / 123.54 | 0% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c10 | B | C (photo B, comps C (weighted sales 3.00)) | 123.54 |  | 218.88 | 123.54 | 133.96 / 129.49 / 123.54 | 0% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c10 | C | C (photo C, comps C (weighted sales 3.00)) | 123.54 |  | 218.88 | 123.54 | 133.96 / 129.49 / 123.54 | 0% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c9 | A | C (photo A, comps C (weighted sales 2.80)) | 92.28 |  | 163.51 | 92.28 | 100.07 / 96.73 / 92.28 | 0% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c9 | B | C (photo B, comps C (weighted sales 2.80)) | 92.28 |  | 163.51 | 92.28 | 100.07 / 96.73 / 92.28 | 0% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c9 | C | C (photo C, comps C (weighted sales 2.80)) | 92.28 |  | 163.51 | 92.28 | 100.07 / 96.73 / 92.28 | 0% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c8 | A | D (photo A, comps D (weighted sales 1.80); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 89.82 | 50.69 | 54.97 / 53.14 / 50.69 | 8% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c8 | B | D (photo B, comps D (weighted sales 1.80); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 89.82 | 50.69 | 54.97 / 53.14 / 50.69 | 8% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c8 | C | D (photo C, comps D (weighted sales 1.80); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 89.82 | 50.69 | 54.97 / 53.14 / 50.69 | 8% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c7 | A | D (photo A, comps D (weighted sales 0.40); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 39.57 | 22.33 | 24.22 / 23.41 / 22.33 | 58% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c7 | B | D (photo B, comps D (weighted sales 0.40); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 39.57 | 22.33 | 24.22 / 23.41 / 22.33 | 58% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c7 | C | D (photo C, comps D (weighted sales 0.40); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 39.57 | 22.33 | 24.22 / 23.41 / 22.33 | 58% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c6 | A | D (photo A, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 11.78 | 6.65 | 7.21 / 6.97 / 6.65 | 100% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c6 | B | D (photo B, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 11.78 | 6.65 | 7.21 / 6.97 / 6.65 | 100% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | c6 | C | D (photo C, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 11.78 | 6.65 | 7.21 / 6.97 / 6.65 | 100% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | wide5-10 | A | D (photo A, comps D (weighted sales 1.40); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 86.12 | 48.60 | 52.71 / 50.95 / 48.60 | 10% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | wide5-10 | B | D (photo B, comps D (weighted sales 1.40); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 86.12 | 48.60 | 52.71 / 50.95 / 48.60 | 10% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Pikachu VMAX | wide5-10 | C | D (photo C, comps D (weighted sales 1.40); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 86.12 | 48.60 | 52.71 / 50.95 / 48.60 | 10% | 190.0 | 10:137(obs) 9:93(obs) 8:47(obs) 7:20(card-step) 6:4(card-step) 5:0(card-step) |
| Giratina V | c10 | A | C (photo A, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 2287.66 | 1291.16 | 1400.05 / 1353.38 / 1291.16 | 0% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c10 | B | C (photo B, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 2287.66 | 1291.16 | 1400.05 / 1353.38 / 1291.16 | 0% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c10 | C | C (photo C, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 2287.66 | 1291.16 | 1400.05 / 1353.38 / 1291.16 | 0% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c9 | A | C (photo A, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 1217.92 | 687.40 | 745.37 / 720.52 / 687.40 | 0% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c9 | B | C (photo B, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 1217.92 | 687.40 | 745.37 / 720.52 / 687.40 | 0% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c9 | C | C (photo C, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 1217.92 | 687.40 | 745.37 / 720.52 / 687.40 | 0% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c8 | A | D (photo A, comps C (weighted sales 2.40); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 599.36 | 338.28 | 366.81 / 354.58 / 338.28 | 6% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c8 | B | D (photo B, comps C (weighted sales 2.40); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 599.36 | 338.28 | 366.81 / 354.58 / 338.28 | 6% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c8 | C | D (photo C, comps C (weighted sales 2.40); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 599.36 | 338.28 | 366.81 / 354.58 / 338.28 | 6% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c7 | A | D (photo A, comps D (weighted sales 0.60); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 242.16 | 136.67 | 148.20 / 143.26 / 136.67 | 45% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c7 | B | D (photo B, comps D (weighted sales 0.60); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 242.16 | 136.67 | 148.20 / 143.26 / 136.67 | 45% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c7 | C | D (photo C, comps D (weighted sales 0.60); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 242.16 | 136.67 | 148.20 / 143.26 / 136.67 | 45% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c6 | A | D (photo A, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 52.12 | 29.42 | 31.90 / 30.84 / 29.42 | 100% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c6 | B | D (photo B, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 52.12 | 29.42 | 31.90 / 30.84 / 29.42 | 100% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | c6 | C | D (photo C, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 52.12 | 29.42 | 31.90 / 30.84 / 29.42 | 100% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | wide5-10 | A | D (photo A, comps D (weighted sales 1.65); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 663.16 | 374.29 | 405.86 / 392.33 / 374.29 | 6% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | wide5-10 | B | D (photo B, comps D (weighted sales 1.65); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 663.16 | 374.29 | 405.86 / 392.33 / 374.29 | 6% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Giratina V | wide5-10 | C | D (photo C, comps D (weighted sales 1.65); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 663.16 | 374.29 | 405.86 / 392.33 / 374.29 | 6% | 686.14 | 10:1641(obs) 9:474(obs) 8:373(obs) 7:98(card-step) 6:16(card-step) 5:0(card-step) |
| Lugia V | c10 | A | C (photo A, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 912.71 | 515.14 | 558.58 / 539.96 / 515.14 | 0% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c10 | B | C (photo B, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 912.71 | 515.14 | 558.58 / 539.96 / 515.14 | 0% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c10 | C | C (photo C, comps C (weighted sales 3.00)) | — | Instant offers go up to $500 for now, and this card is worth more than that, so it gets no offer. | 912.71 | 515.14 | 558.58 / 539.96 / 515.14 | 0% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c9 | A | C (photo A, comps C (weighted sales 2.80)) | 306.06 |  | 542.28 | 306.06 | 331.88 / 320.81 / 306.06 | 0% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c9 | B | C (photo B, comps C (weighted sales 2.80)) | 306.06 |  | 542.28 | 306.06 | 331.88 / 320.81 / 306.06 | 0% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c9 | C | C (photo C, comps C (weighted sales 2.80)) | 306.06 |  | 542.28 | 306.06 | 331.88 / 320.81 / 306.06 | 0% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c8 | A | D (photo A, comps D (weighted sales 1.80); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 303.99 | 171.57 | 186.05 / 179.84 / 171.57 | 7% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c8 | B | D (photo B, comps D (weighted sales 1.80); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 303.99 | 171.57 | 186.05 / 179.84 / 171.57 | 7% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c8 | C | D (photo C, comps D (weighted sales 1.80); derived grades 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 303.99 | 171.57 | 186.05 / 179.84 / 171.57 | 7% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c7 | A | D (photo A, comps D (weighted sales 0.40); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 136.36 | 76.96 | 83.45 / 80.67 / 76.96 | 52% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c7 | B | D (photo B, comps D (weighted sales 0.40); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 136.36 | 76.96 | 83.45 / 80.67 / 76.96 | 52% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c7 | C | D (photo C, comps D (weighted sales 0.40); derived grades 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 136.36 | 76.96 | 83.45 / 80.67 / 76.96 | 52% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c6 | A | D (photo A, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 35.74 | 20.17 | 21.87 / 21.14 / 20.17 | 100% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c6 | B | D (photo B, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 35.74 | 20.17 | 21.87 / 21.14 / 20.17 | 100% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | c6 | C | D (photo C, comps D (weighted sales 0.00); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 35.74 | 20.17 | 21.87 / 21.14 / 20.17 | 100% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | wide5-10 | A | D (photo A, comps D (weighted sales 1.40); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 304.19 | 171.68 | 186.16 / 179.96 / 171.68 | 8% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | wide5-10 | B | D (photo B, comps D (weighted sales 1.40); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 304.19 | 171.68 | 186.16 / 179.96 / 171.68 | 8% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |
| Lugia V | wide5-10 | C | D (photo C, comps D (weighted sales 1.40); derived grades 5, 6, 7) | — | There isn't enough sales data to make a firm offer on this card yet. | 304.19 | 171.68 | 186.16 / 179.96 / 171.68 | 8% | 412.0 | 10:635(obs) 9:236(obs) 8:187(obs) 7:62(card-step) 6:13(card-step) 5:0(card-step) |

### Price tables (gross, net of the default grading cost, method)

**Charizard 4/102** (2.67 sales/mo, sigma null)

| Grade | Gross | Net | Method | n | Basis | Ratio |
|---|---|---|---|---|---|---|
| 10 | 28750.00 | 28143.00 | OBSERVED | 2 | [] |  |
| 9 | 3750.00 | 3643.00 | OBSERVED | 3 | [] |  |
| 8 | 1425.00 | 1364.50 | OBSERVED | 3 | [] |  |
| 7 | 185.87 | 150.15 | CARD_STEP_DOWN | 0 | [8] | 0.130 |
| 6 | 24.24 | 0.00 | CARD_STEP_DOWN | 0 | [7] | 0.130 |
| 5 | 3.16 | 0.00 | CARD_STEP_DOWN | 0 | [6] | 0.130 |
| 4 | 0.41 | 0.00 | CARD_STEP_DOWN | 0 | [5] | 0.130 |
| 3 | 0.05 | 0.00 | CARD_STEP_DOWN | 0 | [4] | 0.130 |
| 2 | 0.01 | 0.00 | CARD_STEP_DOWN | 0 | [3] | 0.130 |
| 1 | 0.00 | 0.00 | CARD_STEP_DOWN | 0 | [2] | 0.130 |
| U | 0.00 | 0.00 | NO_SALES | 0 | [] |  |

**Umbreon VMAX 215/203** (3.00 sales/mo, sigma null)

| Grade | Gross | Net | Method | n | Basis | Ratio |
|---|---|---|---|---|---|---|
| 10 | 3699.00 | 3593.02 | OBSERVED | 3 | [] |  |
| 9 | 2025.00 | 1952.50 | OBSERVED | 3 | [] |  |
| 8 | 1800.00 | 1732.00 | OBSERVED | 3 | [] |  |
| 7 | 985.40 | 933.69 | CARD_STEP_DOWN | 0 | [8] | 0.547 |
| 6 | 539.45 | 496.66 | CARD_STEP_DOWN | 0 | [7] | 0.547 |
| 5 | 295.32 | 257.41 | CARD_STEP_DOWN | 0 | [6] | 0.547 |
| 4 | 161.67 | 126.44 | CARD_STEP_DOWN | 0 | [5] | 0.547 |
| 3 | 88.51 | 54.74 | CARD_STEP_DOWN | 0 | [4] | 0.547 |
| 2 | 48.45 | 15.48 | CARD_STEP_DOWN | 0 | [3] | 0.547 |
| 1 | 26.52 | 0.00 | CARD_STEP_DOWN | 0 | [2] | 0.547 |
| U | 0.00 | 0.00 | NO_SALES | 0 | [] |  |

**Pikachu VMAX 188/185** (2.67 sales/mo, sigma null)

| Grade | Gross | Net | Method | n | Basis | Ratio |
|---|---|---|---|---|---|---|
| 10 | 280.00 | 242.40 | OBSERVED | 3 | [] |  |
| 9 | 200.00 | 164.00 | OBSERVED | 3 | [] |  |
| 8 | 117.50 | 83.15 | OBSERVED | 2 | [] |  |
| 7 | 69.03 | 35.65 | CARD_STEP_DOWN | 0 | [8] | 0.588 |
| 6 | 40.56 | 7.75 | CARD_STEP_DOWN | 0 | [7] | 0.588 |
| 5 | 23.83 | 0.00 | CARD_STEP_DOWN | 0 | [6] | 0.588 |
| 4 | 14.00 | 0.00 | CARD_STEP_DOWN | 0 | [5] | 0.588 |
| 3 | 8.22 | 0.00 | CARD_STEP_DOWN | 0 | [4] | 0.588 |
| 2 | 4.83 | 0.00 | CARD_STEP_DOWN | 0 | [3] | 0.588 |
| 1 | 2.84 | 0.00 | CARD_STEP_DOWN | 0 | [2] | 0.588 |
| U | 0.00 | 0.00 | NO_SALES | 0 | [] |  |

**Giratina V 186/196** (3.00 sales/mo, sigma null)

| Grade | Gross | Net | Method | n | Basis | Ratio |
|---|---|---|---|---|---|---|
| 10 | 3000.00 | 2908.00 | OBSERVED | 3 | [] |  |
| 9 | 890.00 | 840.20 | OBSERVED | 3 | [] |  |
| 8 | 707.15 | 661.01 | OBSERVED | 3 | [] |  |
| 7 | 209.79 | 173.59 | CARD_STEP_DOWN | 0 | [8] | 0.297 |
| 6 | 62.24 | 29.00 | CARD_STEP_DOWN | 0 | [7] | 0.297 |
| 5 | 18.46 | 0.00 | CARD_STEP_DOWN | 0 | [6] | 0.297 |
| 4 | 5.48 | 0.00 | CARD_STEP_DOWN | 0 | [5] | 0.297 |
| 3 | 1.63 | 0.00 | CARD_STEP_DOWN | 0 | [4] | 0.297 |
| 2 | 0.48 | 0.00 | CARD_STEP_DOWN | 0 | [3] | 0.297 |
| 1 | 0.14 | 0.00 | CARD_STEP_DOWN | 0 | [2] | 0.297 |
| U | 0.00 | 0.00 | NO_SALES | 0 | [] |  |

**Lugia V 186/195** (2.67 sales/mo, sigma null)

| Grade | Gross | Net | Method | n | Basis | Ratio |
|---|---|---|---|---|---|---|
| 10 | 1179.99 | 1124.39 | OBSERVED | 3 | [] |  |
| 9 | 460.00 | 418.80 | OBSERVED | 3 | [] |  |
| 8 | 370.00 | 330.60 | OBSERVED | 2 | [] |  |
| 7 | 144.24 | 109.36 | CARD_STEP_DOWN | 0 | [8] | 0.390 |
| 6 | 56.23 | 23.11 | CARD_STEP_DOWN | 0 | [7] | 0.390 |
| 5 | 21.92 | 0.00 | CARD_STEP_DOWN | 0 | [6] | 0.390 |
| 4 | 8.55 | 0.00 | CARD_STEP_DOWN | 0 | [5] | 0.390 |
| 3 | 3.33 | 0.00 | CARD_STEP_DOWN | 0 | [4] | 0.390 |
| 2 | 1.30 | 0.00 | CARD_STEP_DOWN | 0 | [3] | 0.390 |
| 1 | 0.51 | 0.00 | CARD_STEP_DOWN | 0 | [2] | 0.390 |
| U | 0.00 | 0.00 | NO_SALES | 0 | [] |  |


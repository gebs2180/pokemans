# pokemans — raw collection collateral reports

Collateral reports for raw-heavy Pokémon card collections: what a collection recovers in a forced sale, with stated confidence, so a lender can advance against it. Also replacement values for insurers. We don't lend, hold cards, or set advance rates.

**Source of truth:** the *Raw Collection Valuation Methodology* Claude Doc (https://claude.ai/artifact/1XVmPmLtueJk37u2hXgDmL). This file summarizes it as of 2026-10-18. If they disagree, the doc wins: re-read it and update this file.

**System design:** [docs/hld.md](docs/hld.md) covers the architecture, modules, data model, flows and milestones. Read it before adding modules or entities.

## Non-negotiable principles

- **Condition is a distribution, not a grade.** Each raw card gets probabilities over 11 outcomes: Ungradable (U), 1…10. Uncertainty widens the haircut instead of being hidden.
- **Four values, always:** FMV, RV (replacement), QSV (30-day quick sale), FLV (60-day forced liquidation). Insurers cover on RV; lenders advance on FLV.
- **Evidence over opinion.** Every price traces to *sold* comps with dates and counts. Never use asking prices.
- **Portfolio, not cards.** Concentration, liquidity and correlation are first-class.
- **Conservative by default.** Thin data lowers value *and* confidence. Never interpolate or shift upward, whether for prices, grades or photo confidence.
- **Store everything,** including wrong predictions. Photos, model/API predictions, our distribution, human adjustments and returned grades are all calibration and training data.

## Pipeline

1. **Identify & inventory.** Per-card front and back photos for every card at or above $25 est. raw value; bulk lots aren't supported (Zach, 2026-10-18; not yet in the methodology doc). Photos are timestamped and kept (they double as pre-loss proof for insurance). Canonical ID: set, number, language, edition (1st Ed / Shadowless / Unlimited), print variant, promo status → catalog ID. Authenticity-risk categories (vintage holos, high-value JP promos, recent chase) get flagged, and **flagged = zero collateral value** until verified. Verifying a flag needs an in-hand inspection, a grader's slab or third-party authentication. High-risk cards (vintage holos and 1st Editions, Japanese promos) can't be verified from photos. A recent chase card can be verified by a photo review. The review names the photos it used, which must include a passing close-up. A finding, verification or doubt, counts only for the identity it was made against, so re-identifying a card resets it (Zach, 2026-10-18; not yet in the methodology doc). Record custody (who/where/since); unverified custody = no advance. Partners and collectors record their own custody, but only an admin verifies it, and a check lapses after 90 days (Zach, 2026-10-18; not yet in the methodology doc).
2. **Condition distribution.** A photo model scores centering, corners, edges and surface, and a human reviewer adjusts early on (each adjustment is training data). Half grades are folded down unless that half grade has its own sales history. Low photo confidence shifts probability *down*. Modern holos/full arts get a default downward surface shift unless a close-up clears them.
3. **Per-grade prices.** Sold comps of that exact grade, 90-day window, recency-weighted (30-day half-life); extend to 180 days if fewer than 2 sales. Price = recency-weighted median, outlier-trimmed when n ≥ 10.
   - Missing grade, sales on both sides → interpolate on log price.
   - Sales only above → higher grade × era step-down ratio (median adjacent-grade price ratio across comparable same-era cards). With no era ratio yet, use the card's own steepest per-grade ratio if it has sales in at least 2 grades at least 2 apart; otherwise leave it unpriced (Zach, 2026-10-17).
   - Sales only below → use the lower grade's price unchanged. **Never step up.**
   - Grade 1 is priced from its own sales only and never anchors another grade: PSA 1s have their own collectors and often sell above 2–4 (Zach, 2026-10-17). So there is no era 1-to-2 ratio either: a grade 1 without its own sales borrows the era's 2-to-3 ratio, else the card's own ladder (review, 2026-10-17; Zach confirmed 2026-10-18).
   - Any derived price → confidence drops one level.
   - Each grading company (PSA, CGC, BGS, …) is priced separately from its own comps, never pooled or converted. A raw card is valued on PSA's table only: no PSA table means unpriced and excluded, with no fallback to another grader (Zach, 2026-10-18; not yet in the methodology doc).
   - U priced from damaged/altered raw sales, else $0. No bulk floor (Zach, 2026-10-17). Low-value cards are excluded by the $25 FMV minimum instead.
   - Each grade's price is **net** of grading fee, shipping, insurance and a time-to-cash discount, but use the raw-sale price for that condition if it's higher. The grading cost of the day is an input to each valuation run, starting at $25 fee, $5 shipping, $2 insurance per card and 2% time-to-cash (Zach, 2026-10-18; not yet in the methodology doc).
   - A price table no refresh has replaced (sales aged out or purged) is still used, at lower confidence: one level once it is more than 7 days old, one more every 30 days after, down to D (Zach, 2026-10-18).
   - Volatility σ = std dev of 30-day price changes over 12 months, stored per card, from graded sales only (never raw). With too few changes to measure it, a valuation uses a fallback σ of 20% a month and drops confidence one level (Zach, 2026-10-18; not yet in the methodology doc).
4. **Values** (p = grade probability, P = net price):
   - `FMV_i = Σ_g p_ig · P_ig`
   - `RV_i = FMV_i · (1 + b + s_i)`, using the central (unstressed) distribution. b = 12% buyer costs; s = search premium of 0% (>20 sales/mo), 5% (5–20), 10% (<5).
   - `QSV_i = FMV_i · (1 − f) · (1 − d_i)`. f = 15% fees+shipping; d = 5% (>20 sales/mo), 10% (5–20), 20% (<5).
   - `FLV_i = FMV↓_i · (1 − f) · (1 − k·σ_i·√t) · (1 − ℓ_i)`. FMV↓ shifts the distribution 2 grades down (3 if confidence C), floored at grade 1, with U probability unchanged; each grade is priced at most the grade above it, so the shift can't gain from a PSA 1 premium. k = 2, t = 2 months, ℓ = market-depth discount (share of a month's volume to sell), floor 10%, cap 50%. FLV is never above QSV (Zach, 2026-10-18).
   - Confidence A–D from photo quality and comp counts. D is excluded from the collateral base.
   - A card whose returned grade's cert is verified is valued as graded: certain at that grade (half grades fold down), on its own grader's table at gross prices, with no grading cost and no condition stress shift; it counts in the graded share and is left out of grade-first (Zach, 2026-10-18; no condition stress confirmed 2026-10-19: a graded card is priced at its grade, whatever its condition; not yet in the methodology doc).
5. **Portfolio.** Eligible = not D, no unresolved auth flag, verified custody, FMV ≥ $25. Lots are never valued. Single card ≤ 20% and top five ≤ 50% of the eligible FLV before caps, applied by capping every card at one level, at every collection size with no minimum card count (Zach, 2026-10-19; replaces the 2026-10-18 final-base rule, which squeezed 10–15 card pledges down to their smallest card's FLV and had a cliff at 10 cards; not yet in the methodology doc). So one card counts at most 20% of its FLV, and five or fewer at most 50%; >40% of base in one set or character → extra 10% off that slice. Report raw vs graded share. `Advance = α · Σ min(FLV_i, cap_i)`. α is the **lender's** choice: suggest a range (e.g. 50–70%), never set it.
6. **Grade-first list.** `Lift_i = (FLV_graded − FLV_raw − C_i) / C_i`, where C = fee + shipping + insurance + waiting cost. Recommend lift > 1.0 and show the top 20 with expected grade and cost. FLV_raw here is computed from gross prices, so grading cost counts once, in C (Zach, 2026-10-18; not yet in the methodology doc). Doubted cards are left off.
7. **Reports.** A valuation run stores every input and the full trace, and the reports render from the run alone. Lender report: collateral base, suggested α range (the lender sets α; a lender-given α adds one line), caps and haircuts with reasons, exclusions with reasons, raw vs graded share, confidence, grade-first top 20, traceability. Insurer report: RV for every identified, priced card with its photos as pre-loss proof; counterfeit or suspected cards get RV $0 with the reason; custody and confidence D don't exclude. Only the admin issues reports; members of the collection's org can download them (Zach, 2026-10-18).

**Regression check (worked example, illustrative numbers):** FMV $411 → RV $481 → QSV $314 → stressed FMV $181 → FLV $107 → advance at α=60% $64. Inputs: ~10 sales/mo, σ = 8%, one copy (ℓ = 10%), f = 15%, b = 12%, s = 5%. Full grade table is in the doc; use it as an early unit test.

## Validation targets

- Condition: actual grade within ±1 of predicted median ≥ 80% of the time; below the stressed median < 5%. 1,000+ partner cards plus a 20–30 card controlled PSA set.
- Liquidation: replaying the 2021–2023 decline, FLV computed at peak covers price 60 days later for ≥ 95% of cards.

## Constraints

- Price data must be licensable for commercial use. Check pre-grading API terms before storing their outputs or training on them.
- Personal accounts and infrastructure only (no employer resources).

# pokemans — raw collection collateral reports

Collateral reports for raw-heavy Pokémon card collections: what a collection recovers in a forced sale, with stated confidence, so a lender can advance against it. Also replacement values for insurers. We don't lend, hold cards, or set advance rates.

**Source of truth:** the *Raw Collection Valuation Methodology* Claude Doc (https://claude.ai/artifact/1XVmPmLtueJk37u2hXgDmL). This file summarizes it as of 2026-10-15. If they disagree, the doc wins: re-read it and update this file.

**System design:** [docs/hld.md](docs/hld.md) covers the architecture, modules, data model, flows and milestones. Read it before adding modules or entities.

## Non-negotiable principles

- **Condition is a distribution, not a grade.** Each raw card gets probabilities over 11 outcomes: Ungradable (U), 1…10. Uncertainty widens the haircut instead of being hidden.
- **Four values, always:** FMV, RV (replacement), QSV (30-day quick sale), FLV (60-day forced liquidation). Insurers cover on RV; lenders advance on FLV.
- **Evidence over opinion.** Every price traces to *sold* comps with dates and counts. Never use asking prices.
- **Portfolio, not cards.** Concentration, liquidity and correlation are first-class.
- **Conservative by default.** Thin data lowers value *and* confidence. Never interpolate or shift upward, whether for prices, grades or photo confidence.
- **Store everything,** including wrong predictions. Photos, model/API predictions, our distribution, human adjustments and returned grades are all calibration and training data.

## Pipeline

1. **Identify & inventory.** Per-card front and back photos above $25 est. raw value, cards below that handled as photographed lots. Photos are timestamped and kept (they double as pre-loss proof for insurance). Canonical ID: set, number, language, edition (1st Ed / Shadowless / Unlimited), print variant, promo status → catalog ID. Authenticity-risk categories (vintage holos, high-value JP promos, recent chase) get flagged, and **flagged = zero collateral value** until verified. Record custody (who/where/since); unverified custody = no advance.
2. **Condition distribution.** A photo model scores centering, corners, edges and surface, and a human reviewer adjusts early on (each adjustment is training data). Half grades are folded down unless that half grade has its own sales history. Low photo confidence shifts probability *down*. Modern holos/full arts get a default downward surface shift unless a close-up clears them.
3. **Per-grade prices.** Sold comps of that exact grade, 90-day window, recency-weighted (30-day half-life); extend to 180 days if fewer than 2 sales. Price = recency-weighted median, outlier-trimmed when n ≥ 10.
   - Missing grade, sales on both sides → interpolate on log price.
   - Sales only above → higher grade × era step-down ratio (median adjacent-grade price ratio across comparable same-era cards). With no era ratio yet, use the card's own steepest per-grade ratio if it has sales in at least 2 grades at least 2 apart; otherwise leave it unpriced (Zach, 2026-10-17; not yet in the methodology doc).
   - Sales only below → use the lower grade's price unchanged. **Never step up.**
   - Grade 1 is priced from its own sales only and never anchors another grade: PSA 1s have their own collectors and often sell above 2–4 (Zach, 2026-10-17). So there is no era 1-to-2 ratio either: a grade 1 without its own sales borrows the era's 2-to-3 ratio, else the card's own ladder (review, 2026-10-17; needs Zach's OK).
   - Any derived price → confidence drops one level.
   - Each grading company (PSA, CGC, BGS, …) is priced separately from its own comps, never pooled or converted.
   - U priced from damaged/altered raw sales, else $0. No bulk floor (Zach, 2026-10-17; the methodology doc still says bulk floor and needs updating). Low-value cards are excluded by the $25 FMV minimum instead.
   - Each grade's price is **net** of grading fee, shipping, insurance and a time-to-cash discount, but use the raw-sale price for that condition if it's higher.
   - A price table no refresh has replaced (sales aged out or purged) is still used, at lower confidence: one level once it is more than 7 days old, one more every 30 days after, down to D (Zach, 2026-10-18; not yet in the methodology doc).
   - Volatility σ = std dev of 30-day price changes over 12 months, stored per card, from graded sales only (never raw).
4. **Values** (p = grade probability, P = net price):
   - `FMV_i = Σ_g p_ig · P_ig`
   - `RV_i = FMV_i · (1 + b + s_i)`, using the central (unstressed) distribution. b = 12% buyer costs; s = search premium of 0% (>20 sales/mo), 5% (5–20), 10% (<5).
   - `QSV_i = FMV_i · (1 − f) · (1 − d_i)`. f = 15% fees+shipping; d = 5% (>20 sales/mo), 10% (5–20), 20% (<5).
   - `FLV_i = FMV↓_i · (1 − f) · (1 − k·σ_i·√t) · (1 − ℓ_i)`. FMV↓ shifts the distribution 2 grades down (3 if confidence C), floored at grade 1, with U probability unchanged; each grade is priced at most the grade above it, so the shift can't gain from a PSA 1 premium. k = 2, t = 2 months, ℓ = market-depth discount (share of a month's volume to sell), floor 10%, cap 50%. FLV is never above QSV (Zach, 2026-10-18).
   - Confidence A–D from photo quality and comp counts. D is excluded from the collateral base.
5. **Portfolio.** Eligible = not D, no unresolved auth flag, FMV ≥ $25. Single card ≤ 20% of base; top five ≤ 50%, both measured against the final (capped) base by capping every card at one level; with fewer than 10 eligible cards the caps can't hold, so they aren't applied and the report says so (Zach, 2026-10-18; not yet in the methodology doc); >40% of base in one set or character → extra 10% off that slice. Report raw vs graded share. `Advance = α · Σ min(FLV_i, cap_i)`. α is the **lender's** choice: suggest a range (e.g. 50–70%), never set it.
6. **Grade-first list.** `Lift_i = (FLV_graded − FLV_raw − C_i) / C_i`, where C = fee + shipping + insurance + waiting cost. Recommend lift > 1.0 and show the top 20 with expected grade and cost.

**Regression check (worked example, illustrative numbers):** FMV $411 → RV $481 → QSV $314 → stressed FMV $181 → FLV $107 → advance at α=60% $64. Inputs: ~10 sales/mo, σ = 8%, one copy (ℓ = 10%), f = 15%, b = 12%, s = 5%. Full grade table is in the doc; use it as an early unit test.

## Validation targets

- Condition: actual grade within ±1 of predicted median ≥ 80% of the time; below the stressed median < 5%. 1,000+ partner cards plus a 20–30 card controlled PSA set.
- Liquidation: replaying the 2021–2023 decline, FLV computed at peak covers price 60 days later for ≥ 95% of cards.

## Constraints

- Price data must be licensable for commercial use. Check pre-grading API terms before storing their outputs or training on them.
- Personal accounts and infrastructure only (no employer resources).

# pokemans — raw collection collateral reports

**Product name:** Rawmark (`rawmark.app`, chosen 2026-10-25). "pokemans" stays the internal name in packages, Gradle, databases, buckets and Auth0 identifiers.

Collateral reports for raw-heavy Pokémon card collections: what a collection recovers in a forced sale, with stated confidence, so a lender can advance against it. Also replacement values for insurers, and since 2026-10-19 one firm instant offer per raw card for collectors (the lender and insurer side is paused meanwhile). We don't lend, hold cards, or set advance rates.

**Source of truth:** the *Raw Collection Valuation Methodology* Claude Doc (https://claude.ai/artifact/1XVmPmLtueJk37u2hXgDmL). It holds every rule, formula and parameter. Until Zach approves and applies [docs/methodology-sync-2026-11.md](docs/methodology-sync-2026-11.md), that file holds the rules decided since 2026-10-18 that the doc is missing, and where it disagrees with the doc, the file is the later decision. Once applied, the doc wins: if this file and the doc disagree, re-read the doc and update this file.

**System design:** [docs/hld.md](docs/hld.md) covers the architecture, modules, data model, flows and milestones. Read it before adding modules or entities. Offers: [docs/offers.md](docs/offers.md). PokeTrace: [docs/poketrace-pilot.md](docs/poketrace-pilot.md).

## Working here

- Windows. Write files with the Write tool: Bash heredocs fail here.
- Backend: `cd backend; .\gradlew.bat test`. Web: `npm.cmd`, not `npm`. Docker must be running or the Testcontainers tests are skipped.
- Before starting side sessions, check the 5-hour meter. Above 50%, start one. Run at most two heavy writers, and name the shared files and merge order in each brief.
- Pick the model when a session starts, because switching later rebuilds the cache. Sonnet for recipe work; Opus for valuation math, money, auth and audits.
- A new decision goes into the methodology doc (ask Zach first), with its attribution and date. Keep "defaults to confirm" labels until Zach confirms them. Don't put rule text back into this file.

## Compact instructions

Keep PR numbers, open decisions, file paths and test status.

## Non-negotiable principles

- **Condition is a distribution, not a grade.** Probabilities over 11 outcomes: Ungradable (U), 1…10. Uncertainty widens the haircut.
- **Four values, always:** FMV, RV (replacement), QSV (30-day quick sale), FLV (60-day forced liquidation). Insurers cover on RV; lenders advance on FLV.
- **Evidence over opinion.** Every price traces to *sold* comps with dates and counts. Never asking prices.
- **Portfolio, not cards.** Concentration, liquidity and correlation are first-class.
- **Conservative by default.** Thin data lowers value *and* confidence. Never interpolate or shift upward, whether for prices, grades or photo confidence.
- **Store everything,** including wrong predictions: photos, provider predictions, our distribution, human adjustments and returned grades.

## Pipeline overview

Formulas, parameters and edge cases are in the doc (and the sync file until it's applied); don't work from memory. (1) **Identify** each card at or above $25 from front and back photos to a canonical catalog ID; authenticity-risk cards are flagged and worth zero until verified; custody is recorded and admin-verified. (2) **Condition** is a distribution over U and 1–10 from pre-grade providers, our centering cap and human review, shifted down when photo confidence is low. (3) **Per-grade prices** come from sold comps of that exact grade (PokeTrace live since 2026-11-07), net of grading cost with a raw-sale floor, on PSA's table only for raw cards; missing grades are derived conservatively, never stepped up, and grade 1 stands alone. (4) **Values**: FMV, RV, QSV and FLV (stressed two grades down, volatility shock, depth discount, never above QSV), each with confidence A–D. (5) **Portfolio**: eligibility, single-card 20% and top-five 50% caps, correlation haircut, raw vs graded share; `Advance = α · Σ min(FLV_i, cap_i)` with α the lender's choice, only ever suggested as a range. (6) **Grade-first** list of lift > 1.0, top 20. (7) **Reports** render from a stored run alone; only the admin issues them. (8) **Instant offers**: one firm price per raw card, posted to buyer orgs, first accept buys, repriced at post and accept.

## Regression check

Worked example, illustrative numbers: FMV $411 → RV $481 → QSV $314 → stressed FMV $181 → FLV $107 → advance at α=60% $64. Inputs: ~10 sales/mo, σ = 8%, one copy (ℓ = 10%), f = 15%, b = 12%, s = 5%. Full grade table is in the doc; use it as an early unit test. (Whether a one-card pledge should show $64 or the capped $13 is open: item 36 in the sync file.)

## Validation targets

- Condition: actual grade within ±1 of predicted median ≥ 80% of the time; below the stressed median < 5%. 1,000+ partner cards plus a 20–30 card controlled PSA set.
- Liquidation: replaying the 2021–2023 decline, FLV computed at peak covers price 60 days later for ≥ 95% of cards.

## Constraints

- Price data must be licensable for commercial use. Check pre-grading API terms before storing their outputs or training on them.
- Personal accounts and infrastructure only (no employer resources).

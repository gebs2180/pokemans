# M3 pricing: progress

Status 2026-10-16 · scheduled run `pokemans-m3-skeleton` · **not committed**, for Zach to review.

`cd backend; .\gradlew.bat test` passes: valuation-core 49 tests, app 67 tests (66 passed, 1 skipped: `RealPhotoCalibrationTest`, which needs a local photo folder). Docker Desktop was started for the run, so the Testcontainers tests ran too, `PricingFlowTest` included.

Fixture data only. Nothing calls a real price API or scrapes anything.

## What was built

**valuation-core** (the step 3 math was already here; extended, not duplicated):
- `GradePrice` now also carries:
  - `windowDays`: 90, or 180 when extended.
  - `salesInWindow`: n before outlier trimming.
  - `basis`: the grades a derived price came from.
  - `confidence`: per grade.
- `GradePricer` sets per-grade confidence:
  - Observed prices take it from their comp count, using the parameter set's thresholds (10 / 5 / 2 sales for A / B / C).
  - Derived prices are one level below the worst grade they came from.
  - Floor prices are D.
- `GradePricer.observedPrices`: observed prices only, which feed the era table.
- `StepDownRatios` (new): for each grade, the median ratio of its price to the grade above, across same-era cards.
  - A ratio needs at least `minCards` cards with both grades observed. With an even count, it takes the lower middle ratio.
  - Ratios are capped at 1.
- `Volatility.fromComps` (new): σ from 13 thirty-day buckets.
  - Each bucket's price is its median sale.
  - A change only counts between two adjacent non-empty buckets.
  - Changes from all grades are pooled.
  - Returns empty with fewer than 2 changes.
- **New tests:**
  - "Never step up" is covered three ways: a ratio above 1 is refused, carried-down prices ignore an available ratio, and `StepDownRatios` caps at 1.
  - Confidence: observed counts before trimming, interpolation, step-down chains and carried-down prices.
  - Window: 180-day extension.
  - σ: bucketing, no bridging of gaps, pooling across grades, and the lookback cut-off.

**app, new `pricing` module** (package-private apart from `Prices`):
- **`PriceSource` port:**
  - A `Sale` carries the source, external sale id, catalog card, grading company, grade (half grades as sold) or raw condition, price, currency, sold date, URL, listing type and the raw record.
  - A sale without a sold date can't be built, and `ListingType` has no value for a listing that hasn't sold.
  - The only adapter is `FixturePriceSource`, in test sources.
- **`CompIngestionService`:**
  - Stores new sales with provenance (source, licence, retrieval time, raw record, ingest run).
  - Skips repeats by (source, external id).
  - Rejects, with a reason on the run: the wrong card, non-USD prices, future sold dates, or a source name mismatch.
  - If one card fails, the run carries on with the rest.
- **`PriceSnapshotService`:** two passes.
  1. Observed prices per card build each era's step-down table. An era is language plus a release-date band.
  2. Each identified card, and each card with comps, is priced with `GradePricer`. It gets 11 `price_snapshot` rows sharing a `snapshot_set_id`, plus one `price_stats` row with σ and sales per month.
  - Grades 1–10 use PSA comps only. 8.5 folds into 8. Damaged and altered raw sales price U.
  - A card the pricer can't price, for example with no era ratio, is skipped with the reason. It is never guessed.
- **`PricingRefresh` and `PricingScheduler`:** a nightly job that ingests from every `PriceSource` bean and then snapshots. It is off by default and turns on with `PRICING_INGESTION_ENABLED=true`. With no sources it only re-snapshots the comps already stored.
- **`Prices`:** the published API. `latest(catalogCardId)` and `bySet(snapshotSetId)` return gross prices, method, n, comp ids, window, basis, ratio, confidence, σ and sales per month.
- **`Catalog`:** gained `identifiedCards()` and `cards(ids)`, which return a `CardSummary`.
- **Flyway V8:**
  - `sold_comp` is append-only, with a unique (source, external sale id), checks that a comp is graded or raw but not both, and price > 0.
  - Also `comp_ingest_run`, `price_snapshot` and `price_stats`.
- **Config:** `pokemans.pricing.*` in `application.yml`.
- **Tests:**
  - `PricingUnitTest`: sale validation and era keys.
  - `PricingFlowTest` (Testcontainers): identify cards, ingest, reject the bad sales, skip the repeat, then check every snapshot method, the comp ids, the ratio, U, σ, the sales rate, the unpriced modern card, and that a rerun is idempotent.
- **Docs:** M3 status note in [hld.md](hld.md).

## What's left

- **A licensed adapter.** It waits on the vendor replies ([price-vendor-emails.md](price-vendor-emails.md)). PokeTrace Pro or Scale is the planned pilot source. Map its grader/grade/condition fields onto `Sale`, and keep its key in the environment.
- **Manual comp entry** for the pilot: an admin endpoint and UI that record a comp with marketplace, URL, date, grade and price, with `source = MANUAL`. This is the recommended route for any report shown to a lender.
- **Comps browser** in the admin console, plus comp overrides. Overrides would be new rows, never edits.
- **Step-down ratio tables:** store them per era and recompute weekly, as the HLD says. For now they are rebuilt on each run and only the ratio used is kept on the snapshot.
- **Half grades with their own sales history.** They are always folded down for now.
- **Raw-condition override** (step 3's "use the raw-sale price if higher"). The pricer supports it, but snapshots pass no raw prices, because nothing maps a raw condition to a grade yet.
- **Netting grading costs.** It belongs in the valuation run (M4), with the grading cost and turnaround of the day.
- **The M3 "done when":** snapshots for the controlled set from real comps.
- **Performance:** each run loads comps per card and does two passes in one transaction. That's fine for tens of cards. Batch it before thousands.
- **Purging by licence**, if a vendor's terms require it (PriceCharting's purge clause). Provenance is stored per comp to make this possible.

## Open questions (choices made, for Zach to confirm)

1. **Grader for grades 1–10:** PSA only, by config (`pokemans.pricing.grading-company`). CGC, BGS and the rest are stored but unused. Should other graders' comps count, perhaps converted, or price separately?
2. **Confidence on step-down chains:** each step drops one more level, so grade 6 stepped from a stepped grade 7 is two levels below grade 8. That is the conservative reading of "any derived price drops one level". Is the intent a single drop?
3. **Confidence counts sales before outlier trimming**, so 10 sales trimmed to 8 still count as 10 (A). Using the post-trim count would mean 10–11 sales give B.
4. **No extra drop for the 180-day window.** Thin data already shows in the low n. Should an extended window also drop a level?
5. **Era bands** (WotC to May 2003, EX to Black & White, XY to Sun & Moon, Sword & Shield on, split by language) and **3 cards per ratio** are starting values I chose. What makes cards "comparable" may need rarity or holo status as well.
6. **Missing era ratio:** the card is left unpriced and reported (`skipped`), following the pricer's existing "refuse rather than guess". The alternative is pricing those grades at the bulk floor.
7. **Bulk floor:** $0.25, a placeholder. It needs a figure from bulk-lot sales.
8. **σ pooling:** 30-day changes are pooled across grades, from PSA comps only. Raw sales are excluded.
9. **Sales per month:** every stored comp of the card (all graders and raw) over the last 90 days, per 30 days. Should liquidity be per grade?
10. **Snapshots are gross.** M4 nets grading costs. The snapshot keeps the market evidence separate from our cost assumptions.
11. **Scheduling:** `@Scheduled` like the other modules, not JobRunr as the HLD says. Switch when retries or the dashboard are needed.
12. **Append-only** is enforced by code (no setters, no update paths), not by a database trigger, matching `provider_prediction`. Add a trigger?

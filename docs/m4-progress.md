# M4 valuation and reports: progress

Status 2026-10-18 · branch `claude/beautiful-rhodes-f0e445`.

**Checks.** These pass locally with Docker running:
- `cd backend; .\gradlew.bat build`, which runs valuation-core, the app's Testcontainers suites and the JaCoCo gate.
- In `web/`: `npm run lint`, `npm test` and `npm run build`.

**Data.** Fixture and hand-entered data only: no licensed price source yet.

## What was built

**valuation-core** (the golden worked example is unchanged):
- **Confidence penalties.** `CardValuator.Input` takes `ConfidencePenalty`s (reason, levels), applied before the stress is chosen. They cover a stale price table and a fallback σ.
- **Known grade.** A `knownGrade` flag means no condition stress shift.
- **Netting.** `GradePriceTable.netOf(gross, cost)` nets a gross snapshot the way the pricer does, and `atGross()` undoes it. `GradingCost.netPrice` is shared with `GradePricer`, and `GradingCost` refuses negative costs.
- **Grade-first.** `GradeFirstRanker` computes FLV_raw for the lift from gross prices, so C counts once. `Candidate` no longer takes a raw valuation.
- **Parameter set 1.1.0.** It is 1.0.0 plus `values.fallbackSigma=0.20`, without `portfolio.capsMinCards` (caps apply at every size since 2026-10-19). The 1.0.0 file is untouched; its `capsMinCards` key is now ignored. Pricing still uses 1.0.0.
- **Caps against the eligible FLV** (Zach, 2026-10-19): `PortfolioEvaluator` caps every card at one level, the highest at which no card is above 20% and the top five are at most 50% of the eligible FLV before caps, at every collection size.

**app, new `valuation` module:**
- **`ValuationService.run`** gathers each card of a collection:
  - its identity;
  - its current assessment (new `condition.Assessments`);
  - its verified returned grade (new `outcomes.Outcomes`);
  - its PSA table, or the grader's own table for a slab;
  - its provenance;
  - its passing photos.

  Cards that can't be valued are kept with a reason: a lot, not identified, no condition assessment, or no PSA price table. The engine then values the collection, and everything is stored in one transaction.
- **`ValuationEngine`:** pure. It runs CardValuator per card, then PortfolioEvaluator, then GradeFirstRanker on raw cards that aren't doubted. It also works out the insurer RV: $0 for a counterfeit or suspected card.
- **Reproducible:**
  - `card_valuation.inputs` holds canonical JSON (sorted keys).
  - The run stores the parameter-set body and the grading cost, plus a SHA-256 over all of it.
  - `GET /api/admin/valuation-runs/{id}/replay` re-values the run from the stored JSON alone and lists any number that differs.
- **Copies held** (the depth discount's numerator) counts items in the run with the same catalog card and graded state.
- **Correlation keys:**
  - The set key is language plus set name.
  - The character key is the name without an owner prefix (Dark, Light, "X's") or a mechanic suffix (ex, GX, V, VMAX, VSTAR, LV.X, δ, ☆…). `CorrelationKeysTest` lists the cases.
- **Flyway V13:** `valuation_run`, `card_valuation` and `report`, all refusing updates.

**app, new `reporting` module:**
- **Rendering:** Thymeleaf builds XHTML (`resources/reports/templates`), then OpenHTMLtoPDF 1.1.85 renders it.
  - OpenHTMLtoPDF is LGPL-2.1, used unmodified as a library. PDFBox 3 underneath is Apache-2.0, and Thymeleaf is Apache-2.0.
  - The fonts are Noto Sans Regular and Bold (SIL OFL), bundled.
  - The PDF's dates, producer and document id come from the report, so the same report renders the same bytes. `GoldenReportTest` checks this.
- **Lender report:**
  - base and eligible FLV;
  - the suggested α range with the advance at each end, saying "the lender sets alpha";
  - an optional lender-given α line;
  - the caps note, or the under-10-cards note;
  - each cap and correlation haircut;
  - the per-card table;
  - exclusions with the provenance state behind each gate;
  - the grade-first top 20;
  - a method summary;
  - a traceability appendix (per grade in play: p, gross, net, method, sales, window).
- **Insurer report:** RV per card, with the front and back crops at 400 px as JPEG, their capture and upload times, and the SHA-256 of each original. It ends with the not-covered list.
- **Storage:** PDFs are stored once at `reports/{orgId}/{reportId}.pdf` in the private photo bucket, with their SHA-256 and size.
- **Downloads** return `{url}`, a short-lived signed link, rather than a 302, so the browser doesn't carry the API token to storage.

**API:**
- Admin:
  - `GET /api/admin/valuation/defaults`
  - `POST`/`GET /api/admin/orgs/{orgId}/collections/{collectionId}/valuation-runs`
  - `GET /api/admin/valuation-runs/{id}` and `.../replay`
  - `POST`/`GET /api/admin/valuation-runs/{id}/reports`
  - `GET /api/admin/reports/{id}/download`
- Org members (collector and contributor orgs):
  - `GET /api/orgs/{orgId}/collections/{collectionId}/reports`
  - `GET /api/orgs/{orgId}/reports/{id}/download`

**Web:**
- **Collection page:** a Reports section with downloads, and for admins a link to valuation.
- **`/admin/valuation/:orgId/:collectionId`:** start a run (grading cost prefilled, editable) and list runs.
- **`/admin/valuation-runs/:runId`:** the figures, a per-card table with an expandable trace and the reasons, the grade-first list, issuing (with an optional lender α), and the issued reports.

**Tests:**
- `ValuationFlowTest`. Seventeen cards through the API:
  - ten clean;
  - a lot, an unidentified card and an unpriced one;
  - a vintage holo awaiting verification;
  - a counterfeit;
  - a card with unverified custody;
  - a σ fallback.

  It checks every reason, the caps, replay, append-only rows, both reports (stored hash, PDF text), member downloads and outsiders. A second test covers a verified PSA 9.5 slab valued as a 9 at gross with no stress, and the insurer PDF with real photos.
- `GoldenReportTest`. A fixed 16-item collection (`ValuationFixtures`) rendered without a database. It is compared with `src/test/resources/golden/*.txt`, and the PDFs are kept in `app/build/golden-reports/` to look at. `-Dgolden.update=true` rewrites the golden files.
- `CrossOrgAccessTest` covers the new org routes and every new admin route.
- `AppendOnlyTablesTest` covers the new tables.
- Vitest covers the run page, StartRun and ReportsList.

## For Zach to decide

1. ~~**Caps squeeze small pledges hard.**~~ **Decided 2026-10-19:** caps are now measured against the eligible FLV before caps, at every size, with no 10-card minimum (`PortfolioEvaluator`, parameter set 1.1.0 drops `capsMinCards`). The golden fixture's base goes from $589 to $1,133.65 (cap level $223.64; $1,227.65 before the correlation haircut). A single card counts at most 20% of its FLV and five or fewer cards at most 50%. The original note follows. Measured against the final base, "top five ≤ 50%" means the five smallest eligible cards must add up to the five largest. With 10 to about 15 eligible cards, the cap level falls to roughly the smallest cards' FLV.
   - **The golden fixture:** ten eligible cards with FLV from $64 to $519 give an eligible FLV of $1,608. Every card is capped at $64.01, which is the smallest card's FLV, so the base is $589.
   - **Is this what you want?** Alternatives would be to measure the caps against the uncapped base, to apply only the 20% single-card cap below some count, or to raise `portfolio.capsMinCards`.
2. ~~**Graded cards get no condition stress.**~~ Confirmed by Zach, 2026-10-19: a graded card is priced at its grade, whatever its condition. The volatility shock, the depth discount and FLV ≤ QSV still apply.
3. **Character keys.** The normalization rules above decide which cards share a correlation slice. Check them against your sense of "same character".
4. **Methodology doc.** It needs the 2026-10-18 decisions now in CLAUDE.md:
   - PSA only for raw cards;
   - grading cost per run;
   - the fallback σ;
   - slabs valued as graded;
   - the lift counting C once;
   - lots never valued;
   - the reports section.

## What's left

- **The M4 "done when": a first free report from real data.** The steps:
  1. Pick a real collection and capture it.
  2. Identify and assess every card.
  3. Enter sold comps by hand for each card (at least 2 graded grades; sales across 2 or more months give a measured σ).
  4. Record custody checks and any authenticity verifications.
  5. Run a valuation from the collection page's admin link.
  6. Issue the lender report and send it.
- **Japanese card names:** Noto Sans has no CJK glyphs, so Japanese text would render as missing glyphs. Bundle Noto Sans JP (several MB) before the first Japanese-heavy collection.
- **Raw-condition override** ("use the raw-sale price if higher") still isn't applied, because snapshots carry no raw-condition prices. This is carried over from M3.
- **Run size:** runs are synchronous and load per card. That's fine for tens of cards. Batch the lookups before collections of hundreds.
- **Notifications** when a report is issued (M6).
- **Not checked in the running app.** The UI is covered by component tests and the API by flow tests. Logging in needs the admin's Auth0 MFA, which wasn't available to this session.

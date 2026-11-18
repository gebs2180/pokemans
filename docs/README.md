# docs/ index

Which files here describe how things work now, and which are dated records. Checked 2026-11-18 against `git log` and the code. If a file you rely on is marked **Historical**, read the doc named as its replacement instead.

- **Current**: still describes how things work. Where code and a Current doc disagree, fix the doc.
- **Historical**: a dated review, audit or progress log whose findings are done or superseded. Don't read it as a description of today.
- **Reference**: vendor research and drafts. Facts were true on the date written; re-check before relying on them.

`CLAUDE.md` (repo root) is the summary of the methodology doc and outranks everything here on valuation rules.

## Top level

| File | Status | Note |
|---|---|---|
| [hld.md](hld.md) | **Current** | Plan and design intent. Corrected to the build on 2026-11-03; where the plan wasn't built it says so. The as-built reference is [architecture/](architecture/README.md). |
| [offers.md](offers.md) | **Current** | Instant offers: rules, flow, numbers. Updated through 2026-11-11 (parameter sets up to 1.7.0). Its "Status 2026-10-19" line at the top is stale. |
| [poketrace-pilot.md](poketrace-pilot.md) | **Current** | The live-source sections (day guards, revised days, pull gaps) match `CLAUDE.md`. The one-month pilot plan and "Day-1 results" (2026-11-01) are historical. |
| [auth.md](auth.md) | **Current** | Auth0 setup and what the backend expects. Last touched 2026-11-02. |
| [hosting.md](hosting.md) | **Current** | Hetzner + Compose + R2 + Sentry; `deploy/` matches the recommendation. The provider comparison tables are the decision record. |
| [local-dev.md](local-dev.md) | **Current** | Windows dev setup. |
| [api-types.md](api-types.md) | **Current** | How `OpenApiSpecTest` and `api.check.ts` keep web types in step. |
| [data-retention.md](data-retention.md) | **Current** | What is kept how long (2026-11-12). |
| [data-deletion.md](data-deletion.md) | **Current** | Deletion-request procedure; updated for admin hard delete (PR #141). |
| [pregrade-providers.md](pregrade-providers.md) | **Reference** | Pre-grade provider terms, read 2026-10-16. Not legal advice; re-check before signing up. |
| [pregrade-data-sources.md](pregrade-data-sources.md) | **Reference** | Plan for getting training data for our own pre-grader (2026-11-02). A plan, not code. |
| [price-data-sources.md](price-data-sources.md) | **Reference** | Sold-price source terms review (2026-10-16), plus PokeTrace's written permission of 2026-11-07. Other vendors' terms may have changed. |
| [price-vendor-emails.md](price-vendor-emails.md) | **Reference** | Draft confirmation emails to price vendors. Unsure whether any were sent; PokeTrace has since answered. |
| [centering-training-plan.md](centering-training-plan.md) | **Current** (plan) | Written 2026-11-01; step 1 (labelling page and scoreboard) is built (PR #133, 2026-11-13), steps 2–5 are not. Unsure whether the effort estimates still hold. |
| [brand-directions.md](brand-directions.md) | **Historical** | Three directions; "Hobby energy" was chosen 2026-10-26, then superseded 2026-11-13 by the Ledger look (PR #132). Its own banner says so. |
| [beta-readiness.md](beta-readiness.md) | **Historical** | Checklist as of 2026-10-26 (touched 2026-11-10) for the first Discord test. Unsure which boxes are still true; don't treat it as live status. |
| [methodology-changes-2026-10-26.md](methodology-changes-2026-10-26.md) | **Current** (pending) | Lists `CLAUDE.md` decisions not yet in the methodology doc. Nothing was applied to the doc when written, and newer "not yet in the methodology doc" decisions (2026-10-31 to 2026-11-11) are not in it. Unsure whether Zach has applied any since. |
| [m3-progress.md](m3-progress.md) | **Historical** | M3 pricing skeleton, 2026-10-16. Superseded by the code, [hld.md](hld.md) and [architecture/](architecture/README.md). |
| [m4-progress.md](m4-progress.md) | **Historical** | M4 valuation and reports, 2026-10-18. The pause it records (lender and insurer side, 2026-10-19) still holds; the rest is superseded by [offers.md](offers.md) and the code. |
| [review-2026-10-17.md](review-2026-10-17.md) | **Historical** | Overnight five-agent review at `fd807d2`. Superseded by [security-review-2026-10-26.md](security-review-2026-10-26.md) and [review-architecture-2026-11.md](review-architecture-2026-11.md). |
| [e2e-audit-2026-10-21.md](e2e-audit-2026-10-21.md) | **Historical** | Beta click-through audit. Its "Status after the fixes" section records the fixes (PR "Fix E2E audit findings before the Discord beta"). |
| [security-review-2026-10-26.md](security-review-2026-10-26.md) | **Historical** | Fixes were made with tests the same night. Follow-on: [audit-2026-11-02.md](audit-2026-11-02.md). Unsure whether its policy questions were all settled. |
| [dependency-triage-2026-10-26.md](dependency-triage-2026-10-26.md) | **Historical** | The ten Dependabot PRs were merged afterwards (#96–#99, #101–#106). |
| [offer-sanity-2026-10-26.md](offer-sanity-2026-10-26.md) | **Historical** | Offer-engine run over `comps.csv` before the vendor spot check. The $500 maximum it assumes was lifted the same day (EasyPost's $5,000 limit now); prices and PokeTrace rules changed from 2026-11-07. Re-run the runner for numbers. |
| [audit-2026-11-02.md](audit-2026-11-02.md) | **Historical** | Pre-stranger audit: 14 findings, all closed by 2026-11-02 (finding 1 needed a step from Zach). |
| [review-architecture-2026-11.md](review-architecture-2026-11.md) | **Historical** | 58 findings, most marked fixed in #84–#90; the 14 decisions D1–D14 were settled in PR #90 and later. Open items it still lists were carried into [reviews/2026-11-09-deep-review.md](reviews/2026-11-09-deep-review.md) (A5). |
| [ux-review-2026-11-01.md](ux-review-2026-11-01.md) | **Historical** | First-time-user review. Superseded by [reviews/2026-11-12-ux-walkthrough.md](reviews/2026-11-12-ux-walkthrough.md). Screenshots are in [ux-review-2026-11-01/](ux-review-2026-11-01/). |

## [reviews/](reviews/)

| File | Status | Note |
|---|---|---|
| [2026-11-09-deep-review.md](reviews/2026-11-09-deep-review.md) | **Historical** | Whole-system review. Findings B4 and others were acted on in PRs #108 and #109 and later parameter sets (1.5.0 onward); some findings are still open. Unsure which, so check a finding against the code before acting. "Defaults to confirm" items are still Zach's to decide. |
| [2026-11-12-ux-walkthrough.md](reviews/2026-11-12-ux-walkthrough.md) | **Historical** | Judged against `main` of 2026-11-11. Largely acted on by PRs #132 and #133 (the Ledger look and labelling tools). Unsure which findings remain. |
| [2026-11-13-ui-review-132-133.md](reviews/2026-11-13-ui-review-132-133.md) | **Historical** | Review of PRs #132 and #133. Fixed in #135 (findings 8–10, 16–20) and #136 (finding 21); the rest are unsure. |
| [img/](reviews/img/) | **Historical** | Screenshots for the 2026-11-13 review. |

## [architecture/](architecture/README.md)

As-built diagrams, drawn from the code and migrations. Dated 2026-11-02; flows updated 2026-11-15 and modules/context 2026-11-10.

| File | Status | Note |
|---|---|---|
| [README.md](architecture/README.md) | **Current** | Page list. |
| [01-system-context.md](architecture/01-system-context.md) | **Current** | What runs where, and the outside systems. |
| [02-modules.md](architecture/02-modules.md) | **Current** | Backend modules and web app. New modules since 2026-11-10 may be missing. |
| [03-data-model.md](architecture/03-data-model.md) | **Current**, **stale in part** | Describes the schema after Flyway V21 (35 tables). The code is at V37, so tables from V22 on (including photo-first identity, V37) are missing. Use the migrations as the source. |
| [04-flows.md](architecture/04-flows.md) | **Current** | Updated 2026-11-15 for photo-first identify (PR #142). |

## Folders of images

| Folder | Status | Note |
|---|---|---|
| [ux-review-2026-11-01/](ux-review-2026-11-01/) | **Historical** | Screenshots for [ux-review-2026-11-01.md](ux-review-2026-11-01.md). |
| [screenshots/](screenshots/) | **Historical** | Brand-direction (Hobby energy) and early Ledger captures from 2026-11-13. The Ledger look they show is the live design; the "Hobby energy" ones are superseded. |

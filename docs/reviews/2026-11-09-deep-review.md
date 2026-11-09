# Rawmark deep review, 2026-11-09

An independent review of everything built so far: architecture, the soundness of the valuation and offer design, and the product. Review only; no code was changed.

**What it's based on.** `CLAUDE.md`, `docs/` (HLD, offers, PokeTrace pilot, the 2026-11 architecture review, the 2026-11-02 audit, the 2026-10-26 security review and offer sanity check, beta readiness), the code in `backend/` and `web/` at `75a8b55` (PR #95), the PR list, and the project memory files.

**What it isn't based on.**
- The methodology Claude Doc was not re-read. The math is checked against `CLAUDE.md`, which summarises it.
- The read-only prod check (`deploy/prod-check.sh` over SSH) was blocked by this session's permissions, so nothing here says anything about prod's current health.
- The backend suite ran locally: 488 tests, 0 failures, **144 skipped**, because Docker wasn't running and the Testcontainers tests skip without it. CI requires Docker, so the last green CI on main is the real signal. The flow tests were not re-run here.
- `web/` (about 12k lines, 23 test files) was skimmed, not reviewed line by line.

Severity: **High** = can lose a user money or trust, or lose data. **Medium** = wrong or fragile but bounded. **Low** = hygiene. Effort: S ≈ under a day, M ≈ a few days, L ≈ a week or more.

## Executive summary: top 10

| # | Recommendation | Severity | Effort | Section |
|---|---|---|---|---|
| 1 | Wire in the raw-sale price. The rule "use the raw-sale price for that condition if it's higher" is in `CLAUDE.md` and in the pricer, but the app never passes raw prices, so offers can sit below what the card sells for raw. Until it's wired, refuse or flag any offer below the card's recent raw near-mint sales. | High | M | B1 |
| 2 | Stop a provider grade of 10 from becoming a 60% chance of a PSA 10. The spread piles the "+1" mass onto 10, and two agreeing providers add no penalty. The PSA 10 price dominates the offer, so this is the largest overpayment risk in the model. | High | S | B2 |
| 3 | Build the buyer's "grade it came back with" loop. It is the only calibration data the firm price can ever get, it's the data moat, and nothing collects it today. | High | M | C1 |
| 4 | Re-check the price when a card is posted and when it's accepted. A buyer can take a card at a price computed up to 21 days earlier, with no look at the current price table. | High | S | B3 |
| 5 | Send the two emails ("a card was posted", "a buyer took your card"). Listings last 14 days and nobody is told they exist. For a two-sided market this is the cold-start problem, not polish. | High | M | C2 |
| 6 | Guard the PokeTrace day averages. A day's average carries its outliers into a median that isn't trimmed below 10 sale days, the `low` and `high` fields are ignored, and a revised day is never updated. | Medium | S–M | B4 |
| 7 | Make confidence reflect the thinnest price that matters. One sale day counts as an observed price, and the probability-weighted count can hide a thin most-likely grade behind a deep neighbour. | Medium | S | B5 |
| 8 | Decide how Rawmark gets paid and how buyers pay, before any buyer Zach doesn't know joins. There is no take rate, payment is off-platform on trust, and postage is billed by hand. | High (later) | L | C3 |
| 9 | Get one backup copy off the Cloudflare account and schedule the restore test. Everything, photos and backups, sits in one R2 account with a 24-hour recovery point. | Medium | S | A1 |
| 10 | Reduce the single-vendor price risk. PokeTrace is one anonymous operator with thin terms and it is now the only live source. Keep the raw pulls exportable and keep the fallback vendor warm. | Medium | S–M | C4 |

The short version: **the engineering is in better shape than the model inputs.** The pipeline is replayable, append-only and well tested. The risk now is in three uncalibrated places that all push the same way: the condition spread (too generous at the top), the missing raw-price floor (too stingy at the bottom), and offers that go stale. None of that can be tuned without outcome data, and the product doesn't collect outcome data yet. Items 1–5 are the ones to do before more testers arrive.

---

## A. Architecture

### What's right (keep it)

- **Runs are replayable.** A valuation run stores its canonical inputs with a SHA-256 and `replay()` recomputes and compares every number (`valuation/ValuationService.java`, `ValuationEngine.java`). Offers store their inputs, ladder, trace and snapshot set id. This is the single best decision in the codebase: any price ever shown can be explained later.
- **The math is a pure module.** `valuation-core` has no Spring, no database and no clock. Parameter sets are versioned and immutable (`parameter-sets/1.2.0.properties`), so a parameter change is a new version, not an edit.
- **Append-only where it matters,** with undo by appending (voids, cancellations, mapping rows, listing events), and database guards behind the application rules.
- **Pricing is serialised** with a session advisory lock on its own connection (`pricing/PricingLock.java`), and a busy refresh returns 409 instead of queueing.
- **PokeTrace mappings need an admin to confirm,** and a mapping that disagrees with our card needs a reason (`pricing/PokeTraceMappings.java`). A wrong mapping is the worst silent pricing error available, and this closes it.
- **Fail-closed configuration.** Every external feature is off unless its environment variable is set, and the prod profile refuses to start without its variables (`application.yml`).
- **CI is strict:** pinned action SHAs, Docker required so integration tests can't silently skip, web lint, unit tests and Playwright.
- **The 2026-11 architecture review was acted on.** Most of its 58 findings are closed; the open ones are listed in A5 and not repeated.

### Bugs and risks to fix

**A1. Backups share an account with the data, and the recovery point is 24 hours.** *Medium · S*
- Evidence: `deploy/backup/backup.sh` and `schedule.sh` run a nightly `pg_dump` to an R2 bucket in the same Cloudflare account as the photo bucket. The 2026-11 review deferred an off-account copy (D1). The weekly restore test is an open follow-up, not a schedule.
- Why it matters: one account lockout, billing lapse or leaked token takes photos and backups together. The bucket lock helps against deletion, not against losing the account. The append-only tables are the business's evidence and can't be rebuilt.
- Recommendation: add a second `rclone` remote on another provider (Backblaze B2 or a Hetzner Storage Box) to the same script, and make the restore test a scheduled job that pings a healthcheck. A 24-hour recovery point is fine for a beta; revisit when deals per day make a lost day expensive.

**A2. Deploys build on the production box and can't roll back.** *Medium · M*
- Evidence: `deploy/deploy.sh` builds images on the 4 GB server after a backup; CI publishes no image (`.github/workflows/ci.yml`). Open as #37/D10 in the 2026-11 review.
- Why it matters: a Gradle build competes for memory with the running backend (capped at 1536m), and a bad deploy is fixed by building the previous commit, under pressure, on the same box.
- Recommendation: build and push images to GHCR in CI, tagged by commit; deploy by tag; keep the previous tag as the rollback. Flyway migrations stay forward-only, so write down that a rollback is only safe across a deploy with no migration.

**A3. The nightly snapshot is one transaction over every comp in memory.** *Medium (grows) · M*
- Evidence: `pricing/PriceSnapshotService.java:86` (`@Transactional` on `snapshotAll`), loading a 13-month lookback of all comps and running two passes.
- Why it matters: with PokeTrace's one-year first pull, comps grow by hundreds of rows per mapped card. On a backend capped at 1536m this becomes the first out-of-memory failure, and a failure rolls back the whole night's prices. It's fine at tens of cards and not at thousands.
- Recommendation: keep pass one (era ratios) as a read, then price and commit per card or per batch, with the snapshot set marked complete at the end so readers never see half a set. Add a log line with card and comp counts and duration so the growth is visible before it fails.

**A4. A label is bought over HTTP inside a database transaction with a lock held.** *Low now · S*
- Evidence: `offers/Shipping.java:105-111` says so in its own comment; `offers/DealService.java:70-78`. Open as #24/D7.
- Why it matters: a slow EasyPost call holds a pooled connection and the card's lock. At beta volume this is harmless, and the design choice (the accept commits first, so a label failure never undoes a sale) is correct. Leave it, but put a short client timeout on the EasyPost call if there isn't one.

**A5. Open items from earlier reviews that still deserve a slot.**
- 2026-11 architecture review: #26 listing status derived in four places, #27 refusal reasons matched as message text, #28 missing indexes on `listing_event.listing_id` and `sale_listing.offer_id`, #32/D9 hand-written API types in the web app, #44/D12 no data-deletion procedure, #46 no `@RestControllerAdvice`. Of these, **#28 is an S and should just be done**, and **#44 matters before strangers join** (photos carry addresses and EXIF).
- 2026-10-26 security review: #7 providers receive EXIF originals (strip location before sending), #8 Caddy and the backup runner run as root.
- **Eleven Dependabot PRs (#96–#106) are open.** Batch-merge the patch and minor ones weekly; the backlog only gets harder.

**A6. Monitoring covers "is it up", not "is it right".** *Medium · S*
- Evidence: uptime ping, Sentry on new issues, a healthcheck ping from backups. Nothing alerts when the 06:30 pricing run fails, is skipped as busy, or ingests zero rows.
- Recommendation: have `PricingRefresh.runOnce` ping a healthcheck on success with the row count, and alert on silence. A dead price feed degrades quietly: tables go stale and confidence drops a level a week, which looks like "fewer offers", not an error.

---

## B. Soundness of design

### What's right (keep it)

- **The code matches `CLAUDE.md` on the core formulas.** FMV, RV, QSV and FLV, the stress shift with its cap at the grade above, never stepping up, grade 1 never anchoring another grade, per-grader tables with no pooling, graded cards valued at gross with no stress, and the offer ladder with margin by confidence are all implemented as written and traced (`CardValuator.java`, `GradePricer.java`, `OfferLadder.java`).
- **Counting PokeTrace sale days instead of the noised count** is the right call. A lower bound on sales can only lower confidence and liquidity, never raise them.
- **Staleness lowers confidence, never silently reuses.** Money is rounded in one place (`Money.java`).
- **Centering as a cap, never averaged,** carried over a retake, with a recorded override for reviewers, is conservative and auditable.
- **Retake invalidation** is enforced where it counts: posting refuses an offer whose photos changed (`offers/ListingService.java:104-109`).

### Bugs and risks to fix

**B1. The raw-sale price rule isn't applied anywhere.** *High · M*
- Evidence: `CLAUDE.md` step 3: "use the raw-sale price for that condition if it's higher". The pricer supports it (`GradePricer.java:125-126`), but the snapshot passes an empty map (`pricing/PriceSnapshotService.java:140`) and valuations net the table with `GradePriceTable.netOf`, which has no raw input (`GradePriceTable.java:37-41`, called from `valuation/ValuationEngine.java:163`). It has been carried as a known gap since M3 (`docs/m3-progress.md:72`, `docs/m4-progress.md:125`). PokeTrace now ingests raw near-mint and lightly-played sales, and they are used only for the sales rate.
- Why it matters: grading cost is a flat $32 plus 2%, so a PSA 6 or 7 of a $60 card nets to almost nothing, while the same card sells raw for real money. The pilot already shows it: 5 of 22 offers were below the lowest raw near-mint sale of the last 90 days (`docs/poketrace-pilot.md:147`). A seller who checks eBay sees a lowball and leaves. It also understates FMV, which wrongly excludes cards under the $25 minimum.
- Why it's hard: nothing maps a raw condition to a grade. That needs a decision, not just code.
- Recommendation, in two steps:
  1. **Now (S):** at quote time, compare the firm price with the card's recent raw near-mint sales. Below the lowest one, show the admin a flag and tell the seller plainly, or refuse. No formula change, just a sanity band, and the data is already in the table.
  2. **Then (M):** pick a conservative mapping and wire it through the snapshot, for example near mint → the price for grades 7 and 8 only, lightly played → 5 and 6, never 9 or 10, using the low end of raw sales. Record it as a decision and a new parameter set.

**B2. A provider grade of 10 becomes P(10) = 0.60.** *High · S*
- Evidence: `condition/DistributionBuilder.java:104-108`: "mass past 10 … stays on the edge grade". The default spread is +1: 0.10, 0: 0.50, −1: 0.25, −2: 0.10, −3: 0.05 (`ConditionProperties.java:33-34`). For a provider grade of 10, the +1 mass lands on 10. Two providers within 1.0 of each other give confidence A and no downward penalty (`DistributionBuilder.java:74-90`).
- Why it matters: real PSA 10 rates on raw cards that look perfect in photos are well under 60%. The PSA 10 price is often 2–8× the 9 (`docs/offer-sanity-2026-10-26.md`, finding 4), so this one cell drives the offer. It is the exact opposite of "low photo confidence shifts probability down": the edge rule shifts it up. A provider 9 gives P(10) = 0.10, so the jump from 9 to 10 is sixfold.
- Also: a system distribution never puts any probability on Ungradable. A photo model can't see a trimmed or recoloured card, so a small U floor is the conservative default.
- Recommendation: drop mass that would pass 10 onto the grade itself only up to a cap, and send the rest down (for example cap P(10) at 0.25–0.30 until outcome data says otherwise). Add a small configurable U floor. Both are parameter changes with one unit test each. Every spread number is a guess until C1 exists; say so on the review page.
- Today's mitigation: providers are off in prod and Zach enters every distribution by hand, so this bites the day a provider is switched back on.

**B3. An accepted price can be 21 days old.** *High · S*
- Evidence: an offer lasts 7 days and a listing 14 from posting. `ListingService.post` checks only that the offer hasn't expired and the photos haven't changed (`offers/ListingService.java:84-109`); `accept` checks status, self-dealing and address, and nothing about price (`ListingService.java:176-198`).
- Why it matters: modern chase cards move 20–30% in three weeks. Buyers choose which stale listings to take, so they take the ones that fell behind a rising market and skip the ones above a falling one. That is adverse selection against the seller in one direction and against the buyer's trust in the other, and the 10–17% margin is the only cushion.
- Recommendation: at post and at accept, re-run the ladder against the current snapshot. If the price has moved more than a threshold (say 10%), close the listing with "the price has changed, get a new offer". Recording a doubt already voids live offers, so the mechanism exists. Consider shortening listings to 7 days while volume is low.

**B4. PokeTrace day averages go into the median unguarded.** *Medium · S–M*
- Evidence: one comp per tier, source and day at the day's `avg` (`pricing/PokeTraceDays.java:76-95`); `low` and `high` are never read. Trimming starts at 10 comps (`GradePricer.java:308-312`). Inserts are `on conflict … do nothing` (`pricing/CompIngestionService.java:130`), so a day whose average PokeTrace later corrects keeps the first value. Volatility has no outlier handling, and Base Charizard's σ came out at 20.48 against an expected 0.08 (`docs/poketrace-pilot.md:146`).
- Why it matters: a day with one $40,000 sale and two $28,000 sales becomes one $32,000 comp. With 2–9 sale days the median takes it untrimmed. This errs upward, which the principles forbid.
- Recommendation:
  - Reject or down-weight a day whose `high / low` is beyond a ratio, or use the day's `low` when the spread is wide.
  - Update a day's price when the source revises it, as a new appended row, not an overwrite.
  - Winsorise the 30-day changes in `Volatility` before lenders resume; a σ above 1 should fall back to the 20% default and drop confidence, not be used.
  - Detect a pull gap: later pulls ask for `30d`, so an outage longer than 30 days leaves a hole nobody sees.

**B5. Confidence can hide a thin price.** *Medium · S*
- Evidence: one comp in the extended window is an observed price (`GradePricer.java:296-304`: the window extends when there are fewer than 2, then only an empty window returns null). Comps confidence is the probability-weighted count of observed sales across grades (`CardValuator.java:246-255`).
- Why it matters: a card with 40% on a grade 9 with 1 sale day and 30% on a grade 8 with 30 sale days gets a weighted count near 9.4, which is confidence B, while its most likely grade rests on a single day's average. Observed prices are also not forced to be monotone, so a lone high sale at 8 can sit above the 9; the ladder caps at the grade above for offers, but FMV and RV use the uncapped table.
- Recommendation: make comps confidence the worse of the weighted count and the count at the modal grade; treat a grade with fewer than `minSalesInWindow` sale days in 180 days as derived, not observed. Both are small changes in `valuation-core` with a new parameter set.

**B6. The sales rate mixes markets.** *Low · S*
- Evidence: `PriceSnapshotService.java:201-206` counts every comp, raw and graded, all sources, in 90 days.
- Why it matters: the speed discount applies to a graded resale, but a card with heavy raw volume and three PSA sales a month is counted as liquid. With sale days as a lower bound this mostly errs conservative, so it is low priority. Before FLV is used again, count the depth discount from graded sales only.

### Decisions Zach should revisit

These are working as decided. The question is whether the decision still holds with live data.

1. **A raw card can be offered more than a slabbed PSA 9 sells for** (offer sanity finding 4). The formula allows it whenever the 10 sells at a large premium. No vendor will accept that, and after B2 the 10's weight will fall, but a hard cap is cheap insurance: firm price ≤ the net price of the modal grade, or ≤ the lowest recent PSA 9 sale.
2. **The card's own "steepest ratio" includes the 9→10 step** (`GradePricer.java:255-270`; offer sanity finding 6). That repeats the PSA 10 scarcity premium down the ladder: a Base Charizard PSA 7 came out at 13% of its 8. `CLAUDE.md` already treats PSA 1 as its own market; the 10 is one too. Excluding the 9→10 step is still conservative and far less wrong.
3. **The margin spread is narrow.** A to C moves the price 8%, while C can carry twice the grade risk. The sales rate moves it 19%. If vendors push back, widen C before lowering A.
4. **"Firm price" and "subject to authentication in hand" contradict each other** for unverified high-risk cards. Vintage holos are where both the money and the fakes are. Either keep them out of instant offers until authentication is cheap, or say on the offer exactly who decides and what happens to the label cost when a card fails.
5. **Grading cost zeroes the low grades and there is no bulk floor.** Intended, and fine for collateral. For offers, B1 fixes most of it.
6. **PokeTrace went live on 2026-11-07 while the pilot's stay-or-cancel decision is dated around 2026-11-30.** Make the decision explicit and note what the fallback would cost in rework.
7. **The methodology doc is 15+ decisions behind `CLAUDE.md`.** The doc is declared the source of truth and "the doc wins" on conflict. Today that rule would undo decisions. Either apply `docs/methodology-changes-2026-10-26.md` and the later ones, or change the rule so `CLAUDE.md` wins for anything marked "not yet in the methodology doc".

---

## C. Soundness of product

### Is the instant-offer beta a good wedge?

**Yes, with one condition.** A firm, no-re-grade price on a raw card is a real gap: the competitors named in the project notes all re-offer after inspection. It also generates exactly the data the lender and insurer products need (photos, predicted distributions, real transaction prices), which the paused side could never have produced on its own. Pausing lenders and insurers was right: that side has a legal gate, long sales cycles and no data.

The condition is that the wedge only pays off if outcomes come back. Today the loop is open (C1).

### Bugs and risks to fix

**C1. Nothing records what grade a bought card received.** *High · M*
- Evidence: `outcomes/` takes grades from contributors with admin-verified certs. No offers code references it, and `docs/beta-readiness.md` lists "buyers can record the grade a card came back with" as an unticked should-have, described there as "the calibration data the firm price depends on".
- Why it matters: every number in B2 is a guess, the validation target (±1 grade 80% of the time) can't be measured, and "data is the moat" has no data. Each deal without an outcome is a wasted observation, and a beta has few deals.
- Recommendation: on a completed deal, give the buyer "I graded this card" with grader, grade and cert number, feeding the existing outcomes table with the offer id. Make it worth doing: show the buyer their own hit rate against our prediction. Also record "resold raw for $X" and "kept", so non-graded outcomes aren't mistaken for missing data, since buyers will mostly grade the best cards.

**C2. No notifications on a two-sided market.** *High · M*
- Evidence: the seller's status page says "We don't send notifications yet, so check back here" (`offers/CardStatusService.java`); `docs/beta-readiness.md` assigns a Discord DM by hand for each posting, taken card and label.
- Why it matters: a listing is open for 14 days and a buyer only sees it by visiting. With 3–5 buyers, most listings will expire unseen and collectors will conclude nobody buys. That is a false negative on the whole wedge.
- Recommendation: two transactional emails first (new listing to buyer orgs, "a buyer took your card" to the seller), then a Discord webhook for the test group. This is worth more than any pricing refinement except B1–B3.

**C3. No revenue, and payment runs on trust.** *High before strangers join · L*
- Evidence: `docs/offers.md:44-58`: the buyer pays the seller off-platform within 7 days of arrival; postage is billed monthly by hand; Rawmark charges nothing.
- Why it matters:
  - A seller ships first to a buyer who pays later. Among friends that works. Among strangers the first non-payment ends seller trust, and the only remedy is Zach deciding from photos.
  - Rawmark fronts every label on its own EasyPost wallet, insured up to $5,000, and recovers it by invoice. Zach already expects buyers not to pay it.
  - The 10–17% margin is the **buyer's** margin. Rawmark has no line in the formula. A take rate has to come out of the seller's price or the buyer's margin, and both are already thin (see below).
- Recommendation: the card-at-accept plan in the todo list (card saved at onboarding, label charged at accept) is right and should be the gate for opening up. Decide the take rate at the same time and put it in the parameter set as its own term, so it's visible in the trace. Holding card money in escrow is a much bigger step (money transmission); don't drift into it.

**C4. One price vendor, thinly documented.** *Medium · S–M*
- Evidence: `docs/price-data-sources.md:48-50` calls PokeTrace "the best data per dollar" and flags "thin terms and the anonymous operator". Permission is an email. Upstream is eBay data, and eBay's API licence restricts this kind of use for aggregators.
- Why it matters: the permission to keep data after the subscription ends is voided if they terminate for breach, and an anonymous operator can simply vanish. Every offer depends on the feed.
- Recommendation: save the written permission as a PDF outside email; keep the raw daily rows (already stored) exportable; keep the `PriceSource` port honest by running the fallback vendor's adapter against fixtures; ask PokeTrace for the same terms on a signed order form or at least in their ToS. Hand-entered comps remain a workable fallback at beta scale.

**C5. Unit economics are untested at the low end.** *Medium · S (analysis)*
- A seller receives roughly 56–73% of the grading-net expected value: 0.85 fees × 0.80–0.95 speed × 0.83–0.90 margin. The buyer then pays about $32 a card to grade, plus the label.
- On a $40–$80 card the fixed costs eat the margin: the label (several dollars with insurance) and grading are flat, the margin is a percentage. Expect buyers to take only cards above some price, probably $100+, while the $25 minimum invites sellers below it.
- The speed discount is driven by sale days, a lower bound, so liquid cards are discounted as if they were thin. This is conservative for the buyer and a visible lowball to the seller.
- Recommendation: from the first 20–30 deals, compute the buyer's realised return per card including label and grading, by price band. Raise the offer minimum to where buyers actually take cards, instead of leaving sellers with listings that expire.

**C6. Trust and fraud.** *Medium · S–M*
- **Self-dealing:** the shared-member check (`ListingService.java:190`) stops the honest mistake. Two accounts defeat it, but with no money through the platform the only gain is a better track record. Good enough until payment moves on-platform.
- **Adverse selection** is the real fraud-shaped risk, and it needs no bad actor. Sellers know things photos don't show (a faint crease, a smell, a repaired corner). The return reasons cover "doesn't match its photos", and the stored exact copies make that enforceable, which is well designed. Track returns by reason per seller and make the track record visible to buyers.
- **Counterfeits:** photo verification is rightly refused for high-risk cards. See decision 4.
- **Label abuse:** a label is bought the moment a buyer accepts. A buyer org that accepts and vanishes costs a label each time. Card-at-accept (C3) fixes it; until then keep buyer orgs invite-only.

**C7. Pre-grade providers.** *Low · S*
- Ximilar's written terms are good and it is off in prod by choice. CardGrade's reply is still pending, and its adapter has never seen a real response. With one provider the model tops out at confidence B, and with none, every distribution is Zach's by hand, which doesn't scale past the beta.
- Recommendation: don't wait on CardGrade. The path that compounds is C1 plus the centering measurer: own the measurement, use a provider as a second opinion. Check the second tester's consent before any of their photos are used for training (noted in memory).

### What to cut, change or do next

- **Cut for now:** more centering work on foil fronts, the lender and insurer reports, further UX polish, and new pre-grade adapters. None moves the wedge.
- **Change:** the spread at 10 (B2), the stale-price window (B3), the raw sanity band (B1 step 1).
- **Do next, in order:** B2 → B1 step 1 → B3 → C2 emails → C1 outcomes → A1 off-account backup → B4 → B5 → B1 step 2 → C3.
- **Then measure:** acceptance rate, returns by reason, buyer's realised return by price band, and predicted against returned grade. Those four numbers decide whether to open up, and whether lenders are worth resuming.

## Follow-ups outside this review

- The worktree has an uncommitted change to `backend/gradlew.bat` that predates this session. It was left alone and is not in this PR.
- Draft PRs #18 and #29 are still listed as open housekeeping in the todo list.
- Run `deploy/prod-check.sh` from a session that's allowed to; this review couldn't.

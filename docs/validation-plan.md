# Validation plan

2026-11-18. Moves effort from refining valuation rules to testing them. Pricing has gone from parameter set 1.2.0 to 1.8.0 in about three weeks, mostly tuned from reviews rather than results. This plan says, for each target, what data the test needs, how the metric is defined, how to compute it from what we store, how big a sample gives an answer, and which result changes which parameter.

The targets come from CLAUDE.md:

1. **Condition:** the actual grade is within ±1 of the predicted median at least 80% of the time, and below the stressed median less than 5% of the time. Tested on 1,000+ partner cards plus a 20–30 card controlled PSA set.
2. **Liquidation:** replaying the 2021–2023 decline, FLV computed at the peak covers the price 60 days later for at least 95% of cards.
3. **Offer outcomes** (added here): the offer price against what bought cards turned out to be worth once graded.

**Pre-registration.** The metric definitions, pass lines and parameter triggers below are fixed before anyone looks at results. A change to them is a dated edit to this file with a reason, made before the next result is read, never after.

**The repo is public.** Reports with per-card prices stay out of it, as the PokeTrace pilot report does ([poketrace-pilot.md](poketrace-pilot.md)). Only aggregate rates go in docs.

## What exists today

| What | Where | State |
|---|---|---|
| Returned grades | `grade_outcome` (V6; source CONTRIBUTOR, PARTNER, ADMIN, and BUYER since PR #111), cert checks in `outcome_verification` | Contributor and admin entry is live. Partner entry is planned for M5. Buyers record grades on deals (PR #111). |
| What a buyer did with a card | `deal_outcome` (V28, append-only): GRADED with a grade, RESOLD_RAW with a price, or KEPT. A correction is a new row, and only the latest row's grade counts (`DealOutcomes#retracted`). | Live since PR #111. |
| Predictions | `condition_assessment` (V5): every version is kept, with the distribution, confidence, author (SYSTEM or REVIEWER) and `basis` (provider grades, `spreadUsed`, `centeringCap`, `confidencePenalty`, `maxTenProbability`, `ungradableFloor`). Raw provider answers are in `provider_prediction`. | Complete for every assessed card. |
| Deal calibration | `DealOutcomeService#calibration`, `GET /api/admin/deal-outcomes`, admin Deal outcomes page | Live. Deals only, using the offer's assessment. Its ±1 test doesn't look at the grader, so a BGS or CGC grade is scored on PSA's scale. It reports no confidence interval. |
| **All-source condition calibration** | `GET /api/admin/validation/condition-calibration` (`validation` package, this PR) | **New.** Covers every source, PSA in the headline, Wilson bounds, and breakdowns by confidence, author and spread. Read-only, with no schema or parameter change. See check 1. |
| Offers | `card_offer`: `ladder` (grade, probability and offer price per rung), `price`, `buyer_margin`, `confidence`, `parameter_set`, `inputs`, `trace`, `raw_nm_*`; view `v_offer_outcomes` | Everything check 3 needs from the offer side is stored. |
| Valuation runs | `valuation_run` and `card_valuation` (append-only, with inputs enough to replay); `GET /api/admin/valuation-runs/{id}/replay` | Lender and insurer runs are paused, so there are few runs. Offers store the same card inputs. |
| Price history | `sold_comp`: PokeTrace since 2026-11-07, a 1-year backfill per mapped card, then 30 days a night. Revisions are appended (1.7.0). Guarded days go to `comp_exclusion`. | 12 months is enough for σ. **The 2021–2023 decline is not covered** (see check 2). |
| Analysis workspace | `analysis/` | Only the PokeTrace puller. No read-only database role. Of the planned views, only `v_offer_outcomes` exists. No backtest code. |

**Volumes are unknown from this session.** The first action is to call the new endpoint on prod and write the counts here: PSA outcomes with an earlier assessment, outcomes with none, mismatched certs, and graded deals. Expect tens, not hundreds.

## Check 1: condition calibration

### Data

- **Needed:** for each card, photos and an assessment made *before* it was graded, a returned PSA grade, and a cert the admin has checked.
- **Have:** the pipeline stores all of it (above). Contributor, admin and buyer grades are flowing. Partner grades come with M5.
- **Missing:** volume, and an unbiased sample. Cards that get graded are the ones their owners expect to grade well. Buyers grade the promising ones and keep or resell the rest raw. That biases the sample towards high grades.
- **Controlled PSA set (20–30 cards):** picked at random from intake, stratified by era (WOTC, EX–BW, XY–SM, SWSH on) and by our predicted median (≤ 6, 7–8, 9–10). Photograph them, freeze the assessments, then submit all to PSA whatever they look like. At the methodology's grading cost ($25 fee, $5 shipping, $2 insurance) that is $640–$960 plus the cards, and about 6–10 weeks of turnaround. It is the only part of the sample with no selection bias.
- **Licensing:** our own photos, predictions and returned grades. CardGrade's written confirmation of its terms is still pending (hld.md §10, M2 status). Don't store or train on its outputs beyond what that confirmation allows. The calibration reads our distributions, not CardGrade's raw answers, but a refit of the spread on its grades is training on its output. Hold that until the confirmation arrives.

### Metric (exact)

- **Population:** every `grade_outcome`, except a buyer's grade that the buyer has since corrected or taken back. A cert found MISMATCHED is left out. **Only PSA results make the headline,** because the distribution is on PSA's scale. Other graders are listed apart for information.
- **Prediction:** the card's latest `condition_assessment` created before the grade was recorded and before the grading date, when there is one. That way nothing learned from the slab leaks in. An outcome with no such assessment is counted as `unpredicted` and left out.
- **Predicted median *m*:** the lowest grade, ordered U < 1 < … < 10, at which cumulative probability reaches 0.5.
- **Stressed median *s*:** the median after `GradeDistribution#shiftDown` by 3 grades at confidence C or D, and by 2 otherwise (FLV's FMV↓). U is unchanged.
- **Returned grade *r*:** half grades fold down. A result with no number ("Authentic") is U, which counts as 0.
- **Hit:** |*r* − *m*| ≤ 1. **Below stressed:** *r* < *s*. **Error:** *r* − *m*. A negative error means the card came back worse than predicted.
- **Pass:** the 95% Wilson lower bound on the hit rate is ≥ 0.80, *and* the 95% Wilson upper bound on the below-stressed rate is < 0.05. The raw rates are shown too, but a lucky small sample can't pass on them.
- **Headline vs verified:** the headline includes unchecked certs. Verified-only is shown alongside. If they differ by more than the interval, use verified.
- **Diagnostics:**
  - Rates by confidence (A should beat B, and B should beat C).
  - Rates by author (does a reviewer's adjustment help?).
  - Rates by `spreadUsed` (tight vs default).
  - Expected against observed count per grade. The sum of each grade's probability over cards is set against how many cards came back at it, which tests the top (`maxTenProbability`) and U (`ungradableFloor`).

### Computing it

`GET /api/admin/validation/condition-calibration` computes all of the above from the stored rows on request, in one read-only SQL query, with no new view or table. `ConditionCalibrationTest` checks the scoring and the Wilson bounds. `ConditionCalibrationReportTest` checks the population rules against Postgres: a retracted grade is left out, and so is a grade recorded before the assessment.

Not yet in the report, and worth adding once the sample is big enough to split:

- an FMV band (to split the $1,000 one-provider threshold);
- the centering cap (only once `CENTERING_ENABLED` is on);
- whether the assessment still counted (photos retaken, provider retired) when the card was graded.

### Sample size

Wilson bounds at 95%. A single pass/fail needs:

| True rate | n to show hits ≥ 80% | | True rate | n to show below-stressed < 5% |
|---|---|---|---|---|
| 90% | 55 | | 1% | 110 |
| 88% | 95 | | 2% | 205 |
| 85% | 230 | | 3% | 415 |

- **About 400 PSA results** is enough to answer both questions at plausible true rates.
- **1,000** (the methodology's target) gives about ±2.5 points on the hit rate, enough to split the results by confidence.
- **Controlled set:** with 25 cards, **4 or more below the stressed median** fails the target (p = 0.034 if the true rate were 5%), and **16 or fewer hits** fails it (p = 0.047 if it were 80%). With 30 cards the lines are 5 or more and 19 or fewer. Fewer misses than that proves nothing on its own; it only rules out a gross failure.
- **Clustering:** cards from one submitter share photographer, lighting and taste. Report the rate per submitting org, and cap any one org at 25% of the sample, or say so when the cap can't be met.

### What result triggers what

| Result | Change to consider |
|---|---|
| Below-stressed upper bound ≥ 5% | The stress is too shallow: shift 3 grades at B as well as C, or raise `confidencePenaltyB` / `confidencePenaltyC`. |
| Hit rate fails and the mean error is clearly negative | The distribution sits too high: move mass down in `spread` (more on −1 and −2) or raise the confidence penalties. |
| Hit rate fails and the mean error is near 0 | The provider's median is noisy rather than biased, and no spread fixes that. Lower confidence instead: tighten `disagreementForB` and the one-provider A, and widen the spread so the stress covers it. |
| More 10s expected than observed | Lower `maxTenProbability` (0.25, "a starting guess, not calibrated"). |
| More U observed than expected | Raise `ungradableFloor` (0.02). |
| Tight spread rows hit worse than default ones | Drop or widen `tightSpread`. |
| A doesn't beat B | The A rules are wrong: `confidence.oneProviderMaxFmvForA` and the single-provider A. |
| Reviewer rows no better than system rows | Reviewer adjustments aren't earning their time. Review the reviewer guidance before scaling review. |
| Hit rate and below-stressed rate both far better than the targets, with tight bounds | The stress may be too deep, which costs lenders advance. Loosening is Zach's call and needs the bound, not the point estimate. |

Every change is a new parameter set. Its first calibration report is the one after its own cards come back.

## Check 2: liquidation replay (2021–2023)

### Data

- **Needed:** per-grade PSA sold prices (sale-level, or at worst daily or monthly) for a few hundred cards, **from 2020-01** (12 months for σ before a 2021 peak) **to 2023-12**. Raw near-mint prices too, for the 1.8.0 raw floor. Prices must be licensable for a backtest, and a backtest is "modelling prices" in eBay's terms, so the vendor's warranty on its eBay chain matters ([price-data-sources.md](price-data-sources.md)).
- **We don't have it.** PokeTrace's history starts 2020-12-29 but is thin: 50 sale days in 2021, 111 in 2022 and 179 in 2023 across the 27 pilot cards ([poketrace-pilot.md](poketrace-pilot.md)). PokemonPriceTracker starts in 2025.
- **Where to get it,** in order:
  1. **Card Ladder (Collectors):** sales since 2004 from 15 marketplaces, the right depth, but under personal-use terms. Ask for a one-off historical extract (Pokémon, 2020–2023, per grade, sale-level) under a written licence, even if no live feed follows.
  2. **PriceCharting:** the site has historic charts, but the API doesn't. Ask whether a commercial licence can include historic monthly values. Monthly values are enough for a **coarse replay**: σ is defined on 30-day changes, and the peak and 60-days-later prices can be read from month-end values. They are not enough for recency-weighted medians, so the replay would test FLV's market haircut on someone else's price, not our pricing.
  3. **Heritage, Goldin or Fanatics:** first-party auction archives, as part of the preferred-liquidation-channel ask. These are slower, but the lender story is the best.
- **Who:** Zach sends the asks. Price and terms decide which route.

### Metric (exact)

- **Universe:** chosen before results are read. Cards with PSA sales in the 12 months before *t₀* at grades 7–10 at least, stratified by era and by price band (< $100, $100–1,000, > $1,000). Aim for 400 or more. Exclude what the methodology excludes: FMV under $25, and cards with no PSA table.
- **Peak *t₀*:** the week in 2021–2022 when an equal-weighted index of the universe's 30-day median log PSA 9 price is highest. Because one date is one scenario, also run **every second week from 2021-01 to 2023-10**, and report the coverage at the peak and the worst window.
- **Condition:** 2021 cards have no photos with us, so each card is valued as a point mass at each grade it has sales in, 8, 9 and 10, as separate rows. FLV then applies its own stress shift (2 grades). A second, **market-only** variant sets the shift to 0, to see what `k·σ·√t` and ℓ cover by themselves.
- **FLV at *t₀*:** computed by **valuation-core itself** (`CardValuator`), with inputs built from the comps known at *t₀* only: per-grade prices by the methodology's rules, σ from the previous 12 months of graded comps, the sales rate, ℓ for one copy, and the current parameter set (1.8.0). Then rerun on older sets, to see whether 1.2.0 to 1.8.0 helped. The replay code reuses the engine, so it tests the code that prices offers.
- **Price 60 days later *P₆₀*:** the gross recency-weighted median of the same grade's sales in [*t₀* + 45, *t₀* + 75] days. With fewer than 2 sales in that window, the card-grade is dropped and counted.
- **Covered:** FLV(*t₀*) ≤ (1 − f) · *P₆₀*. FLV is net of selling fees f, so it is compared with what a forced sale would net.
- **Pass:** at *t₀* = peak, the 95% Wilson lower bound on coverage is ≥ 95%.
- **Also report:**
  - the median of FLV / ((1 − f) · *P₆₀*) (how much buffer FLV keeps; too much costs advance);
  - coverage by era, price band and grade;
  - how often the σ cap (1.0) and the fallback σ were used.

### Sample size and its limit

- **Cards needed:** 415 cards to show ≥ 95% if the true coverage is 97%, and 175 if it is 98%.
- **The real limit is time, not cards.** Every card at one *t₀* rides the same market move, so the coverage at the peak is one stress scenario, not 400 independent trials. The rolling windows give about 18 non-overlapping 60-day periods in three years. Read a pass as "survived the 2021–2023 decline", not as a 95% guarantee.

### What result triggers what

| Result | Change to consider |
|---|---|
| Fails, and the market-only variant fails too | Raise `k` (2) to the value that gives 95% at the peak, raise the fallback σ (20% a month), or lengthen σ's window: a calm, rising 2020 understates a 2021 fall. |
| Passes only because of the stress shift | The condition stress is doing market work. Keep it, but note that FLV has no separate market buffer. |
| Shortfall concentrated in thin cards | ℓ is too small for low sales rates. Raise the ℓ floor (10%) or make it steeper with the sales rate. |
| Shortfall concentrated at grade 10 | PSA 10 is its own market. Consider a deeper haircut on the 10 rung of FLV, as offers already cap 10 against 9. |
| The σ cap (1.0) binds often, or the guards exclude many days in the run-up | Revisit the σ cap and the 3× outlier guards against what the replay shows. |
| Coverage ≥ 99% with a median buffer above 40% | FLV is too deep for lenders. Loosening is Zach's call. |

### Forward check, possible now

Without any licence, our own data supports a forward version. For every offer since PokeTrace went live (2026-11-07), compute FLV from the offer's stored `inputs` with valuation-core. Then, 60 days later, compute *P₆₀* from `sold_comp` for the grades the distribution puts weight on, using the expected value over the distribution. The first results arrive from 2027-01-06. It won't contain a decline, so it doesn't meet the target, but it catches gross errors in σ and ℓ in a calm market. Add it as a read-only report next to check 1 once the first 60 days have passed.

## Check 3: offer outcomes

### Data

- **Have:** for every deal, `card_offer` (the ladder: probability and offer price per grade, the firm price, margin, confidence and parameter set) and `deal_outcome` (the grade and its cert, a raw resale price, or "kept"). PokeTrace prices at the grading date are in `sold_comp`.
- **Bias:** buyers choose which cards to grade, and kept cards give no label. Report the share of deals graded. The controlled set in check 1 is the unbiased complement.

### Metric (exact)

Over graded deals whose cert isn't MISMATCHED (PSA only for the condition parts), with *g\** the returned grade folded down:

1. **Condition ratio** `R_cond = offer_{g*} / price`, using the offer's own ladder rung at *g\**. Stored rows alone give this today. A calibrated distribution makes its mean 1, because the price is the probability-weighted ladder. A mean below 1 means buyers pay for condition that doesn't come back.
2. **Market ratio** `R_mkt = P_{g*}(graded date) / P_{g*}(offer date)`, gross PSA prices from `sold_comp`: how far the market moved while the card was out.
3. **Realised buyer return** `(1 − f) · P_{g*}(graded date) − C − price`, as a share of the price, with C the grading cost of the day. For RESOLD_RAW, use `resale_price · (1 − f) − price`. Compare it with the margin the offer intended (m, plus d, which the offer already takes).

Report each by confidence. The 10% / 13% / 17% margins by confidence (1.2.0) are right when the realised return is about the same at A, B and C. Also report:

- the share of deals where the buyer lost money;
- deals where the 10 cap (M = 2.5) bound and the card came back a 10 (upside the cap took from the seller);
- deals whose offer was flagged below raw sales against those that weren't.

### Sample size

- **Spread:** a one-grade miss moves a card's price by 30–50%, so take a per-deal standard deviation of about 0.4 for `R_cond`.
- **Mean within ±5 points:** about **250 graded deals** (1.96 · 0.4 / 0.05)², and about 750 to do that per confidence level.
- **At 50 deals:** only a gross error shows, a mean whose 95% interval misses 1 by more than 10 points. Use a bootstrap interval, because the ratios are skewed.

### What result triggers what

| Result | Change to consider |
|---|---|
| Mean `R_cond` below 1, interval excluding 1 | The distribution is too generous: the same levers as check 1 (spread, `maxTenProbability`). |
| Realised return differs by confidence | Re-set the margins by confidence (`offers` margin, 1.2.0). |
| Buyers lose on more than 25% of deals | Raise the margin, or tighten `reprice-threshold` if `R_mkt` drives the losses. |
| Deals capped at 10 often come back a 10 | Raise `maxTenToNineMultiple` (2.5). |
| Raw resales often above the offer | The raw floor is too low: the 25th percentile and the near-mint mapping (1.8.0). |
| Flagged-below-raw offers do worse for sellers than resale | Turn the flag into a refusal or a floor. That's Zach's call. |

Adding `R_cond` to the existing deal-outcomes report is a small follow-up. The ladder is already loaded there.

## Defaults to confirm, and which check could settle them

| Default (CLAUDE.md or config) | Check 1 | Check 2 | Check 3 | Notes |
|---|---|---|---|---|
| Tight spread +1 10%, 0 60%, −1 25%, −2 5% (2026-11-10) | **yes**, by `spreadUsed` | | via `R_cond` | |
| Default spread, `confidencePenaltyB` 10% / `C` 25% | **yes** | | via `R_cond` | "Starting values" in `ConditionProperties` |
| `maxTenProbability` 0.25, `ungradableFloor` 0.02 | **yes**, expected against observed | | partly | "A starting guess, not calibrated" |
| `confidence.oneProviderMaxFmvForA` $1,000 and FMV as the measure (1.6.0) | **yes**, A vs B, split by FMV band | | by confidence | Needs the FMV band in the report |
| FLV stress shift 2 grades, 3 at C | **yes**, below-stressed rate | partly (stress vs market) | | Methodology value, not marked to confirm, but this is its test |
| σ cap 1.0 (1.5.0) and fallback σ 20% a month | | **yes** | | Forward check sees the cap in calm markets |
| `k` = 2, `t` = 2 months, ℓ floor 10% and cap 50% | | **yes** | | |
| PokeTrace day guards: outside low–high, high > 3× low, > 3× median (1.5.0) | | partly (σ and the run-up) | partly (`R_mkt`) | Mostly a review of excluded days, not a calibration |
| Raw floor: 25th percentile, 2 sale days, near-mint for 7–10, lightly played for 5–6, MP and HP none (1.8.0) | | needs raw history | **yes**, raw resales and flagged offers | |
| `offers.maxTenToNineMultiple` 2.5 (1.4.0) | observed 10s | | **yes** | |
| Margins by confidence 10% / 13% / 17% (1.2.0) | | | **yes** | |
| Below-raw-sales flag, never refused (deep review B1) | | | **yes** | |
| `reprice-threshold` 10% (B3) | | | weakly (`R_mkt`) | Mostly an operational choice |
| Newest PokeTrace revision prices the day (1.7.0); pull gap 2 days | | | | **None.** Operational; no outcome measures them |
| `min-cards-per-ratio` 3 (era ratios) | | | | **None yet.** Needs a derived-price check: derived prices against sales once they appear |
| Retention periods (2026-11-12) | | | | **None.** Privacy choice |

## Order of work

1. **Now:** call `/api/admin/validation/condition-calibration` on prod and record the counts above (aggregates only).
2. **Now:** freeze condition and stress parameters for changes not driven by a check result (bug fixes excepted), so results can be attributed to a parameter set. That's Zach's decision.
3. **This month:** choose and photograph the 25–30 card controlled PSA set, then submit it. Results arrive in about 6–10 weeks.
4. **This month:** send the replay data asks (Card Ladder extract, PriceCharting historic values).
5. **From 2027-01-06:** the forward FLV check, as a read-only report.
6. **Follow-ups:** add `R_cond` to the deal-outcomes report. Make that report score PSA grades only, or show the grader. Partner grade entry (M5) is what gets check 1 to 1,000.

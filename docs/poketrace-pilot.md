# PokeTrace Pro pilot: one month of baseline comps

**Goal:** enough sold data on about 30 cards to rerun the offer sanity check ([offer-sanity-2026-10-26.md](offer-sanity-2026-10-26.md)) with real depth, to tune the 10/5/2 confidence thresholds and the buyer margin on evidence, and to give the vendor spot check credible prices.

**Budget:** $19.99, one month of [PokeTrace Pro](https://poketrace.com/pricing), with commercial use allowed. The data goes into calibration only. Nothing reaches live offers without a separate decision (see "Two ways in").

## What Pro gives us (OpenAPI v1.7.0, read 2026-10-26)

- **`GET /cards?search=&set=&card_number=`:** finds the card's PokeTrace UUID, with its TCGplayer and Cardmarket IDs.
- **`GET /cards/{id}`:** current prices by source and tier. For each tier it gives `avg`, `low`, `high`, `saleCount`, `median3d/7d/30d` and `confidence`. `gradedOptions` and `conditionOptions` list the tiers that exist for the card, and `totalSaleCount` gives the card's total sales. US data is eBay (all tiers) plus TCGplayer (raw only).
- **`GET /cards/{id}/prices/{tier}/history?period=7d|30d|90d|1y|all`:** **one row per day**, with `date`, `source`, `avg`, `low`, `high`, `saleCount` and rolling medians. This is the part that matters:
  - On a day with one sale, `avg = low = high` gives the exact sale price.
  - On a busier day we get the count, the range and the mean.
  - That's close to sale-level. The count was assumed exact, but every eBay row turned out to be flagged approximate (see "Day-1 results").
- **Not on Pro:** `/cards/{id}/listings` (individual sold eBay listings) is on the $98 Scale plan only.
- **Limits:** 10,000 requests a day, 30 per 10 seconds, auth by `X-API-Key` header.

## The cards

Don't guess prices up front: pull current prices for about 50 candidates (2 requests each), then let the data pick the 30.

**Selection rules, applied to day-1 data:**

1. Raw near-mint `avg` between $25 and $500, where beta offers land.
2. `totalSaleCount` high enough to be worth it. Rank by graded sales over the last 30 days and prefer cards with PSA 6–10 tiers all present.
3. 5 per era, so every era clears the 3-card bar for era step-down ratios. The vintage era also gets the cards with sales down to PSA 5.
4. Within each era, spread across $25–75, $75–200 and $200–500 raw.
5. English only. Swap in any card the Discord testers actually hold.

**Candidates.** Numbers marked ? are resolved by the `/cards` search on day 1.

| Era | Candidates |
|---|---|
| WotC (1999–2003) | Charizard Base 4/102 · Blastoise Base 2/102 · Venusaur Base 15/102 · Gyarados Base 6/102 · Ninetales Base 12/102 · Dark Charizard Team Rocket 4/82 · Dark Blastoise Team Rocket 3/82 · Lugia Neo Genesis 9/111 · Typhlosion Neo Genesis 17/111 · Gengar Fossil 5/62 |
| EX / DP / HGSS (2003–2011) | Charizard ex FireRed & LeafGreen 105/112 · Rayquaza ex Deoxys ? · Umbreon Prime Unleashed ? · Typhlosion Prime HeartGold SoulSilver ? · Garchomp C LV.X Supreme Victors ? · Gardevoir ex Dragon Frontiers ? · Lugia LEGEND (skip: two-piece card) |
| BW / XY (2011–2016) | Charizard Evolutions 11/108 · Charizard-EX Flashfire 12/106 · M Charizard-EX Flashfire ? · Mewtwo-EX Next Destinies 98/99 · Rayquaza-EX Dragons Exalted ? (full art) · Pikachu Evolutions 35/108 (likely under $25: a floor check) · Umbreon Dark Explorers ? |
| SM (2017–2019) | Charizard-GX Hidden Fates SV49 · Mewtwo & Mew-GX Unified Minds ? (alt) · Reshiram & Charizard-GX Unbroken Bonds ? (full art) · Pikachu & Zekrom-GX Team Up ? · Umbreon-GX Sun & Moon ? · Espeon & Deoxys-GX Cosmic Eclipse ? (alt) |
| SWSH (2020–2023) | Umbreon VMAX 215/203 · Pikachu VMAX 188/185 · Giratina V 186/196 · Lugia V 186/195 (the four from comps.csv, to cross-check) · Charizard V Brilliant Stars ? (full art) · Leafeon VMAX Evolving Skies ? (alt) · Mew VMAX Fusion Strike ? (alt) · Charizard UPC promo SWSH260 |
| SV (2023–) | Charizard ex Obsidian Flames ? (SIR) · Charizard ex 151 ? (SIR) · Iono Paldea Evolved ? (SIR) · Magikarp Paldea Evolved ? (IR) · Gardevoir ex Scarlet & Violet ? (SIR) · Mew ex 151 ? (gold) · Pikachu ex Surging Sparks ? (SIR) |

Charizard Base 4/102 stays in even though it's over $500 raw: it's the vintage anchor and the one card with a PSA 10 premium problem (finding 4).

## What to pull

For each of the 30 chosen cards:

| Pull | Requests | Why |
|---|---|---|
| `/cards/{id}` once a week (days 1, 8, 15, 22, 29) | 5 | Current tier snapshot and a sanity check of `saleCount` over time |
| History `period=all` for PSA_1 … PSA_10 | 10 × pages | Every grade's daily sales: grades 5–7 observed, σ from 12 months, and how far back it goes (the 2021–2023 replay) |
| History `period=all` for raw tiers in `conditionOptions` (NEAR_MINT, LIGHTLY_PLAYED, MODERATELY_PLAYED, HEAVILY_PLAYED, DAMAGED) | 5 × pages | Raw floor by condition, and U from DAMAGED |
| CGC_10, BGS_9.5 history | 2 × pages | Kept for later. Never priced into PSA (step 3) |

At 50 rows a page, a year of daily rows is about 8 pages per tier, fewer on quiet tiers. That's roughly 17 tiers × 8 × 30 cards ≈ 4,000 requests, so the full set fits in day 1's 10,000 with room for the candidates. Re-pull `period=30d` on day 29 to close the window.

**Storage, never in git (the repo is public, and PokeTrace bans redistribution):**

- Every response goes verbatim to `C:\Users\chief\pokemans-data\poketrace\<pull-date>\<card-id>\<endpoint>.json`, with the request URL and timestamp alongside. That honours "store everything" and gives provenance.
- A `manifest.csv` maps PokeTrace UUID → our card (name, set, number, era, raw band).
- Back the folder up off-machine.

## Two ways into the engine

### 1. Calibration only: no decision needed, build before buying

A **test-scoped** `PokeTraceDailySource implements PriceSource` reads the saved JSON and turns each daily row into sales for `CompIngestionService`, the same path the live source uses. It was first built to expand a day of n sales into n comps (low, high and n − 2 at `avg`). Since 2026-11-07 it uses the live sale-day rule instead (`PokeTraceDays`, below): one comp per day with a sale, at the day's `avg`, because the count is noised. Raw tiers map to `RawCondition`, and `raw` keeps the day's row.

`OfferSanityRunner` gets a second input, `POKEMANS_OFFER_SANITY_POKETRACE=<folder>`. It identifies the 30 cards from `manifest.csv`, ingests through the source, refreshes and runs the same sweep. It runs only in the Testcontainers database, so nothing reaches the app's data.

It answers:

- How many cards reach A or B at 10/5/2, and what thresholds would do.
- Whether the c8/c7 refusals and the steepest-step ladders (findings 2 and 6) go away once 5–7 are observed and era ratios exist.
- The real speed-discount tier per card (finding 8).
- How offers compare with the raw NEAR_MINT prices, the floor a vendor checks first (finding 5).
- σ measured from 12 months instead of the fallback.

### 2. Live offers: built 2026-11-07

PokeTrace gave written permission on 2026-11-07 (see [price-data-sources.md](price-data-sources.md)), and said `saleCount` is noised: a positive count is shifted by −2 to +4, never below 1, and zero stays zero. Zach chose the sale-day rule, and the production `PokeTraceSource` prices real offers with it:

- **One comp per sale day.** Each (tier, source, day) with a positive count is one comp at the day's `avg`, whatever the count. The count is never used as a count, so confidence, the sales rate and trimming all count sale days, a lower bound on the sales. A day with no sale, or no `avg`, is no comp, and today's row is left out until the day is over.
- **Day guards (parameter set 1.5.0, review B4, 2026-11-09).** At each price snapshot a PokeTrace day is left out of pricing, never shifted, when its `avg` lies outside its own `low`–`high` or its `low` isn't positive (`INCONSISTENT`), when its `high` is more than 3× its `low` (`WIDE_DAY`: one wild sale can't be told from the rest), or when its `avg` is more than 3× the median of at least 2 other sale days of the same tier, any source, within 90 days either side (`HIGH_DAY`). Only high days are caught: leaving out a low day would raise the price. Each left-out day is stored in `comp_exclusion` with the run, rule and reason, and shows in the trace of the grade it would have priced. The comp itself is kept. The thresholds are defaults for Zach to confirm.
- **σ cap (1.5.0).** A measured σ above 1.0 a month isn't used: the valuation takes the 20% fallback and drops confidence one level, as for a σ too thin to measure. The stored σ stays as measured. Winsorising the 30-day changes is still to do before lenders resume.
- **Which rows.** PSA tiers and raw conditions only, since a raw card is valued on PSA's table. Graded tiers come from eBay; raw from eBay or TCGplayer. Never Cardmarket or `cardmarket_unsold`.
- **Ids.** `externalSaleId` = `<poketraceId>|<tier>|<source>|<day>`, so re-pulls are no-ops. A card's first pull asks for `1y`, later ones `30d` (`pokemans.pricing.poketrace.backfill` and `recent`).
- **Mapping.** An admin maps each catalog card to its PokeTrace card from the card's prices panel, because PokeTrace's search doesn't split editions. Mappings are append-only (`poketrace_card`), unmapping is a row with no id, and a candidate that disagrees with our card (number, set, edition, variant, language, stamp) needs a reason. A price table uses only the comps of the card's current mapping, so a wrong mapping is undone by remapping, with the old comps kept.
- **Switching it on.** Off by default. Prod needs `POKETRACE_ENABLED=true`, `POKETRACE_API_KEY` and `PRICING_INGESTION_ENABLED=true` (the daily pull), all set by Zach.
- **Cost:** Pro, $19.99 a month for the whole catalog.

## Built (2026-10-26): day 1 is buy, set the key, run

Everything below is tested offline against made-up responses; nothing has touched the real API yet.

- **Puller:** `analysis/poketrace/poketrace.py`, standard library only. `candidates.csv` lists the candidates; `test_poketrace.py` is its offline tests (`python -m unittest test_poketrace`). `make_sample.py` writes a made-up pilot folder.
- **Source:** `PokeTraceDailySource` (backend test scope, `pricing` package). It reads the saved folder, expands daily rows as described above and goes through `CompIngestionService`. It keeps only eBay rows for graded tiers, and eBay or TCGplayer rows for raw tiers; never Cardmarket or `cardmarket_unsold` (listings). `PokeTraceDailySourceTest` checks it against `src/test/resources/poketrace-sample`.
- **Runner:** `OfferSanityRunner` takes `POKEMANS_OFFER_SANITY_POKETRACE=<folder>` (alongside or instead of the CSV). It adds a "lowest raw NM (90 days)" column, the floor a vendor checks first.

**Day 1, in PowerShell** (data lands in `C:\Users\chief\pokemans-data\poketrace` unless `POKETRACE_DATA_DIR` says otherwise):

```
cd analysis\poketrace
$env:POKETRACE_API_KEY = "<key from the PokeTrace dashboard>"
python poketrace.py find          # ~43 candidates -> candidates-found.csv (about 90 requests)
python poketrace.py shortlist     # -> manifest.csv; review it, swap cards by hand if you like
python poketrace.py history       # full daily history per tier (a few thousand requests, ~20-40 min; rerun to resume)
python poketrace.py check --comps C:\Users\chief\Pictures\pokemans-test-cards\comps.csv
```

Then the sanity check on the pulled cards (Docker running):

```
cd backend
$env:POKEMANS_OFFER_SANITY_POKETRACE = "C:\Users\chief\pokemans-data\poketrace"
.\gradlew.bat :app:test --tests "*OfferSanityRunner" --rerun
```

Weekly after that: `python poketrace.py snapshot`. On day 29: `python poketrace.py history --period 30d`. A later pull's row for the same day replaces the earlier one.

**What `check` decides.** If it reports "Rows look like rolling windows", the history isn't per-day sales: stop before importing and ask PokeTrace. Rolling windows would show as the same count and average repeated day after day. The cross-check line shows how many of the comps.csv sales fall inside PokeTrace's low–high range for that day.

**What the sample run showed** (made-up data, so only the mechanics count):

- **Deep cards reach confidence A.** With 11–14 sales a grade, PSA 5–10 are all observed. The sales rate comes out at 30–40 a month (HIGH, d = 5%), σ is measured and U is priced from damaged sales.
- **A card with sales only at PSA 9 and 10 gets no PSA table at all,** even though the era has ratios down to grade 5.
  - The cause: grades below 5 still need an era ratio. The card's own ladder needs two sold grades at least 2 apart, which it doesn't have. So "otherwise leave it unpriced" (CLAUDE.md step 3, 2026-10-17) applies to the whole table.
  - That's why the pull includes PSA 1–4: era ratios for the low grades are what let thin cards be priced at all.
  - It's worth Zach deciding whether one unpriceable low grade should sink the whole table.
- **The engine has 4 eras for step-down ratios, not 6:** WOTC; EX_TO_BW (from 2003-06); XY_TO_SM (from 2014-02); SWSH_ON (from 2020-02). The six candidate groups fold into those, so each engine era gets 5–15 cards.

## Day-1 results (2026-11-01, 27 cards, 61,293 rows)

The pull covered 27 cards rather than 30. Several candidates didn't match, others are under $25, and the old $500 ceiling was lifted to take in the cross-check cards and Zach's and the second tester's cards.

- **Prices check out.** 41 of 42 known sales fall inside PokeTrace's range for that day. The miss is a Heritage sale, and PokeTrace covers eBay and TCGplayer, not auction houses.
- **Rows are daily.** 2% of consecutive sale days repeat the day before, against a 20% stop line.
- **eBay counts are approximate.** Every eBay row (21,310 sale days) carries `approxSaleCount: true`, and graded data is eBay only. TCGplayer's 11,797 are exact. 1,257 of 13,474 one-sale rows show a price range, which one sale can't have, and known sales of two on a day show as 1. 47% of multi-sale rows have a single price. So `avg`, `low` and `high` are usable, but `saleCount` isn't a true count.
- **Why that matters:** confidence, liquidity discounts and the row expansion all use the count. Don't treat graded confidence as comparable to sale-level comps until PokeTrace says what the flag means.
- **History is shallow.** The earliest sale is 2020-12-29, but sale days by year are 50 (2021), 111 (2022), 179 (2023), 496 (2024), 6,715 (2025) and 25,555 (2026). So 12 months for σ works, while the 2021–2023 replay can't be done from this data.
- **Grade coverage.** Vintage has PSA 5–7 sales on most cards. Modern cards often have few or none below 8, so they lean on derived prices.

**Sanity check on the same data** (82,186 expanded sales, none rejected). The full report stays in the data folder, since PokeTrace bans redistribution and this repo is public:

- **Coverage:** at a likely PSA 9 and photo confidence B, 22 of 27 cards get a firm offer, 4 are over the $5,000 maximum and 1 has no PSA table.
- **Prices:** the engine's PSA 8 and 9 for Base Charizard sit within about 1% of PokeTrace's own 90-day median, which is what the method should do.
- **Confidence is flattered:** comps grade A in 387 of 486 rows. It rests on the approximate counts, so it can't be trusted yet. Recommendation: cap prices from these rows at confidence B until the flag is explained. (Superseded 2026-11-07: PokeTrace explained the flag as deliberate noise, and the live source counts sale days instead of `saleCount`, so no cap is needed.)
- **Thin top grades are unsafe:** Mewtwo-EX 98/99 shows a single PSA 10 sale far out of line with its raw price, and Base Charizard's PSA 10 days differ by more than 100× low to high. Trimming starts at 10 sales, so a tier with a few wild days goes through untouched. Wild days are rare overall (8 of 3,168 tier days beyond 3× the tier's median), but that test needs 5 days in a tier, so it misses the thinnest tiers.
- **Volatility is broken by those rows:** Base Charizard's σ comes out at 20.48, where the method expects something near 0.08. Offers don't use σ, but FLV does, so it needs outlier handling before lenders resume. (From 1.5.0 a σ above 1.0 falls back to 20%; see the σ cap above.)
- **Some offers fall below raw:** at a likely PSA 9, 5 of the 22 offers are under the lowest raw near-mint sale of the last 90 days, so a seller would do better selling raw.

## Timeline

| When | What | Who |
|---|---|---|
| Before buying | Done 2026-10-26 (see "Built") | Claude |
| Day 1 | Buy Pro, set the key, run the candidate pull, pick the 30, run the full history pull | Zach (buy and key), then the script |
| Day 1 checks | History depth (does `all` reach 2021?). Do PSA 5–7 tiers exist with counts? Cross-check the four comps.csv cards: are our Umbreon PSA 9 sales of 2026-10-19 and 10-20 in the daily rows? If depth or 5–7 coverage is poor, stop and email PokeTrace before using the rest of the month. | Claude |
| Day 2 | Email contact@poketrace.com: data retention after cancelling, and use in collector offers | Zach (send) |
| Days 2–5 | Rerun the sanity check on the 30, write `offer-sanity-<date>.md`, propose thresholds and margin with evidence | Claude |
| Weeks 2–3 | Vendor spot check with the new numbers; weekly snapshots keep running | Zach |
| Day 29 | Final `30d` pull, then decide: stay on Pro (if retention and offer use are confirmed and it's going live) or cancel | Zach |

## What it won't answer

- **Individual eBay listings:** titles, URLs, best-offer flags and anomaly flags are Scale only. Outlier trimming at n ≥ 10 works on expanded daily rows, so a single crazy sale on a busy day is blurred into the day's average.
- **Whether PokeTrace's own sources are licensable.** Their eBay data carries the chain-of-title question in [price-data-sources.md](price-data-sources.md).
- **Japanese cards:** they're a separate `game`, out of scope here.

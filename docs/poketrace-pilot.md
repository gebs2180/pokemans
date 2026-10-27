# PokeTrace Pro pilot: one month of baseline comps

**Goal:** enough sold data on about 30 cards to rerun the offer sanity check ([offer-sanity-2026-10-26.md](offer-sanity-2026-10-26.md)) with real depth, to tune the 10/5/2 confidence thresholds and the buyer margin on evidence, and to give the vendor spot check credible prices.

**Budget:** $19.99, one month of [PokeTrace Pro](https://poketrace.com/pricing), with commercial use allowed. The data goes into calibration only. Nothing reaches live offers without a separate decision (see "Two ways in").

## What Pro gives us (OpenAPI v1.7.0, read 2026-10-26)

- **`GET /cards?search=&set=&card_number=`:** finds the card's PokeTrace UUID, with its TCGplayer and Cardmarket IDs.
- **`GET /cards/{id}`:** current prices by source and tier. For each tier it gives `avg`, `low`, `high`, `saleCount`, `median3d/7d/30d` and `confidence`. `gradedOptions` and `conditionOptions` list the tiers that exist for the card, and `totalSaleCount` gives the card's total sales. US data is eBay (all tiers) plus TCGplayer (raw only).
- **`GET /cards/{id}/prices/{tier}/history?period=7d|30d|90d|1y|all`:** **one row per day**, with `date`, `source`, `avg`, `low`, `high`, `saleCount` and rolling medians. This is the part that matters:
  - On a day with one sale, `avg = low = high` gives the exact sale price.
  - On a busier day we get the count, the range and the mean.
  - That's close to sale-level. The count is exact, which is what confidence and liquidity depend on.
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

A **test-scoped** `PokeTraceDailySource implements PriceSource` reads the saved JSON and turns each daily row into sales for `CompIngestionService`, the same path a licensed source will use:

- `saleCount` 1 → one sale at `avg`.
- `saleCount` 2 → two sales, at `low` and `high`.
- `saleCount` n ≥ 3 → one at `low`, one at `high` and n − 2 at `avg`. That keeps the count exact, the range exact and the mean close.
- `externalSaleId` = `<uuid>|<tier>|<date>|<i>`, so a re-import is a no-op.
- `source` = `POKETRACE_DAILY`, and `raw` keeps the day's row.
- Raw tiers map to `RawCondition`.

`OfferSanityRunner` gets a second input, `POKEMANS_OFFER_SANITY_POKETRACE=<folder>`. It identifies the 30 cards from `manifest.csv`, ingests through the source, refreshes and runs the same sweep. It runs only in the Testcontainers database, so nothing reaches the app's data.

It answers:

- How many cards reach A or B at 10/5/2, and what thresholds would do.
- Whether the c8/c7 refusals and the steepest-step ladders (findings 2 and 6) go away once 5–7 are observed and era ratios exist.
- The real speed-discount tier per card (finding 8).
- How offers compare with the raw NEAR_MINT prices, the floor a vendor checks first (finding 5).
- σ measured from 12 months instead of the fallback.

### 2. Live offers: Zach's call, after the pilot

A production `PokeTraceSource` would price real offers from daily aggregates rather than individual sales. Three things to settle before that:

- **Methodology:** step 3 says "every price traces to *sold* comps with dates and counts". Daily rows have dates and counts but, on busy days, not each price. Options:
  - accept the expansion above;
  - treat a busy day as one comp with weight n;
  - cap the confidence of aggregate-sourced prices.
- **Licence:** confirm with PokeTrace in writing that collector offers priced from their data are allowed, and whether data can be kept after cancelling. The terms are silent on both; `PriceSource`'s own rule is "only added, and only enabled, once its licence covers" storage and use.
- **Cost:** staying on Pro is $19.99 a month for the whole catalog. That's cheap if the answers are yes.

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

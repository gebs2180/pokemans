# Decisions to confirm

Written 2026-11-18. Every parameter or rule that is live and marked "default to confirm", or labelled in a similar way
("starting value", "starting guess, not calibrated", "unsure"), in one place. Zach can confirm or change each one in a
single sitting.

- **Sorted by money at stake.** Offers and pricing come first, then confidence and FLV, then operations, privacy and
  display.
- **Nothing here changes a value.** Each "Zach's decision" line is a blank to fill in. A changed parameter-set value
  needs a new set (`1.9.0.properties`), because sets are immutable once a run references them. A changed property is an
  `application.yml` or environment edit.
- **Queries are written, not run.** They are read-only Postgres for prod. Run them on a replica or a restored backup
  where you can. Traces are stored as JSON arrays of `{subject, rule, value, detail}` steps. Ladders are stored as
  `[{grade, probability, price}]`. Grades are written as Jackson writes the `Grade` enum, so the queries match both
  `G10` and `10`.
- **Where defined** points at the current set, `backend/valuation-core/src/main/resources/parameter-sets/1.8.0.properties`
  (shortened to `1.8.0.properties`), or at the Java or YAML file named.

## Summary

| # | Item | Current | Affects | Recommendation |
|---|---|---|---|---|
| 1 | `offers.maxTenToNineMultiple` | 2.5 | Offers | Keep 2.5 |
| 2 | Raw-floor quantile | 0.25 | Pricing → offers, FMV, FLV | Keep 0.25 |
| 3 | Raw-floor minimum sale days | 2 | Pricing | Raise to 3 |
| 4 | Raw-floor grade mapping (NM 7–10, LP 5–6, MP/HP none) | as listed | Pricing | Keep. Revisit MP→3–4 once outcome data exists |
| 5 | Raw floor: a worse condition is capped at a better one's | on | Pricing | Keep (no real alternative) |
| 6 | `pokemans.offers.reprice-threshold` | 10% | Offers (market guard) | Keep 10%. Consider 7% if many voids are upward moves |
| 7 | PokeTrace day guard `maxSpread` (high/low) | 3× | Pricing guard | Keep 3× |
| 8 | PokeTrace day guard `maxMultipleOfTier` | 3× | Pricing guard | Keep 3× |
| 9 | PokeTrace day guard `minOtherDays` / `neighbourhoodDays` | 2 / 90 | Pricing guard | Keep 2. Shorten to 60 days only if the query shows trend misses |
| 10 | What counts as a PokeTrace revision | any cent change in avg, low or high | Pricing | Keep |
| 11 | `confidence.oneProviderMaxFmvForA` (and FMV as the measure) | $1,000, FMV | Confidence → offer margin | Keep $1,000 and FMV |
| 12 | Lowball flag (raw NM sanity band) | flag only; 90 days; lowest NM day | Offers (information) | Keep flag-only. Change the benchmark to the raw floor |
| 13 | `pokemans.condition.tight-spread` weights | +1 10%, 0 60%, −1 25%, −2 5% | Condition → offers | Keep until outcomes exist |
| 14 | `max-ten-probability` / `ungradable-floor` | 0.25 / 0.02 | Condition → offers | Keep both |
| 15 | `disagreement-for-b`, `confidence-penalty-b/c` | 1.5 / 10% / 25% | Condition → offers | Keep |
| 16 | `values.maxSigma` | 1.0 a month | FLV, confidence (lender side, paused) | Lower to 0.35 before lenders resume |
| 17 | `pokemans.pricing.min-cards-per-ratio` | 3 | Pricing (era ratios) | Keep 3 |
| 18 | `pokemans.pricing.poketrace.pull-gap-days` | 2 | Ops alert | Keep 2 |
| 19 | Retention periods | 90 / 365 / 30 days | Privacy | Keep |
| 20 | Other graders' centering rows marked "unsure" | as listed | Display only | Keep, with the visible footnote |

---

## 1. Offers: the cap on the 10 rung

### 1. `offers.maxTenToNineMultiple`: the 10 rung pays at most M × the 9 rung

- **Where:** `1.8.0.properties:137`, read in `ParameterSet.java:209` and applied in `OfferLadder.java:85-101`.
  Documented in CLAUDE.md step 8 and `docs/offers.md:56`.
- **Current:** M = 2.5, from parameter set 1.4.0 onwards (now 1.8.0). Proposed by the session on 2026-11-09, "2.5 to
  be confirmed".
- **Affects:** offers only. FMV, RV, QSV, FLV and the grade-first list keep the full ladder.
- **Evidence:**
  - A PSA 10 sells for 2–8× a 9. In the offer sanity check, Base Charizard's was 7.7× and Giratina's 3.4×
    (`docs/offer-sanity-2026-10-26.md:63-70`).
  - Without the cap, a 20% chance of a 10 lifted the Charizard c9 offer by $3,177, above a slabbed 9.
  - The cap is applied after f, d and m on both rungs, so 2.5 compares like with like.
- **Case for higher (3–4×):**
  - The buyer keeps the upside of a 10 (step 8). A tight cap gives clean modern cards, where 10s are common and the
    10:9 multiple is often 3–5×, less than they're worth.
  - Sellers of the cards most likely to be 10s are the ones who'll compare with other venues.
- **Case for lower (1.5–2×):**
  - P(10) from photos is the least calibrated number in the system (deep review B2), and the buyer carries the
    downside under a firm price.
  - 2× is roughly where a raw "likely 10" trades in practice.
- **Recommendation:** keep 2.5 until the buyers' "came back as" grades give a real P(10) hit rate (deep review C1).
  If that rate is below about half of the predicted P(10), lower M to 2.
- **Data that settles it:** how often the cap binds, and how much it takes off.

```sql
-- How often the 10 cap binds, and what it removes from the firm price (p10 x (uncapped - capped)).
with t as (
  select o.id, o.price, o.created_at,
         (select (r->>'probability')::numeric from jsonb_array_elements(o.ladder) r
           where r->>'grade' in ('G10','10')) as p10,
         (select substring(s->>'detail' from 'uncapped 10 rung ([0-9.]+)')::numeric
            from jsonb_array_elements(o.trace) s where s->>'subject' = 'offer_ten_cap') as uncapped10,
         (select (s->>'value')::numeric
            from jsonb_array_elements(o.trace) s where s->>'subject' = 'offer_ten_cap') as capped10
  from card_offer o
  where o.parameter_set >= '1.4.0' and not o.graded
)
select count(*)                                           as offers,
       count(*) filter (where capped10 is not null)       as cap_binds,
       round(avg(p10 * (uncapped10 - capped10)) filter (where capped10 is not null), 2) as avg_dollars_removed,
       round(avg(p10 * (uncapped10 - capped10) / nullif(price, 0)) filter (where capped10 is not null), 3) as avg_share_of_price
from t;

-- The 10:9 multiple across all raw offers' ladders (after net, cap at the grade above, f, d, m and the 10 cap).
select percentile_cont(array[0.25, 0.5, 0.75, 0.9]) within group (order by p10 / nullif(p9, 0)) as ten_to_nine_quartiles
from (select (select (r->>'price')::numeric from jsonb_array_elements(o.ladder) r where r->>'grade' in ('G10','10')) as p10,
             (select (r->>'price')::numeric from jsonb_array_elements(o.ladder) r where r->>'grade' in ('G9','9'))  as p9
      from card_offer o where not o.graded and o.parameter_set >= '1.4.0') x;
```

**Zach's decision:** ___

---

## 2. Pricing: raw-sale floors (parameter set 1.8.0)

Raw floors set `P_g = max(net graded, raw floor)` for each grade. They raise prices, and so every offer, FMV and FLV,
for cards whose graded ladder nets below their raw sales. That's most cards under about $100, because a $32 grading
cost is a large share of their price. Zach decided the rule on 2026-11-11. Four of its details are defaults.

### 2. `pricing.rawFloor.quantile`: which point of the raw sale days sets the floor

- **Where:** `1.8.0.properties:68` (comment at 65-67), used in `RawFloors.java`.
- **Current:** 0.25, the recency-weighted 25th percentile of the condition's sale days.
- **Affects:** pricing. The floor raises net prices for grades 5–10, and so offers, FMV, RV, QSV and FLV.
- **Evidence:**
  - Deep review B1 asked for "the low end of raw sales", never the average. The principles forbid shifting up.
  - Each PokeTrace sale day is already a day's *average*, so the 25th percentile of day averages sits above the
    25th percentile of single sales.
- **Case for higher (0.5, the median):**
  - It matches how graded grades are priced (the recency-weighted median), so a floored grade and an observed grade
    mean the same thing.
  - At 0.25 a seller can see the offer far below "what it sells for raw".
- **Case for lower (0.10, or the minimum):**
  - The floor feeds a firm price with no inspection. A raw "near mint" listing is the seller's label, often
    optimistic.
  - Day averages already smooth out the low sales.
- **Recommendation:** keep 0.25. It's the conservative middle, and the lowball query in item 12 shows how often offers
  still land below raw sales.
- **Data that settles it:** how much the floor lifts prices, and how far the 25th percentile sits from the median.

```sql
-- Latest snapshot per card: how often each grade is floored, and by how much over the net graded price.
-- (price is gross graded; the floor applies against the net, so compare with the run's grading cost of $32.)
with latest as (
  select distinct on (catalog_card_id) snapshot_set_id
  from price_snapshot where parameter_set = '1.8.0'
  order by catalog_card_id, as_of desc, created_at desc
)
select ps.grade,
       count(*)                                                   as cards,
       count(*) filter (where ps.raw_floor is not null)           as with_floor,
       count(*) filter (where ps.raw_floor > ps.price - 32)       as floor_binds_approx,
       round(avg(ps.raw_floor / nullif(ps.price - 32, 0)) filter (where ps.raw_floor > ps.price - 32), 2) as avg_lift_ratio
from price_snapshot ps join latest l using (snapshot_set_id)
where ps.grade <> 'U'
group by ps.grade order by ps.grade;
```

**Zach's decision:** ___

### 3. `pricing.rawFloor.minSaleDays`: fewest raw sale days for a floor

- **Where:** `1.8.0.properties:69`.
- **Current:** 2 sale days (90-day window, extended to 180 with fewer than 2).
- **Affects:** pricing, the same as item 2.
- **Evidence:** it matches the graded rule (`pricing.minSalesInWindow=2`). With 2 days, the 25th percentile is
  effectively the lower day.
- **Case for higher (3–5):**
  - With 2 days a single mislabelled listing (a "near mint" that's really played, or the wrong edition) can be half of
    the floor.
  - Unlike a graded price, a floored grade is *not* counted as derived (`rawFloor.derived=false`), so a thin floor
    costs no confidence.
- **Case for lower (1):** more cards get a floor, and a floor only ever helps the seller.
- **Recommendation:** raise to 3. A floor with no confidence penalty should need more evidence than a graded price
  that does carry one.
- **Data that settles it:**

```sql
-- How many floors rest on exactly 2 or 3 sale days, and whether they bind.
select ps.raw_floor_n, ps.raw_floor_window_days, count(*) as grades,
       count(*) filter (where ps.raw_floor > ps.price - 32) as binds_approx
from price_snapshot ps
where ps.parameter_set = '1.8.0' and ps.raw_floor is not null
  and ps.as_of >= current_date - 7
group by 1, 2 order by 1, 2;
```

**Zach's decision:** ___

### 4. The grade mapping: near mint floors 7–10, lightly played 5–6, moderately and heavily played floor nothing

- **Where:** `1.8.0.properties:60-64` (`pricing.rawFloor.NEAR_MINT`, `LIGHTLY_PLAYED`, `MODERATELY_PLAYED`,
  `HEAVILY_PLAYED`).
- **Current:** NM → 7,8,9,10; LP → 5,6; MP → none; HP → none. Grade 1 can never be floored (the code refuses it).
- **Affects:** pricing.
- **Evidence:**
  - Deep review B1 step 2 proposed NM → 7 and 8 only and "never 9 or 10".
  - Zach chose 7–10 (2026-11-11): a likely 9 or 10 is certainly near mint, and an unfloored 9 below the floor would
    cap the 8 back down through "at most the grade above". Only the MP/HP part is still a default.
- **Case for more floors (MP → 3–4, HP → 2):** low-grade probability gets priced from step-downs today, which the
  sanity check found "carry most of the price" and can sit at a few dollars (finding 6). A floor would replace a
  derived price with an observed one.
- **Case for none:** MP and HP labels are the least consistent across sellers, and a played raw card's buyer prices
  in damage nobody can see in photos.
- **Recommendation:** keep MP and HP at none for now. Add MP → 3,4 in a later set once deal outcomes show how
  often a card predicted 3–4 comes back as one.

```sql
-- How many raw MP/HP sale days exist per card (would a floor even form?).
select raw_condition, count(distinct catalog_card_id) as cards,
       percentile_cont(0.5) within group (order by days) as median_sale_days_90d
from (select catalog_card_id, raw_condition, count(distinct sold_on) as days
      from sold_comp
      where raw_condition in ('MODERATELY_PLAYED', 'HEAVILY_PLAYED') and sold_on >= current_date - 90
      group by 1, 2) x
group by raw_condition;
```

**Zach's decision:** ___

### 5. A worse condition's floor is capped at a better one's

- **Where:** `RawFloors.java:24` and `:81`. CLAUDE.md step 3.
- **Current:** on. If LP's floor comes out above NM's (thin or noisy data), it is cut to NM's, never stepped up.
- **Affects:** pricing, rarely.
- **Case for removing it:** none that's conservative. Without it, noise could pay more for a 5 than a 7.
- **Recommendation:** confirm as is.

**Zach's decision:** ___

---

## 3. Offers: re-pricing at post and accept

### 6. `pokemans.offers.reprice-threshold`

- **Where:** `OffersProperties.java:32` (default `0.10`). Not in `application.yml`, so it's set by the default or by
  the `POKEMANS_OFFERS_REPRICETHRESHOLD` environment variable (Spring drops the dash). Used in `OfferService.java:199` and
  `ListingService.java:143, 219-221`. Documented in `docs/offers.md:94-104`.
- **Current:** 10% either way. Exactly 10% still holds. It's a property, not part of the parameter set.
- **Affects:** offers. It decides which posted or accepted prices stand.
- **Evidence:**
  - Deep review B3: an accepted price could be 21 days old, modern chase cards move 20–30% in three weeks, and buyers
    pick the listings that fell behind a rising market. That's adverse selection.
  - The 10–17% buyer margin is the only cushion, so a threshold at or above the margin lets the buyer's whole margin
    disappear before the guard trips.
- **Case for higher (15%):** fewer voided listings and "get a new offer" frustrations for sellers, at low volume where
  each listing matters. The day-to-day noise of a thin card's sale-day median can move 10% on its own.
- **Case for lower (5–7%):** an upward move of 10% on a $1,000 card gives the buyer $100 that the seller didn't get.
  The buyer's margin at A is only 10%.
- **Recommendation:** keep 10%. If the query shows most voids come from upward moves (the seller losing), consider an
  asymmetric rule: 7% up, 10% down.

```sql
-- Re-price voids by direction and size. The refusal stores no old or new price, so re-derive the move from the
-- offer's price and the latest snapshot is not possible in SQL; count and timing are.
select date_trunc('week', v.created_at) as week,
       count(*)                                    as reprice_voids,
       count(*) filter (where le.id is not null)   as at_accept
from card_offer_void v
left join sale_listing l on l.offer_id = v.offer_id
left join listing_event le on le.listing_id = l.id and le.type = 'VOIDED' and le.note = v.reason
where v.reason = 'The price has changed. Get a new offer.'
group by 1 order by 1;

-- Against all post and accept attempts, for the void rate.
select count(*) as listings_posted from sale_listing where created_at >= now() - interval '30 days';
select count(*) as accepted from listing_event where type = 'ACCEPTED' and created_at >= now() - interval '30 days';
```

The refusal records no old or new price. Logging both, in the void's reason or a column, would show the direction of
each move. That's a code change for a later PR, not this one.

**Zach's decision:** ___

---

## 4. Pricing guards: PokeTrace days

These four leave a PokeTrace day out of pricing, never shift it, and only catch high days. Each exclusion is stored
in `comp_exclusion` with its rule and reason. All are in parameter set 1.5.0 onwards. Documented in
`docs/poketrace-pilot.md:83`.

### 7. `pricing.pokeTraceDay.maxSpread`: a day whose high is more than 3× its low is left out (`WIDE_DAY`)

- **Where:** `1.8.0.properties:46`. Applied in `PokeTraceDayGuard.java`.
- **Current:** 3.
- **Affects:** pricing guard. Fewer comps can mean a thinner price and lower confidence, but never a higher price
  from a wild sale.
- **Evidence:** Base Charizard's PSA 10 days differ by more than 100× low to high (`poketrace-pilot.md:149`).
  Deep review B4: a day with one $40,000 and two $28,000 sales becomes one $32,000 comp.
- **Case for higher (5×):** cheap cards with a $5 low and a $20 high on a busy day are normal (shipping-included
  versus not, auctions versus buy-it-now), and 3× drops honest days, which thins the price.
- **Case for lower (2×):** within one tier and one day, a real market rarely spreads 2×. A wider spread usually means
  a mislabelled grade or card.
- **Recommendation:** keep 3×, and check the rate by price band with the query. If more than about 10% of sale days
  under $50 are dropped as `WIDE_DAY`, raise it for low prices only (a later set).

```sql
-- Exclusion rate by rule and price band, last 30 days of snapshot runs.
with ex as (
  select ce.rule, sc.price
  from comp_exclusion ce join sold_comp sc on sc.id = ce.sold_comp_id
  join price_snapshot_run r on r.id = ce.snapshot_run_id
  where r.started_at >= now() - interval '30 days' and ce.rule <> 'SUPERSEDED'
)
select rule,
       case when price < 50 then '<50' when price < 200 then '50-200' when price < 1000 then '200-1000' else '1000+' end as band,
       count(*) as excluded
from ex group by 1, 2 order by 1, 2;

-- Denominator: PokeTrace sale days per band, and how many would trip at 2x, 3x and 5x.
select case when price < 50 then '<50' when price < 200 then '50-200' when price < 1000 then '200-1000' else '1000+' end as band,
       count(*) as sale_days,
       count(*) filter (where (raw->>'high')::numeric > 2 * (raw->>'low')::numeric) as over_2x,
       count(*) filter (where (raw->>'high')::numeric > 3 * (raw->>'low')::numeric) as over_3x,
       count(*) filter (where (raw->>'high')::numeric > 5 * (raw->>'low')::numeric) as over_5x
from sold_comp
where source = 'POKETRACE' and sold_on >= current_date - 90
  and (raw->>'low')::numeric > 0
group by 1 order by 1;
```

**Zach's decision:** ___

### 8. `pricing.pokeTraceDay.maxMultipleOfTier`: a day averaging over 3× its tier's median is left out (`HIGH_DAY`)

- **Where:** `1.8.0.properties:47`.
- **Current:** 3.
- **Affects:** pricing guard.
- **Evidence:** Mewtwo-EX 98/99 had a single PSA 10 sale far out of line with its raw price
  (`poketrace-pilot.md:149`). Trimming starts only at 10 comps, so 2–9 sale days go into the median untrimmed.
- **Case for higher (4–5×):** a genuine spike (a reprint announcement, a tournament win) is real. A 3× guard drops
  the first days of a real move, and the median catches up later anyway.
- **Case for lower (2×):** a median of 2–9 days is easily dragged up by one day. 2× still lets a real move through
  within a few days once the other days follow.
- **Recommendation:** keep 3×. The median of a small set is robust to one wild day already, so this guard mainly
  protects σ and thin tiers, where 3× is enough.

```sql
-- HIGH_DAY exclusions with the multiple they were at, and whether the tier's later days followed (a real move).
select ce.reason, sc.catalog_card_id, sc.raw->>'tier' as tier, sc.sold_on, sc.price
from comp_exclusion ce join sold_comp sc on sc.id = ce.sold_comp_id
where ce.rule = 'HIGH_DAY'
order by ce.created_at desc limit 200;
-- For each one, compare with the tier's median 30 days later. If most were followed by days at the same level,
-- 3x is cutting real moves and should go up.
```

**Zach's decision:** ___

### 9. `pricing.pokeTraceDay.minOtherDays` and `neighbourhoodDays`

- **Where:** `1.8.0.properties:48-49`.
- **Current:** at least 2 other sale days of the same tier (any source), within 90 days either side.
- **Affects:** pricing guard. With fewer than 2 neighbours the `HIGH_DAY` rule doesn't apply at all.
- **Case for more days (3+):** a median of 2 is just their average, so one odd neighbour moves it.
- **Case for fewer (1):** thin tiers, where a wild day does the most harm, are exactly where 2 neighbours are
  missing.
- **Neighbourhood:** 90 days either side looks forward as well as back. That's fine for a snapshot of history, but a
  rising market makes old days look low. 60 days would follow trends more closely.
- **Recommendation:** keep 2 and 90. Shorten to 60 only if item 8's query shows real moves being cut.

**Zach's decision:** ___

### 10. What counts as a PokeTrace revision

- **Where:** `PokeTraceRevisions.java:52-66` (rule), `1.8.0.properties:54` (`latestRevision=true`, parameter set 1.7.0
  on). `docs/poketrace-pilot.md:84`: "What counts as a revision is a default for Zach to confirm."
- **Current:** any change of at least a cent in a stored day's `avg`, `low` or `high` is a revision. The noised
  `saleCount` alone is not. From 1.7.0 the newest revision prices the day.
- **Affects:** pricing. A revision can move a price either way.
- **Case for a tolerance (say 1%):** fewer rows for rounding jitter.
- **Case against:** PokeTrace sends cents, and every change is kept anyway (store everything). A tolerance would
  only hide real corrections.
- **Recommendation:** keep as is.

```sql
select date_trunc('week', started_at) as week, sum(revised) as revised_rows, sum(inserted) as inserted
from comp_ingest_run where source = 'POKETRACE' group by 1 order by 1;
```

**Zach's decision:** ___

---

## 5. Confidence and the offer margin

### 11. `confidence.oneProviderMaxFmvForA`: one pre-grade provider is A only up to $1,000 FMV

- **Where:** `1.8.0.properties:113` (comment at 109-112). Read in `ParameterSet.java:215`, applied in
  `CardValuator.java:244-255` (trace subject `photo_confidence_value_gate`).
- **Current:** $1,000, measured on FMV (central distribution, net prices, same run). Parameter set 1.6.0 onwards.
  Zach decided the rule on 2026-11-10. The threshold and FMV as the measure are defaults.
- **Affects:** confidence, and so the buyer margin: A 10%, B 13%. Above $1,000 it's 3 points of the price, at least
  $30 a card. It only applies while CardGrade is on (`CARDGRADE_ENABLED`, off by default in
  `deploy/compose.prod.yml:59`). A reviewer-entered distribution isn't affected.
- **Evidence:** none from data yet. "One photo grading is enough to be sure of a cheaper card, not a dear one."
- **Case for higher ($2,500):** above $1,000 the buyer already has a $100+ margin at A, and most raw cards over
  $1,000 are vintage holos, which get offers subject to authentication anyway.
- **Case for lower ($500):** the cost of one wrong grade scales with the price. At $500 a one-grade miss on a modern
  chase card can already be more than the 10% margin.
- **Measure:** FMV is the right measure. The firm price is the alternative, but it depends on the margin, which
  depends on confidence, so it would be circular.
- **Recommendation:** keep $1,000 and FMV. Revisit once 50 or more CardGrade-assessed deals have returned grades.

```sql
-- How many offers the gate moved from A to B, and their prices.
select count(*) as gated, round(avg(o.price), 2) as avg_price, round(sum(o.price) * 0.03, 2) as approx_margin_moved
from card_offer o
where exists (select 1 from jsonb_array_elements(o.trace) s where s->>'subject' = 'photo_confidence_value_gate');
```

**Zach's decision:** ___

### 12. The lowball flag: an offer below the lowest raw near-mint day of the last 90 days

- **Where:** `OfferService.java:47` (`RAW_SALES_DAYS = 90`, a constant, not a property), `OfferService.java:84` (the
  comparison), `card_offer.raw_nm_*` and `below_raw_sales` (`V26__offer_raw_sales_check.sql`). Documented in
  `docs/offers.md:78-92`, "These defaults are the reviewing session's picks, not Zach's decisions yet."
- **Current:** flag only, never a refusal. 90 days. The benchmark is the lowest raw NM day average. The admin and the
  seller see it, buyers don't.
- **Affects:** offers, as information. It doesn't change a price.
- **Evidence:** deep review B1 step 1 offered "flag or refuse". The session chose flag.
- **It probably fires on most cheap cards now.** Since 1.8.0, a floored grade's offer rung is roughly
  p25 × (1 − 0.15) × (1 − d) × (1 − m), about 0.6–0.7 of the 25th-percentile NM day. That is usually below the lowest
  NM day. A flag that's always on stops meaning anything.
- **Options:**
  - (a) Keep as is.
  - (b) Refuse below the lowest day. That would refuse most cheap cards, so no.
  - (c) Compare with the *net* benchmark the formula would pay for a sure NM card: the raw floor × (1 − f)(1 − d)(1 − m).
    It flags only offers below what a certain NM card would get, which is a real anomaly.
- **Recommendation:** keep flag-only (never refuse). Ask for (c) as a follow-up code change, and make the 90 days a
  property.

```sql
-- How often the flag fires, by price band and parameter set.
select parameter_set,
       case when price < 50 then '<50' when price < 200 then '50-200' when price < 1000 then '200-1000' else '1000+' end as band,
       count(*) as offers,
       count(*) filter (where below_raw_sales) as flagged,
       round(avg(price / nullif(raw_nm_low, 0)) filter (where raw_nm_low is not null), 2) as avg_price_to_raw_low
from card_offer where not graded and raw_nm_sales is not null
group by 1, 2 order by 1, 2;
```

**Zach's decision:** ___

---

## 6. Condition distribution (applies when a pre-grade provider is on)

CardGrade is off by default in prod (`deploy/compose.prod.yml:59`). Today Zach enters distributions by hand, so these
items start to bite the day it's switched on. Each assessment stores the values it was built with in
`condition_assessment.basis`.

### 13. `pokemans.condition.tight-spread`

- **Where:** `ConditionProperties.java:50-51` (default), `:16-20` (doc: "weights are defaults to confirm"). Not in
  `application.yml`.
- **Current:** +1: 10%, 0: 60%, −1: 25%, −2: 5%. Used when the provider's lowest of corners, edges and surface
  (folded) is at or above its overall grade. The default spread is 10/50/25/10/5.
- **Affects:** condition, and so offers. 10 points more on the grade itself, taken from −2 and −3.
- **Case for tighter (70% on the grade):** subgrades all at or above the overall grade is the best evidence a photo
  provider gives.
- **Case for looser (back to the default):** the provider's subgrades come from the same photos as its overall grade,
  so they aren't independent evidence.
- **Recommendation:** keep. Measure with the condition validation target (±1 grade 80% of the time) once outcomes
  exist.

```sql
-- Once outcomes exist: hit rate of tight versus default spread (basis records which spread was used).
select a.basis->>'spread' as spread_used, count(*) as cards
from condition_assessment a where a.author_type = 'SYSTEM' and a.is_current group by 1;
```

**Zach's decision:** ___

### 14. `pokemans.condition.max-ten-probability` and `ungradable-floor`

- **Where:** `ConditionProperties.java:38-39`, doc at `:24-29`: "a starting guess, not calibrated" (deep review B2).
- **Current:** at most 25% on 10 (the rest moves to 9), and at least 2% on U.
- **Affects:** condition → offers. Together with item 1 these set the 10 rung's weight.
- **Case for a higher P(10) cap (30%):** modern pack-fresh cards do grade 10 often.
- **Case for a lower one (15%):** raw cards that look perfect in photos grade 10 much less often than they seem to.
  The cap and item 1 both protect the buyer, and both are uncalibrated.
- **Recommendation:** keep both. They're the deep review's suggested range.

**Zach's decision:** ___

### 15. `disagreement-for-b`, `confidence-penalty-b`, `confidence-penalty-c`

- **Where:** `ConditionProperties.java:35-37`, "starting values, to be tuned against returned grades".
- **Current:** two providers up to 1.5 grades apart still allow B. At B, 10% of each grade's probability moves one
  grade down; at C, 25%.
- **Affects:** condition → offers, through both the distribution and the margin.
- **Recommendation:** keep until outcome data exists. These came from the methodology's principles, not a separate
  review.

**Zach's decision:** ___

---

## 7. FLV and the lender side (paused)

### 16. `values.maxSigma`: a measured monthly σ above 1.0 isn't used

- **Where:** `1.8.0.properties:98`, read in `ParameterSet.java:214`, applied in `ValuationEngine.java:185-191`.
  Parameter set 1.5.0 onwards.
- **Current:** 1.0 a month. Above it, the valuation uses the 20% fallback and drops confidence one level, the same as a
  σ too thin to measure.
- **Affects:** FLV and the confidence of valuation runs. Not offers: they never use σ. Collateral is paused
  (`COLLATERAL_ENABLED=false`), so nothing is at stake today.
- **Evidence:** Base Charizard measured 20.48 against an expected 0.08 (`poketrace-pilot.md:150`). The fallback's
  shock is 1 − 2 × 0.20 × √2 = 0.43.
- **The cap is non-monotone today.** The shock 1 − k·σ·√t is clamped at zero (`CardValuator.java:189-193`), and it
  reaches zero at σ = 1/(2√2) ≈ 0.354. So:
  - a measured σ between 0.354 and 1.0 is used, and gives an FLV of $0;
  - a σ of 1.01 falls back to 20% and gets a positive FLV (shock 0.43), less one confidence level.
  
  The worse the data, the better the FLV.
- **Case for higher:** none. Every σ above 0.354 already zeroes FLV.
- **Case for lower (about 0.35):** at the point where the shock reaches zero, a measured σ never zeroes FLV when a
  slightly worse one wouldn't. Real monthly σ for Pokémon graded cards is expected near 0.08, so 0.35 is still 4× that.
- **Recommendation:** lower to 0.35 in a new set before lenders resume, alongside winsorising the 30-day changes (deep
  review B4). The alternative is to treat any σ above the fallback as unmeasured.

```sql
select width_bucket(sigma, 0, 1.5, 15) as bucket, min(sigma), max(sigma), count(*)
from price_stats ps
where as_of >= current_date - 7 and sigma is not null
group by 1 order by 1;
```

**Zach's decision:** ___

### 17. `pokemans.pricing.min-cards-per-ratio`

- **Where:** `application.yml:113` ("Starting value, to be set from real data").
- **Current:** an era step-down ratio needs 3 cards with both adjacent grades observed.
- **Affects:** pricing of grades without their own sales: offers, FMV and FLV. With no era ratio, the card's own
  ladder is used, which the sanity check found less reliable (finding 6).
- **Case for higher (5):** a median of 3 cards is easily skewed by one odd card.
- **Case for lower (2):** more grades get priced from era ratios instead of the card's own steepest step.
- **Recommendation:** keep 3 until the PokeTrace pull gives 5 or more cards per era for every step.

**Zach's decision:** ___

---

## 8. Operations, privacy, display

### 18. `pokemans.pricing.poketrace.pull-gap-days`

- **Where:** `application.yml:132`, `PricingProperties.java:83`. Used in `PokeTracePullGaps.java:48`.
- **Current:** 2 days without a successful pull since the last one (or since mapping) puts a card on "Pulls stopped"
  and fails the nightly pricing healthcheck.
- **Affects:** an ops alert. No price changes. A missed gap means stale tables, which lose confidence after 7 days.
- **Case for 1 day:** a nightly pull that fails once is worth knowing about the next morning.
- **Case for 3 days:** fewer false alarms from one-off PokeTrace errors.
- **Recommendation:** keep 2.

```sql
select date_trunc('day', pulled_at) as day, count(*) filter (where not ok) as failed, count(*) as pulls
from comp_pull where source = 'POKETRACE' and pulled_at >= now() - interval '30 days'
group by 1 order by 1;
```

**Zach's decision:** ___

### 19. Retention periods

- **Where:** `application.yml:194-198`, `EmailRetention.java:19`, `AddressRetention.java:20, 46-47`,
  `docs/data-retention.md:8`.
- **Current:** email content cleared after 90 days. A label's address anonymised 365 days after its last deal closed.
  A replaced, unused address deleted after 30 days.
- **Affects:** privacy. No money, but a label address is evidence in a shipping claim, so 365 days covers USPS's claim
  window with room to spare.
- **Recommendation:** keep all three.

**Zach's decision:** ___

### 20. Other graders' centering allowances marked "unsure"

- **Where:** `GraderCentering.java:44-89` (BGS from memory because the page refused our reader, CGC rows whose back
  limit isn't stated, TAG's approximate values). The web shows "To confirm" on the centering labels page.
- **Affects:** display only. These never price or cap a card. Only PSA's table does.
- **Recommendation:** keep. Check BGS against beckett.com by hand when convenient.

**Zach's decision:** ___

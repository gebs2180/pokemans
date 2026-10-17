# Sold-price data sources: terms review

Checked 2026-10-16 against the sources' public pages. This is a reading of the terms, not legal advice. Re-check before signing up, and get anything marked "confirm" in writing.

M3 and M4 need four things from a price source (CLAUDE.md pipeline steps 3–4, validation targets):
1. **Sold comps per grade:** raw by condition, and PSA/CGC/BGS by grade, each with a sale date, so we can apply the 90/180-day windows, the 30-day half-life and the n ≥ 10 trim. Asking prices are no use.
2. **History:** 12 months for σ on every card, and 2021–2023 for the liquidation replay.
3. **Store** the comps indefinitely. Every report must trace to the comps it used, and they're calibration data.
4. **Show** derived values, and ideally the comp list (dates, counts, prices), in PDF reports to lenders and insurers who don't log in to anything.

Rights 3 and 4 are where almost every source falls short.

## A problem that affects nearly every source: most sold data starts on eBay

Most aggregators (PriceCharting, PokemonPriceTracker, 130point, Card Ladder, TCG Price Lookup) get much or most of their sold data from eBay. eBay's API License Agreement forbids using eBay Content "to suggest or model prices" (§9.5) and forbids training algorithms on it (§9.10). It also requires written permission for anything that lets someone derive average selling prices (§8.1(d)). The agreement exempts information "you lawfully obtain independent of eBay" (§1), so data we license from an aggregator may fall outside it. But whether the aggregator had the right to collect and sublicense that data is the aggregator's problem until it becomes ours. **Every licence we sign should include a warranty that the vendor has the right to license the data for this use, and an indemnity if it didn't.**

In the US, raw facts such as a sale price and date generally aren't copyrightable, but website terms and licence contracts still bind us. The EU also protects databases with a separate database right. We don't rely on the facts exception: we license.

## PriceCharting (PriceCharting.com, also SportsCardsPro): best fit, needs a commercial licence

- **Data:** per-grade values for each card. Card pages show columns for Ungraded, Grade 7, 8, 9, 9.5 and PSA 10, plus BGS 10, CGC 10, SGC 10, TAG 10 and ACE 10 in the API. Values come from sold listings combined by a proprietary algorithm. Card pages also list individual sold listings with a date, title, price and source tag ([eBay], [TCGPlayer], [Goldin], [Fanatics], [HeritageAuctions]). This is the only source checked that lists comps from several marketplaces, by grade, on one page. Confirm whether "Grade 9" mixes PSA, BGS and CGC 9s.
- **History:** the site shows historic price charts, but **the API and CSV give current values only**: the docs say "Historic prices and historic sales are not supported". The listing-level comps we need for our own weighted medians aren't in the API either. On API data alone, σ would need 12 months of daily snapshots from sign-up, and a 2021–2023 replay would be impossible. Confirm whether a commercial licence can include historic monthly values and the sold-listing feed back to 2020.
- **API:** prices API at 1 call per second, and a full CSV that regenerates daily (one download per 10 minutes). Both need the **Legendary** subscription at $49/mo. Collector ($6/mo) has no API.
- **Commercial use / third parties:** API and CSV data are "licensed for internal use only". Sharing price data with third parties, or using it in a service others use, "requires a commercial license and express written permission". Attribution (logo or link) is preferred under a commercial deal. No price is published. The site terms add that price data "can be used for your Internal Business Purposes if you maintain a valid and current Legendary subscription", "strictly within the organization, and not for external display or redistribution". They also let external sites reference prices if PriceCharting "is clearly cited as the source" with a visible link. Whether a cited PriceCharting figure in a pilot report counts as that kind of reference is untested. Confirm.
- **Storing:** caching on our servers is encouraged, but "all data obtained from the API or CSV must be purged when the subscription ends." That conflicts with store-everything and with the audit trail behind reports we've already issued. Negotiate it.
- **Confirm in writing:** a commercial licence for lender and insurer reports. Retention of data used in issued reports (and calibration data) after the licence ends. Access to sold-listing detail (date, price, grade, source) through the API or a bulk export. Historic values and sales back to 2020. Price. The eBay-chain warranty above.

Sources: [API docs](https://www.pricecharting.com/api-documentation), [terms of service](https://www.pricecharting.com/page/terms-of-service), [subscriptions](https://www.pricecharting.com/pricecharting-pro), [FAQ](https://www.pricecharting.com/faq), [example card page](https://www.pricecharting.com/game/pokemon-base-set/charizard-4).

## PokemonPriceTracker: usable for live comps, no history before 2025

- **Data:** raw prices (TCGplayer market prices, which are partly listing-based), graded sales from **eBay and Fanatics Collect** (PSA 8, 9 and 10 for many cards, English and Japanese), Cardmarket EUR prices, and PSA population. Business and Enterprise add daily dump exports of cards, graded sales and population.
- **History:** **not backfilled**. History starts when they began tracking each source: English card prices in late January 2025, Japanese and sealed in late October 2025, and Cardmarket in March 2026. Graded history has no stated start date. That's enough for 12-month σ on English cards, but **useless for the 2021–2023 replay**.
- **Plans:** Free (3 days of history, non-commercial), API $9.99/mo (6 months of history, no commercial licence), **Business $99/mo** (all history, 200k credits a day, commercial licence, exports), Enterprise $300/mo.
- **Commercial use:** any revenue, and any internal business use, needs Business.
- **Storing:** caching and storing to serve our own application is allowed, but the stored copy mustn't be exposed to third parties. After cancellation we may **keep** data gathered while subscribed, for our own product. That's better than PriceCharting.
- **Third parties:** derived figures (medians, charts, estimates) can be displayed. Individual sold listings can be shown "inside your product" on Business. Bulk export to third parties and any redistribution or resale are banned on every plan. Whether a PDF sent to a lender counts as "inside your product" isn't stated. Confirm.
- **Other:** no SLA, accuracy guarantee or indemnity. Access can be suspended without warning or refund. Section 6 of its terms governs.
- **Confirm in writing:** that PDF reports to lenders and insurers, including the comp list behind a value, count as display inside our product. That we may keep comps used in issued reports permanently. The graded-sales start date and fields (date, price, grade company, marketplace). The eBay-chain warranty.

Sources: [licensing](https://www.pokemonpricetracker.com/licensing), [pricing](https://www.pokemonpricetracker.com/pricing), [data coverage](https://www.pokemonpricetracker.com/data-coverage).

## PokeTrace: cheapest commercial option, terms thin

- **Data:** US prices from eBay and TCGplayer, EU prices from Cardmarket. Raw tiers (NEAR_MINT and so on) and graded tiers (PSA_10 and similar) for PSA, BGS, CGC, SGC, ACE and TAG, grades 1–10. Each tier gives `avg`, `low`, `high`, `saleCount`, `avg1d/7d/30d`, `median3d/7d/30d`, `trend` and a `confidence` of high, medium or low (OpenAPI v1.7.0). Japanese, Chinese, Thai and Indonesian cards are separate games.
- **Sold listings:** `GET /cards/{id}/listings` returns **sold eBay listings** with `soldAt`, `price`, `grader`, `grade`, `condition`, `title`, `listingUrl`, `sourceItemId` and an `anomalyFlag`/`anomalyReason`. That's exactly the comp-level data step 3 needs. It's **Scale plan only**.
- **History:** `GET /cards/{id}/prices/{tier}/history` with a period of 7d, 30d, 90d, 1y or all. Depth isn't stated, so it's unknown whether it reaches 2021. "1y" being an option suggests at least 12 months for σ. Confirm.
- **Plans:** Free (raw only, non-commercial). **Pro $19.99/mo** (all grades, full history, 10k requests a day, **commercial use**). Growth $49.99/mo (30k). **Scale $98/mo** (adds sold listings and WebSocket, 100k). Card search returns at most 20 per page. Payment is through Paddle.
- **Terms:** a "limited, non-exclusive, non-transferable, revocable license" (§2). No using it "to create a competing service or resell data" (§2). No "Resell, sublicense, or redistribute our data without explicit written authorization" (§8). Storage, caching, retention after cancellation, displaying data in reports and model training are **not addressed**. The data is "AS IS" with no warranty (§5). **No legal entity or jurisdiction is named** ("the laws of the jurisdiction in which PokeTrace operates", §11), which is weak for anything a lender relies on.
- **Fit:** the best data per dollar found. Scale at $98 gets sale-level comps that PokemonPriceTracker and PriceCharting don't expose at that price. The thin terms and the anonymous operator are the risks. Fine for a pilot. For production, only with a written licence from a named entity.
- **Confirm in writing:** the operating company and jurisdiction. That PDF reports to lenders and insurers, showing derived values and comps, aren't "redistribution". That we may store comps indefinitely, including after cancelling. History depth by tier. The eBay-chain warranty.

Sources: [pricing](https://poketrace.com/pricing), [terms](https://poketrace.com/terms), [OpenAPI](https://api.poketrace.com/v1/openapi.json), [listings docs](https://poketrace.com/docs/listings).

## Card Ladder (Collectors / PSA): the 2021–2023 history, blocked as-is

- **Data:** sold-sales history of about 28 million sales since 2004, from 15 marketplaces including eBay, Goldin, PWCC (now Fanatics Collect) and MySlabs, per grade. It covers TCG as well as sports. Collectors Universe (PSA's parent) bought it in December 2021.
- **History:** the deepest source found, and the obvious candidate for the 2021–2023 replay.
- **API:** none public.
- **Terms (updated 2024-12-30):** "personal, non-commercial use only". No "Reproduce, duplicate, copy, sell, resell or otherwise exploit for any commercial purposes". No "Populate a database by systematically downloading and storing Content". No scraping without "express prior written consent". Delaware law, AAA arbitration.
- **Use only if** Collectors grants a written data licence. Ask for a one-off historical extract (Pokémon, 2020–2023, per grade, sale-level), even if live data never follows. PSA owning both the grader and the data could make that harder, or easier.

Sources: [terms](https://www.cardladder.com/terms), [acquisition (Sportico)](https://www.sportico.com/business/commerce/2021/collectors-universe-buys-card-ladder-1234647703/), [interview (Boardroom)](https://boardroom.tv/card-ladder-chris-mcgill-q-and-a).

## eBay (Marketplace Insights, Browse, Terapeak): blocked

- **Marketplace Insights API:** sold items, but only up to **90 days** back, and not every category. It's a **Limited Release**, a "Restricted API" available only to developers that eBay's business units approve. Our 180-day extension and 12-month σ wouldn't fit even with access.
- **Browse API:** active listings (asking prices), so it's no use to us.
- **Terapeak / Product Research:** up to 3 years of eBay sales in Seller Hub, for viewing only. No export or API was found.
- **API License Agreement:** forbids using eBay Content, alone or combined with other data, "to suggest or model prices" (§9.5). Forbids training algorithms or doing machine learning on it (§9.10), and using Restricted APIs to "develop, train, or improve algorithms or AI" (§8.5(b)(2)). Pricing tools that use Restricted API data need eBay's "express prior written consent" (§8.5(c)). Selling or sharing Restricted API data with third parties is banned (§8.5(b)(3)), and eBay Content can't be sold, distributed or stored except for permitted purposes (§9.7). Non-listing content must be no more than 24 hours old (§8.1(c)), and everything must be destroyed when the agreement ends (§16.3).
- **Verdict:** pricing collateral from eBay data is exactly what §9.5 forbids. It would take a negotiated data deal with eBay. That isn't realistic for us now; revisit only at scale.

Sources: [API License Agreement](https://developer.ebay.com/products/license), [Marketplace Insights overview](https://developer.ebay.com/api-docs/buy/marketplace-insights/static/overview.html), [Terapeak help](https://www.ebay.com.au/help/selling/selling-tools/terapeak?id=4853).

## 130point: not usable

- **Data:** eBay sold prices including accepted Best Offer amounts (which eBay hides), plus some auction houses, US only. Collectors use it heavily.
- **Source:** forum reports say it gets eBay data by scraping, and that it breaks when eBay blocks it. No API or data licence was found. Its terms page is behind a bot check, so we couldn't read it. Confirm.
- **Verdict:** even if 130point offered a licence, it would be sublicensing scraped eBay data, which is exactly the chain-of-title problem above. Use it only for manual spot checks.

Sources: [eBay best-offer guide (hobbyDB)](https://help.hobbydb.com/support/solutions/articles/36000343715-adding-ebay-best-offer-price-points), [Elite Fourum thread](https://www.elitefourum.com/t/130point-not-working/32232).

## TCGplayer: closed

- **Data:** raw card prices by condition (Market Price is built from recent sales; Listed Median is asking prices). No graded data.
- **API:** "We are no longer granting new API access at this time." Existing keys still work. The API terms forbid scrapers and bots outside the API.
- **Verdict:** not available directly. Its raw-condition prices only reach us through resellers (JustTCG, Scrydex, PokemonPriceTracker), so ask each whether its TCGplayer-based numbers are sales or listings.

Sources: [getting started](https://docs.tcgplayer.com/docs/getting-started), [API terms](https://help.tcgplayer.com/hc/en-us/articles/360061115874-TCGplayer-API-Terms-Conditions).

## JustTCG: raw by condition, terms workable

- **Data:** per-condition prices (Near Mint to Damaged) for Pokémon and other TCGs, built from "real sales data from online marketplaces and from storefronts run by our Partners", combined with listed prices. So it is **not purely sold data**. Graded PSA/BGS/CGC variants exist only in the v2 beta. There's a `priceHistory` field, but its depth isn't stated.
- **Plans:** Free (non-commercial), Starter $19/mo, Professional $49/mo, Enterprise $149/mo.
- **Terms (paid plans):** may display prices and derived metrics to end users, combine them with other market data, and store history with no time limit **while subscribed**. The storage licence ends with the subscription, and stored data can't then be used to serve others. No redistribution as a feed or dataset, and no competing price API. Attribution is optional.
- **Fit:** a candidate for the raw-condition price that caps each grade's net price (step 3). Only if it confirms which numbers are sales.

Sources: [terms](https://justtcg.com/terms), [home and pricing](https://justtcg.com/).

## Scrydex: raw and graded, terms thin

- **Data:** raw prices, graded prices (PSA, BGS, CGC, TAG, ACE), population, trends, price history (3 credits a request) and Vision image analysis. Sources, sold vs listed, and history depth aren't stated.
- **Plans:** Starter $29/mo (5k credits), Growth $99/mo (50k), Professional $399/mo (250k), Enterprise custom.
- **Terms:** no "Resell, sublicense, redistribute, mirror, or commercially exploit the Services" without written authorization (§4). Can't be used as "a substitute backend, proxy, or wholesale data source" for a competing product (§4). Says nothing about caching or retention. "Commercially exploit the Services" is broad enough to cover us. Confirm.
- **Fit:** a possible second graded source, and also a catalog/ID candidate (it was already noted in pregrade-providers.md). Ask the same questions as for PokemonPriceTracker.

Sources: [pricing](https://scrydex.com/pricing), [terms](https://scrydex.com/terms).

## Others looked at

- **PSA (Auction Prices Realized, Public API):** APR is per-grade sold prices for PSA slabs (eBay plus auction houses) on psacard.com. The free Public API does **cert lookup only**: no prices, no population. No data licence for APR was found. The site terms weren't reachable (the "Terms & Conditions" link goes to the grading submission terms). Ask Collectors together with Card Ladder.
- **Goldin, Heritage, Fanatics Collect (PWCC):** first-party auction results, fully sold with exact dates, and Heritage's archive goes back decades. None offers a public API or data licence. Heritage's terms (as reported) restrict use to personal, non-commercial use and forbid scraping, and Heritage has sued scrapers (Collectrium, Christie's). Goldin's terms couldn't be read. Their results already reach us through PriceCharting's sold listings, so don't approach them directly unless the replay needs them.
- **Alt:** shows sales history and an "Alt Value" estimate for slabs in its vault. No public API or data programme. **Alt also lends against cards**, so it's a competitor rather than a supplier.
- **Collectr:** consumer portfolio app. No public API or data terms were found.
- **TCG Price Lookup:** launched June 2026 with TCGplayer and eBay prices, per-condition raw values and PSA/BGS/CGC graded prices, plus rolling averages from eBay sold listings. Its terms weren't found. Too new and eBay-derived. Watch it.
- **Card Hedge:** consumer app with population and grade data. No API docs were found.
- **pokemon-api.com (tcggo, on RapidAPI):** $0–49.50/mo. It includes eBay graded medians (PSA 10/9, BGS 10) with sample sizes, and Cardmarket and TCGplayer prices, but only 7- and 30-day averages. No terms are published beyond a copyright line, and no operator is named. Not usable.
- **POKEKA:** graded sold prices with sale counts and last-sale dates (it claims 3.9M sales, every one traceable), with JP, EN and CN markets kept separate. Pro is $19.90/mo, but its only API is an MCP feed for AI assistants. No bulk API, and the commercial terms weren't checked. Worth a look for Japanese cards.
- **Not checked:** Market Movers (Sports Card Investor), WorthPoint, GemRate, Cardmarket, Courtyard.

## Other routes: partnerships, our own data and public ledgers

These aren't data products we can buy today. They're places where sold data exists and where we have something to offer in return, or where nobody's website terms apply.

### Auction houses: trade liquidation flow for sold data (strongest)

- **Idea:** our product forecasts forced-sale value, so every loan that defaults needs somewhere to sell the cards. Fanatics Collect (formerly PWCC), Goldin, Heritage and Courtyard all compete for consignments. Offer to name one as the preferred liquidation channel in our reports and lender agreements. In return, ask for its sold-results feed (first-party, exact hammer and fixed prices, dates, grade, cert number) under a licence that allows lender reports.
- **Why it's strong:** first-party data has no eBay chain-of-title problem. A lender also gets a better story than a data licence: the source of our forced-sale numbers is the house that would sell their collateral. Heritage's archive goes back decades, which would cover the 2021–2023 replay.
- **Status:** no public data programme found at any of them, so this is a negotiation, not a sign-up. Our liquidation volume is the bargaining chip, so it gets stronger once a lender pilot is signed.

### On-chain marketplaces: Courtyard (Polygon) and Collector Crypt (Solana)

- **What they are:** PSA, BGS and CGC slabs held in vaults (Courtyard uses Brink's), each represented by a token tied to one serialized card. Owners trade the token. Redeeming the physical card burns it. OpenSea lists Courtyard traits including Grader and Grade. Reported volume is large but inconsistent: Courtyard about $78M in August 2025; Collector Crypt over $1.3B cumulative by mid-2026.
- **Why it's interesting:** token transfers are recorded on a public blockchain, and reading a public ledger means accepting no website terms. Sale records carry a timestamp and are tied to a specific graded card. That could be a free, independent graded-price cross-check.
- **Catches:**
  - **The biggest catch is that their prices are circular.** Both run pack openings with an **instant buyback at about 85–90% of a "fair market value"** that they take from markets like eBay. Buyback trades are a fixed percentage of someone else's price, so they're useless as evidence. Only peer-to-peer marketplace sales count, and they have to be separated from buybacks.
  - **Pack-opening dynamics.** Reporting says a few hundred wallets drive most Collector Crypt spending. Prices may reflect gambling-style behaviour more than collector demand.
  - **The price may not be on-chain.** Courtyard accepts card payments, so a token transfer may not show the price paid. Confirm, per platform, whether marketplace sale prices settle on-chain (USDC, SOL) or only the transfer does.
  - **Graded slabs only.** No raw cards. Card identity comes from token metadata, which we'd have to map to our catalog IDs, ideally by cert number.
  - **Tooling has its own terms.** Indexers and marketplace APIs (OpenSea, Magic Eden, Dune, Polygonscan) aren't the chain. Read from a node or check each tool's terms.
- **Courtyard sample (2026-10-16, Polygon blocks 95,665,363–95,665,563, about 7 minutes):**
  - **Contract:** `0x251BE3A17Af4892035C37ebf5890F4a4D889dcAD` (CourtyardRegistry, verified on Polygonscan). 516 token transfers in 359 transactions: 169 mints, 155 burns, 192 transfers.
  - **Prices are on-chain.** Every marketplace sale settles in native USDC (`0x3c499c…3359`) through the settlement contract `0x5e4943…4797`. The buyer pays the contract and the contract pays the seller, so the full sale price is visible. No marketplace fee shows on-chain. Courtyard's terms charge a 6% fee only on buybacks.
  - **Buybacks are easy to separate.** In a buyback, Courtyard's treasury `0x66dbff…e642` is the buyer. The treasury then burns the bought-back tokens in batches. Pack purchases are mints paid in USDC to the treasury, mostly round amounts ($25, $50).
  - **Mix in the sample:** 155 buybacks against **27 peer-to-peer sales**, so 85% of priced transfers are buybacks. Peer-to-peer prices ranged $5.87–$1,005, mostly $15–25. A handful of sales whose public token pages were viewed mixed Pokémon PSA 10s with sports cards and comics. All had come out of vending-machine packs.
  - **Card identity is not on-chain.** The token ID is a salted hash of the item's fingerprint ("Pokémon | PSA ######## | … | 10 GEM MINT"), so it can't be reversed. Grader, cert number, grade and card name come only from Courtyard's metadata API (`api.courtyard.io`). Once we have them, matching to our catalog by cert number would be exact.
- **Terms:** Courtyard's Terms of Service (Courtyard Inc., Delaware, California law, updated 2024-12-23) grant a "personal, non-commercial use" licence. They ban "any robot, spider, crawler, scraper, script… or other automated means… to access the Service, extract data", and "any data mining, robots, or similar data gathering or extraction methods" (§11, §14). The metadata API is part of the Service. Fair market value for buybacks "is determined solely by Courtyard… Courtyard does not disclose its model".
- **Verdict:** sale prices and dates are public ledger data and usable. Knowing *which card* sold needs Courtyard's metadata, and their terms bar collecting that automatically or for commercial use. **So on-chain sales aren't a free source without Courtyard's permission.** The comparison against PSA 9/10 prices was stopped for this reason, and no metadata is kept. Courtyard becomes a partnership ask under "Auction houses" above. It's both a liquidation channel (vaulted, insured, instant sale) and a sold-data source with cert-level identity. Ask for a data licence covering peer-to-peer sales with cert, grade and price.
- **Other caveats stand:** about 85% of priced activity is buybacks at Courtyard's own undisclosed "fair market value". The peer-to-peer market trades pack pulls, mostly modern, low-value cards. Even with a licence, weight this source lightly and never let buyback trades in.
- **Collector Crypt:** not sampled. Check its terms before touching its metadata.

### Partners' own sales records

- **Idea:** dealers, card shops and high-volume eBay sellers own their sales history and can export it (eBay order reports, shop point-of-sale systems, consignment ledgers). Pilot partners share their sold data as part of the arrangement.
- **Catch:** check eBay's seller terms on sharing your own order data with a third party. Buyer details must be stripped, and we only want card, grade, price and date. Volumes start small, and each partner's mix is skewed toward what that partner sells.
- **Why bother:** it's proprietary data nobody else has, which is the "data is the moat" principle. It also captures shop sales that no aggregator sees.

### Liquidation outcomes from lenders

- **Idea:** when a lender sells collateral, the realised price is the best evidence we could have. It tests FLV directly, which is our second validation target. Put a data-sharing clause in every lender agreement from day one: card, grade, channel, date, gross price, fees, and days to sell.
- **Status:** costs nothing but a contract clause. It's store-everything applied to the end of the loan.

### Direct data deal with eBay (later)

- eBay licenses sold data to approved partners through its Restricted APIs, under written consent (§8.5(c)). Not realistic before we have traction. Revisit once a lender pilot and some volume make us credible. It would cure the chain-of-title problem behind most aggregators.

### Regional sources for non-US cards

- **Cardmarket** (EU raw prices): its API is for professional sellers. Terms not checked.
- **SNKRDUNK, Yuyu-tei** (Japan): relevant to the high-value JP promos we flag. Not checked.
- PokeTrace (EU via Cardmarket, with Japanese and other Asian games) and POKEKA (JP/EN/CN sold prices) partly cover this already.

### Buylist bids as a floor check

- Dealer buylist offers are real bids, not asking prices. They can't set a price under our sold-comps rule. They can sanity-check QSV and FLV: a forced-sale value far below what dealers will pay today means a model or data problem. Use for alerts, never as a value input.

Sources: [Courtyard volume (Crypto Briefing)](https://cryptobriefing.com/pokemon-cards-nfts-tokenized-collectibles/), [Courtyard on OpenSea](https://opensea.io/collection/courtyard-nft/activity), [Courtyard 90% buyback (Forerunner)](https://forerunnerventures.com/perspectives/investing-in-courtyard-a-modern-collectibles-experience-creates-a-marketplace-flywheel), [Collector Crypt buyback (The Block)](https://www.theblock.co/post/369869/collector-crypt-drives-150-million-in-randomized-pokemon-card-trades-as-cards-token-soars), [Collector Crypt whales (Crypto Briefing)](https://cryptobriefing.com/collector-crypt-record-q3-pack-sales/), [Collector Crypt volume (Solana)](https://solana.com/news/tokenized-cards-and-physical-collectibles).

## Summary

| Source | Sold, per grade, dated? | History to 2021 | API | Commercial / lender reports | Cost |
|---|---|---|---|---|---|
| PriceCharting | yes, multi-marketplace (listings on site, values in API) | on site; **not in API** | yes | commercial licence needed; purge on exit | $49/mo + licence (unpublished) |
| PokemonPriceTracker | graded: eBay + Fanatics; raw: TCGplayer market | **no** (Jan 2025) | yes | Business allows display; PDFs unclear; may keep data | $99/mo |
| PokeTrace | yes: aggregates per grade; sold eBay listings on Scale | unknown ("all", "1y") | yes | commercial from Pro; redistribution needs consent; no entity named | $19.99–98/mo |
| Card Ladder | yes, 15 marketplaces | **yes**, since 2004 | no | personal use only | licence needed |
| eBay Marketplace Insights | yes, eBay only | no (90 days) | restricted | **§9.5 bans price modelling** | n/a |
| 130point | yes, eBay incl. Best Offer | unknown | no | scraped; no licence | n/a |
| TCGplayer | raw only | — | **closed** | — | n/a |
| JustTCG | mixed sales + listings, raw | unknown | yes | display OK; storage ends with sub | $19–149/mo |
| Scrydex | unstated | unknown | yes | "commercially exploit" needs consent | $29–399/mo |
| PSA APR / Goldin / Heritage | yes, first-party | yes | no (PSA API = cert only) | no licence offered | n/a |

## Recommendation

- **Primary: PriceCharting under a commercial licence.** It's the only source found with per-grade sold comps from several marketplaces, dated and tagged by source. That's what step 3's weighted medians need. Without a licence it's internal-use only, which rules out reports to lenders, so the licence is the gate for M4 reports. Apply now, because there's no published price.
- **Live cross-check and fallback: PokemonPriceTracker Business ($99/mo).** Its terms are the most explicit: it allows commercial use, display of derived values and sold listings, and keeping data after we cancel. eBay and Fanatics graded sales cover the main PSA grades. PokeTrace is cheaper with richer data but thinner terms. See "Pilot" below for which to start M3 on.
- **2021–2023 replay: buy a one-off historical extract**, in this order: Card Ladder (deepest, sale-level), then PriceCharting historic (if its licence can include it). The replay is internal validation, not shown to lenders, so a narrower internal-use licence may be easier to get. Neither current source can do it on standard terms.
- **σ needs 12 months of history at launch.** PokemonPriceTracker has it from January 2025 for English cards. PriceCharting's API doesn't. So either the licence includes history, or σ comes from PokemonPriceTracker until our own snapshots reach 12 months.
- **Raw-condition price** (the cap in step 3): JustTCG or Scrydex, only once they confirm their numbers are sales and not listings. Until then, use PriceCharting's Ungraded value.
- **Dropped:** eBay direct (§9.5, §9.10, 90 days, restricted). 130point (scraped, no licence). TCGplayer (closed). Card Ladder, Heritage and Goldin on their public terms (personal use, no scraping). Alt (competitor).
- **Start the non-vendor routes now:** add a liquidation-outcome data clause to every lender agreement, and offer an auction house preferred-liquidation status in exchange for its sold feed once a lender pilot is signed. Add Courtyard to that list: its on-chain prices are public, but card identity needs its permission.
- **Never scrape** any of these sites. Every one forbids it, and Heritage has gone to court over it.
- **Store vendor provenance on every comp** (source, licence, retrieval date) so we can honour purge or retention terms by source, and show the licence basis behind each report value.

## Pilot: the cheap start

A pilot (the 20–30 card controlled set, then one partner collection and one lender looking at a sample report) needs per-grade sold prices, 12 months of history for σ, and permission to show a report to one lender. It doesn't need the 2021–2023 replay or production-grade licensing. Options, cheapest first:

| Option | Cost | Gets us | Catch |
|---|---|---|---|
| **Manual comps** | $0 | A reviewer records each comp (marketplace, URL, date, grade, price) from public sold-results pages: eBay sold, Goldin, Heritage (free account) | Doesn't scale beyond tens of cards. Fine at pilot scale: it's what appraisers do. No scraping, and not copied from aggregator pages, since PriceCharting claims its price data as its own |
| **PokeTrace Pro** | $19.99/mo | Per-grade aggregates with sale counts, medians and history, with commercial use allowed | No sale-level comps. Terms thin. Operator anonymous |
| **PokeTrace Scale** | $98/mo | Adds sold eBay listings (date, price, grader, grade, URL) | Same terms risk |
| **PriceCharting Legendary** | $49/mo | Daily CSV of every card's per-grade values, allowed for internal business use | External display needs permission. Snapshotting daily is the only route to history |
| **PokemonPriceTracker Business** | $99/mo | Graded sales (eBay + Fanatics), history from Jan 2025, explicit commercial terms, data kept after cancelling | No sale-level comps confirmed. Cheaper plans ban all business use, internal included |

**Pilot recommendation (about $20–70/mo):**
- **PokeTrace Pro ($19.99) for the first month.** It's enough to build and test step 3 and σ on real per-grade data. Move to Scale ($98) only once M3 needs sale-level comps for the weighted median and trimming.
- **Manual comps for every card in a pilot report shown to a lender.** Each value in that report then traces to dated sold sales we recorded ourselves, so the report doesn't depend on any vendor's redistribution terms. The controlled set and the first partner collection are small enough for this.
- **Optional: PriceCharting Legendary ($49) as an internal cross-check**, never shown to the lender.
- **The same day:** email PokeTrace and PokemonPriceTracker the confirmation questions below. Whichever confirms report use in writing first becomes the production source until a PriceCharting licence lands. If PokeTrace won't name its company or confirm report use, switch to PokemonPriceTracker Business.
- **Store provenance on every comp from day one** (source, plan, retrieval date, or "manual"), so pilot data can be kept or purged by source later.

## Cost (list prices, 2026-10-16)

- **Pilot:** PokeTrace Pro $19.99/mo, plus optionally PriceCharting Legendary $49/mo, plus reviewer time for manual comps. About $20–70/mo.

- **M3 development past the pilot:** PokeTrace Scale $98/mo (sale-level comps), or PokemonPriceTracker Business $99/mo if PokeTrace won't confirm terms. Add PriceCharting Legendary $49/mo for the CSV while its licence is negotiated (internal use only). About $150/mo in all.
- **Production:** PriceCharting commercial licence (unpublished) plus PokemonPriceTracker Business $99/mo. Add JustTCG Professional $49/mo if raw-condition prices are confirmed as sold data.
- **Replay extract:** one-off, unpriced. Budget a conversation, not a line item.

## Written confirmations to request

Send each vendor these questions, adapted to what it offers. Every licence should also include the eBay-chain warranty and indemnity.

**PriceCharting**
1. A commercial licence for showing derived values (FMV, liquidation values) and the underlying sold comps (date, price, grade, source) in PDF reports to lenders and insurers who aren't PriceCharting users. Price?
2. A carve-out from "purge when the subscription ends" for comps used in reports we've issued, and for our calibration records.
3. Sold-listing-level data (date, price, grade, grading company, marketplace) through the API or a bulk export, not just the combined values.
4. Historic values and sales back to January 2020, for Pokémon, by grade.
5. Whether "Grade 7/8/9/9.5" mix grading companies, and how the combined value is calculated (window, outliers, Best Offer handling).
6. Whether training our own models on the data counts as internal use.

**PokemonPriceTracker**
1. That PDF reports sent to lenders and insurers, including the comp list behind a value, count as display "inside your product" on Business.
2. That we may keep comps used in issued reports permanently after we cancel (the licensing page says yes; get it in the contract).
3. The start date and fields of graded sales history (date, price, grade, grading company, marketplace, Best Offer handling).
4. Whether TCGplayer-based raw prices are sales or listings.
5. Whether training our own models counts as internal use on Business.

**PokeTrace**
1. The legal entity that operates PokeTrace, and the governing jurisdiction.
2. That PDF reports to lenders and insurers, showing derived values and the comps behind them, aren't "redistribution" under §8.
3. That we may store returned prices and listings indefinitely, including after cancelling.
4. How far back price history and sold listings go, by tier (raw and each grade).
5. Whether tier prices are built from sold transactions only, and how anomalies and Best Offers are handled.
6. Whether training our own models on the data is allowed.

**Courtyard**
1. A licence to use peer-to-peer marketplace sales (cert, grader, grade, card, price, date; buybacks excluded) in collateral and insurance reports, including bulk access to token metadata.
2. Whether they'd act as a liquidation channel for lenders' defaulted collateral (vaulting, instant sale), and on what terms.

**Collectors (Card Ladder and PSA APR)**
1. A licence, internal-use only if need be, for a one-off sale-level extract: Pokémon, January 2020 to December 2023, per grade, with marketplace and date, for validating a liquidation model.
2. Whether ongoing commercial access to sold data (Card Ladder or APR) is available for reports to lenders, and at what price.

**JustTCG and Scrydex**
1. Which price fields are sold transactions and which are listings, and from which marketplaces.
2. Whether data stored during the subscription may be kept for issued reports after we cancel (JustTCG's terms say no).
3. History depth for raw-condition and graded prices.
4. Scrydex: whether reports to lenders count as "commercially exploit[ing] the Services" under §4.

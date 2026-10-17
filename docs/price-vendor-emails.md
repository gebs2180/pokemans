# Price vendor confirmation emails (drafts)

Drafted 2026-10-16 from the questions in [price-data-sources.md](price-data-sources.md#written-confirmations-to-request). Fill in the bracketed parts before sending. Ask for answers by email (or an order form or contract), so we have them in writing. A chat reply or a support-widget answer doesn't count.

- **PokeTrace:** contact@poketrace.com (from their OpenAPI contact and pricing page).
- **PokemonPriceTracker:** no address checked. Use the contact or enterprise form linked from pokemonpricetracker.com/licensing.

---

## PokeTrace

**To:** contact@poketrace.com
**Subject:** Commercial licence questions before subscribing (Pro/Scale)

Hi PokeTrace team,

I'm building [product name], which produces valuation reports for collections of Pokémon cards. The reports estimate what a collection would fetch in a quick or forced sale, and they go to lenders who lend against the collection and insurers who cover it. I'd like to use PokeTrace as a price source, starting on Pro and moving to Scale for sold listings. Before I do, could you confirm the following in writing?

1. **Who we'd be contracting with.** What is the legal entity that operates PokeTrace, and which jurisdiction's law applies? (Section 11 refers to "the jurisdiction in which PokeTrace operates".)
2. **Reports to third parties.** Our reports are PDFs sent to a lender or insurer, who doesn't log in to anything. They show our derived values per card and for the whole collection. Ideally they also list the sold comps behind each value: date, price, grade and marketplace. Do you treat that as permitted use, rather than "redistribution" under Section 8? If the comp list is a problem but derived values are fine, please say so.
3. **Storage and retention.** May we store the prices and sold listings we retrieve indefinitely, including after we cancel? We have to be able to show the evidence behind any report we've already issued, and we use past data to check how accurate our estimates were.
4. **History depth.** How far back do price history (`period=all`) and sold listings go, for raw tiers and for graded tiers such as PSA_10? Specifically, do you have data for 2021–2023?
5. **What the prices are built from.** Are tier prices (`avg`, `median7d`, `median30d` and so on) built only from completed sales, or do they include active listings? How are accepted Best Offers and the listings you flag as anomalies treated?
6. **Model training.** May we use the data to train and evaluate our own internal valuation models? These aren't offered as a data product or a competing price service.
7. **Data rights.** Your US prices come partly from eBay. Can you confirm you have the rights to license this data for commercial use like ours, and would you put that in an order form or contract?

If any of these need a custom or business plan, I'm happy to discuss pricing. We'd expect [N] cards a month during a pilot, growing from there.

Thanks,
[Your name]
[Company name, if any]

---

## PokemonPriceTracker

**To:** [contact or enterprise form, pokemonpricetracker.com/licensing]
**Subject:** Business plan: confirming use in valuation reports for lenders

Hi,

I'm building [product name], which produces valuation reports for collections of Pokémon cards. The reports estimate what a collection would fetch in a quick or forced sale, and they go to lenders who lend against the collection and insurers who cover it. I'm planning to subscribe to the Business plan and have read your licensing page. Could you confirm a few points in writing first?

1. **Reports to third parties.** Our reports are PDFs sent to a lender or insurer, who doesn't log in to anything. They show our derived values. Ideally they also list the individual graded sales behind each value: date, price, grade and marketplace. Does that count as display "inside your product" on Business? Or would the sales list count as bulk export to third parties? If only derived values are allowed, please say so.
2. **Retention.** Your licensing page says we can keep data gathered while subscribed for use in our own product after we cancel. Can you confirm that covers keeping it indefinitely for reports we've already issued, and for our own accuracy records? Could that be put in our agreement?
3. **Graded sales history.** When does graded sales history (eBay and Fanatics Collect) start? Which fields does each sale include: date, price, grade, grading company, marketplace? How are accepted Best Offers handled?
4. **Raw prices.** Are the TCGplayer-based raw prices built from completed sales, listings, or both?
5. **Model training.** Does training and evaluating our own internal valuation models count as permitted internal business use on Business? These aren't offered as a data product or a competing price API.
6. **Data rights.** Your graded sales come from eBay and Fanatics Collect. Can you confirm you have the rights to license this data for commercial use like ours?

If Enterprise or a custom agreement is the better fit for this, I'm happy to talk.

Thanks,
[Your name]
[Company name, if any]

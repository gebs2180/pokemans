# Pre-grading providers: terms review

Checked 2026-10-16 against the providers' public pages. This is a reading of the terms, not legal advice. Re-check before signing up, and get anything marked "confirm" in writing.

We need three rights from a provider (HLD §3, CLAUDE.md "store everything"):
1. **Store** every prediction indefinitely, alongside our photos and returned grades.
2. **Train and evaluate** our own condition model on those predictions (calibration, comparison against returned PSA grades).
3. **Show** derived results to third parties: lenders and insurers who get PDF reports, not app logins.

## Ximilar (Czech Republic): usable, confirmed in writing

- **API:** `/v2/grade` gives 1–10 grades per corner, edge, surface and centering, plus a final grade (geometric mean, 70/30 front/back by default) and a condition label. `/v2/centering` gives L/R and T/B ratios and margins. It reports detection and tag probabilities, but no probability distribution over grades. It wants a short side of at least 2000 px, unedited, and no sleeve or slab.
- **Storing results:** confirmed. Ximilar wrote on 2026-10-30 that the results are ours, and we can keep them as long as we want, with our own photos and any later grades.
- **Training our own model:** confirmed in the same reply. The model engine itself stays Ximilar's.
- **Our photos:** Ximilar keeps images temporarily so we can see the grading visualization, and removes them automatically after a week. This corrects the 2026-10-16 reading that inference images aren't stored. It says it uses no customer data for training.
- **Third parties:** confirmed. Commercial use is allowed, including values derived from the results in reports for our business customers.
- **Other:** liability is capped at fees paid, and is zero on the free plan. Czech law applies, with Vienna Rules arbitration. Pricing is credit-based and wasn't checked.
- **Confirmed in writing:** Michal at Ximilar replied by email on 2026-10-30, covering storage, training, commercial use and image handling. Zach has the email. Grading costs 100 credits per card, per the same reply.

Sources: [terms](https://www.ximilar.com/terms-of-use-privacy/), [data security FAQ](https://www.ximilar.com/faq/data-security/), [grading docs](https://docs.ximilar.com/collectibles/card-grading).

## CardGrader.AI (Florida): blocked as-is

- **API:** `POST /v1/grades` (subscription: $250/mo for 5,000, or $449/mo for 10,000) or prepaid scans. It returns a predicted grade plus centering, corner, edge and surface sub-grades. Commercial subscription grades are numbers only, with no grade probabilities. The rate limit is 60 requests per minute.
- **Storing results:** **prohibited**: "Store and redistribute Results as a dataset" (API Terms §4).
- **Training our own model:** **prohibited**: no using the API or Results "to train, fine-tune, evaluate or build a competing card grading" (or identification or pricing) model (§4). Our condition model is a grader, and comparing them against returned grades is "evaluate".
- **Third parties:** Results may be shown only "to users of your own app, website or service" (§1) and only "through your product" (§4). It needs attribution ("Grading by CardGrader.AI" with a link) and must be presented as an estimate (§2–3). PDF reports for lenders are a grey area.
- **Our photos:** it may use images and results "to operate, secure and improve our service" (§6). There's no opt-out and no deletion deadline. Prepaid scans store images. Its data policy says a no-training agreement, a deletion deadline or a DPA can be agreed in writing before production data is sent.
- **Other:** liability is capped at 3 months of fees, and it requires an indemnity from us. Florida law applies.
- **Use only if** they grant a written carve-out for §4 (storing, training and evaluating) and for reports sent to third parties. Without one, storing its outputs breaks the terms, and not storing them breaks our store-everything principle.

Sources: [API terms](https://cardgrader.ai/api-terms), [API data policy](https://cardgrader.ai/api-data-policy), [API docs](https://cardgrader.ai/api-docs).

## CardGrade.io (Pennsylvania): usable, opt out of training

- **API:** a REST API (OpenAPI 3.1). It returns an overall grade, centering, corners, edges and surface sub-grades, PSA/BGS/CGC predictions, a `limitingFactor`, per-zone corner and edge scores, centering percentages, zone close-up crops, and a market value range when it has one. It returns no grade probabilities. Plans: Starter at $149.99/mo (1,000 grades, 30 per minute), Growth at $399.99/mo (4,000, 60 per minute), Scale at $999.99/mo (12,000, 120 per minute). Pro ($24.99) and Business ($74.99) also include some API access.
- **Storing results / training our own model:** not restricted. The terms have no clause on output ownership, competing models or benchmarking. The anti-bot clause (no automated access "without our express written permission") should be covered by an API subscription. Confirm that.
- **Our photos:** **they train on uploads and results by default** (§5–6): "develop, train, test, and improve our AI models". You can opt out in Settings. De-identified derivatives (crops, labels) can survive account deletion. Opt out before sending partner photos.
- **Third parties:** not addressed.
- **Other:** Pennsylvania law, with arbitration.
- **Confirm in writing:** that API use is the permitted automated access, that we can store and train on results, that our account is opted out of training, and that we can use results in lender reports.

Sources: [terms](https://cardgrade.io/terms), [API announcement](https://cardgrade.io/blog/introducing-cardgrade-api).

## Market scan: top pre-grading tools (consumer apps, Oct 2026)

Every accuracy figure below is self-reported on the vendor's own blog. There is no independent head-to-head test. Our controlled PSA set will be the first real comparison.

| Tool | Claimed accuracy | API? | Terms fit |
|---|---|---|---|
| SnapGradeAI | 87% within ±0.5 of PSA over 412 logged returns (the only public returns log) | none found | n/a |
| PreGradeCards | 89% within ±1, 68% exact, on a 10k-card benchmark | none found | n/a |
| CardGrade.io | 92.8% (described as model confidence, not PSA match) | yes | OK, opt out of training |
| CardMintAI | ~95% "PSA match", no published method | yes, on $25+ plans, no docs | unclear: §5 "may not copy, modify, distribute, or reproduce any materials from the platform" without permission; outputs may count as theirs |
| CardGrader.AI | not checked | yes | blocked (see above) |
| Ximilar | n/a (B2B vision API) | yes | OK |
| CardGrading.app, Gradeon, MasterGrade, OneGrade | none published | none found | n/a |
| Ludex | cataloguing, condition flags only | n/a | n/a |

## Others looked at

- **PREGRAiDED:** its terms bar building a competing pre-grading service or reselling reports, so the same problem as CardGrader.AI.
- **Nyckel:** a generic 10-class condition classifier. Under its terms (Common Paper CSA), our training data is used only for our own models. Output ownership and restrictions on the customer weren't visible. It's weaker than card-specialised graders.
- **Scrydex Vision, CardSight AI:** identification and slab reading, not raw condition. They're possible catalog or ID candidates.

## Recommendation

- **Provider A: Ximilar.** Its terms fit and it doesn't keep our photos.
- **Provider B: CardGrade.io.** Its terms don't restrict our use of results, and it gives the richest output (PSA/BGS/CGC predictions, limiting factor, zone scores). Opt out of training before sending partner photos. Start on Pro ($24.99) for the controlled set before paying for an API tier.
- **Before M2 stores production data:** email both to confirm the rights listed above. Ximilar confirmed on 2026-10-30; CardGrade.io has not replied yet.
- **Dropped:** CardGrader.AI (§4 bans storing and training). CardMintAI unless it clarifies output rights in writing.
- **Neither A nor B** returns a grade distribution. Our distribution comes from their point grades and sub-grades plus the reviewer, which is what M2 planned.

## Cost (list prices, 2026-10-16)

- **Ximilar:** "Grade a card" costs 100 credits, and front plus back in one request counts as one card. The free plan has 1,000 credits a month (about 10 cards). Business 100K is $69/mo (about 1,000 cards, roughly 7¢ each). Top-up packs that never expire start at 10,000 credits for $11 (about 100 cards).
- **CardGrade.io:** Pro is $24.99/mo for 80 grades at 10 requests per minute. Business is $74.99/mo for 300 grades at 30 per minute. API Starter is $149.99/mo for 1,000 (15¢ each). It doesn't say whether front plus back is one credit. Confirm that.
- **Controlled set (30 cards):** about $11 on a Ximilar top-up (or its free plan over a few months) plus one month of CardGrade.io Pro ($24.99), so about $36 in all.
- **Per 1,000 partner cards:** about $69 (Ximilar) + $150 (CardGrade.io API Starter), so about $220. That's about 22¢ per card for both opinions. Only cards that pass quality checks and are worth over $25 get sent, and each card is sent once.

## Our own: centering (RAWMARK_CENTERING), off by default

The first step towards our own grader. It runs in the app, so there are no terms to clear and no cost. Turn it on with `CENTERING_ENABLED=true`.

- **What it does:** on the passing front and back it straightens the card from its outline, refines the four outer edges at full resolution, finds each border's inner edge, and reports left/right and top/bottom ratios with the border widths in mm (`CenteringMeasurer`).
- **What it reports:** centering only. Its centering grade is the best PSA grade the ratios allow, from PSA's published table (`PsaCentering`, read from psacard.com/gradingstandards on 2026-10-28), with 1 percentage point added for measurement error (`pokemans.condition.centering.measurement-margin`).
- **How it's used:** it caps the system distribution (probability above the allowed grade moves onto it) and is never averaged in as a grade, so it can only lower a card.
- **What collectors see:** the rounded ratios per measured side on the card page, from this measurement only. No other provider's numbers or names reach them.
- **Ximilar's centering isn't used (Zach, 2026-10-31):** on a Metagross front with nearly even borders it read 42/58 and 44/56 against about 48/52 measured. Its score and ratios are stored but no longer reach the review page or "Holding it back". Its overall grade still includes centering and is used as it comes: recomputing it without centering could raise a card.
- **When it says nothing:** full-art and borderless cards, glare, sleeves, borders under about 0.6 mm, and cards whose inner edge isn't found come back "not measured" with the reason. A side's inner edge needs 5 of 8 pieces to agree; one side alone may rest on 3 if the other three are firm and left + right is within 10% of top + bottom. A side that isn't measured doesn't limit the cap.
- **Calibration:** `RealPhotoCenteringTest` with `POKEMANS_REAL_PHOTOS=<folder>` prints the ratios and writes each straightened card with the measured lines drawn on it.

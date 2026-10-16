# Pre-grading providers: terms review

Checked 2026-10-16 against the providers' public pages. This is a reading of the terms, not legal advice. Re-check before signing up, and get anything marked "confirm" in writing.

We need three rights from a provider (HLD §3, CLAUDE.md "store everything"):
1. **Store** every prediction indefinitely, alongside our photos and returned grades.
2. **Train and evaluate** our own condition model on those predictions (calibration, comparison against returned PSA grades).
3. **Show** derived results to third parties: lenders and insurers who get PDF reports, not app logins.

## Ximilar (Czech Republic): usable

- **API:** `/v2/grade` gives 1–10 grades per corner, edge, surface and centering, plus a final grade (geometric mean, 70/30 front/back by default) and a condition label. `/v2/centering` gives L/R and T/B ratios and margins. It reports detection and tag probabilities, but no probability distribution over grades. It wants a short side of at least 2000 px, unedited, and no sleeve or slab.
- **Storing results:** not restricted. The data-security FAQ says the customer "retain[s] all rights to your own training data and its outputs", including prediction results, and that results are exportable through the API.
- **Training our own model:** not restricted. The terms have no clause against competing models, benchmarking or reverse engineering. The model engine itself stays Ximilar's.
- **Our photos:** inference images are processed in memory and not stored. Ximilar won't use our content to train models offered to other customers without written permission.
- **Third parties:** not addressed for users.
- **Other:** liability is capped at fees paid, and is zero on the free plan. Czech law applies, with Vienna Rules arbitration. Pricing is credit-based and wasn't checked.
- **Confirm in writing:** that we may keep results indefinitely, train our own model on them, and include derived values in reports for lenders.

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

## Others looked at

- **CardMintAI:** advertises an embeddable API. No public API terms were found, so we'd need to ask.
- **OneGrade:** advertises a grading engine API. No docs or terms were found.
- **PREGRAiDED:** its terms bar building a competing pre-grading service or reselling reports, so the same problem as CardGrader.AI.
- **Nyckel:** a pretrained 10-class condition classifier that can be retrained on our data. It's generic rather than card-specialised. Terms not checked.
- **Scrydex Vision, CardSight AI:** identification and slab reading, not raw condition. They're possible catalog or ID candidates, not pre-graders.

## Recommendation

- **Provider A: Ximilar.** Its terms fit. Get the three rights above confirmed by email before M2 stores production data.
- **Provider B:** ask CardGrader.AI for the written carve-out, since its sub-grade output is the closest comparison. If they refuse, ask CardMintAI for its API terms. If nothing clears in time, M2 ships with Ximilar plus the human reviewer as the second opinion. The `PreGradeProvider` port keeps adding B later cheap.
- **Either way:** neither returns a grade distribution. Our distribution comes from their point grades and sub-grades plus the reviewer, which is what M2 planned.

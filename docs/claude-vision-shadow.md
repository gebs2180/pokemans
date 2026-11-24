# claude-vision: a second pre-grader, in shadow

**Status:** built 2026-11-23, off by default (`CLAUDE_VISION_ENABLED=false`). It stores its answers and changes nothing else: not the condition distribution, FMV, offers or the review queue. Whether it ever replaces or joins CardGrade is Zach's methodology decision, made after PSA grades come back. This note isn't the methodology doc and doesn't change it.

## What it does

After CardGrade answers for a card, the worker queues a `claude-vision` request on the same photos (`ConditionService.queueShadow`). It never runs on its own and never runs before CardGrade. The provider is `ClaudeVisionPreGradeProvider`.

1. **Views** (`ClaudeVisionViews`). Each side is straightened from intake's card outline onto a 63 × 88 mm canvas at 32 px/mm, with 3 mm of background past the edge so the true edge is always in the picture. Nine pictures go per side: the whole card, then four corner close-ups (14 mm square), then four edge strips (7 mm deep), each after a text label such as "Back — top edge". That makes 18 pictures for a card with both sides. If no outline is found, the whole photo goes instead and the details say so.
2. **Rubric** (`ClaudeVisionRubric`, version `rubric-1`). The system prompt is fixed. It paraphrases PSA's 1–10 definitions and says:
   - Grade corners, edges and surface in half points, taking the worse of front and back.
   - Check every back edge and back corner for whitening, chipping and silvering.
   - Check the holo for fine scratches, scuffs, dents and print lines, and the non-holo areas and the back for scratches and dents.
   - Any crease caps the card at 4.
   - The overall grade follows the weakest sub-grade, not the average.
   - When torn between two grades, give the lower. An area hidden by glare or a sleeve is not assumed clean.
   - The notes for each sub-grade name the visible defects and where they are.

   The answer is structured JSON (`output_config.format`, a JSON schema): each sub-grade's grade and notes, the overall grade, the limiting factor and the hidden areas. The model is never asked about centering.
3. **Sampling.** The same request is sent `CLAUDE_VISION_SAMPLES` times (default 3) in one Message Batches API call, using Opus 5.5 at high effort. Batches cost half price and usually answer in minutes, always within the hour. The pictures carry a cache breakpoint, so later samples can read them from cache.
4. **Aggregation.** Grades are always rounded down to the half point.
   - **Sub-grades:** each is the median across the usable samples. With an even count, the lower of the middle two is taken, never interpolated.
   - **Overall:** the lowest of the model's median overall, the weakest median sub-grade, and our own centering cap.
   - **Limiting factor:** "centering" when our cap set the overall; otherwise the weakest sub-grade, with ties going to the one most samples named.
   - **PSA grade:** 9.5 reads as 9.
   - **Spread:** highest minus lowest across the samples, stored for each sub-grade and for the overall.
   - **Notes:** taken from the sample whose overall equals the median.
   - **Failures:** a refusal, a cut-off answer or JSON outside the schema makes a failed sample. When no sample is usable, the prediction is stored as failed.
5. **Centering is ours.** At submit, `CenteringPreGradeProvider` (the same measurer that caps the distribution) measures both sides. Its PSA cap is stored as the prediction's centering and caps the overall. The measurement travels in the job id until the batch ends.

## Stored

Each answer gets one `provider_prediction` row with provider `claude-vision`, whether it is right or wrong:

- `model_version`: e.g. `claude-opus-5-5/rubric-1`.
- `overall_grade`, `psa_grade`, `centering` (our cap), `corners`, `edges` and `surface`.
- `details`:
  - `limitingFactor`, `modelOverallMedian`, `spread`, `samplesRequested`, `samplesUsed`.
  - Each usable sample's grades.
  - The per-sub-grade `notes` and `hiddenAreas`.
  - `psaCenteringCap`, `frontViews` and `backViews`.
  - `costUsd`.
- The raw response: every sample's answer (text, stop reason, usage), the centering measurement and the summed usage. The pictures aren't kept there; the photos are already stored.

## Kept out of everything else

`ProviderPrediction.SHADOW` lists `claude-vision`. It is left out of:

- the system distribution and the rebuild that makes it (`latestSucceeded`);
- the providers "Ask again" and the sweep queue;
- the wait before a rebuild;
- the review queue's open counts;
- the "pre-grade failing" count.

So FMV, offers and what sellers see are the same with the switch on or off. One thing does change: an admin's review page keeps refreshing while a shadow request is open.

## Switch and keys

| Env | Default | |
|---|---|---|
| `CLAUDE_VISION_ENABLED` | `false` | Turns the shadow on. With it off, the bean doesn't exist and nothing is queued. |
| `CLAUDE_VISION_API_KEY` | falls back to `ANTHROPIC_API_KEY` | Required when enabled; startup fails without it. Keep it in Bitwarden with the other secrets, never in git. |
| `CLAUDE_VISION_SAMPLES` | `3` | Samples per card. |

The model, effort, max tokens and the token prices used for the cost estimate are under `pokemans.condition.claude-vision` in `application.yml`.

## Cost

Every card sends the same 18 pictures, because the views are a fixed size. That is about **18,500 input tokens per sample**, counted from the pixels of the views of six real cards with `ClaudeVisionLiveCheck`. At batch prices ($2 in, $10 out per million tokens on Opus 5.5), with 3 samples:

- **Input:** $0.05 per card if samples 2 and 3 read the pictures from cache, and $0.14 if none do. Caching across a batch is best effort.
- **Output:** not measured yet. It is the thinking plus the JSON, about $0.02–0.06 per sample at 2,000–6,000 tokens.
- **Total:** about $0.11–0.32 per card, until a live run measures it.

To measure it, run the live check on a local folder of photos (name-front.jpg and name-back.jpg; they stay out of git):

```powershell
cd backend; $env:CLAUDE_VISION_LIVE_PHOTOS = "C:\Users\chief\Pictures\cardgrade"; $env:CLAUDE_VISION_API_KEY = "<key>"; .\gradlew.bat :app:test --tests "*ClaudeVisionLiveCheck" --rerun -i
```

It prints the reading and the usage, and writes the raw answer to `backend/build/claude-vision-live/`. In the app, each prediction's `details.costUsd` is computed from the usage the batch returns.

## Comparing against PSA returns

`GET /api/admin/validation/pregrade-comparison` (admin only) lists every card claude-vision has graded. Each row shows:

- claude-vision's latest answer: grades, limiting factor, overall spread, cost and notes;
- CardGrade's latest answer on **the same photos**;
- the card's latest PSA grade, when one is back. A buyer's grade that was later corrected or withdrawn is left out, as in the calibration report.

The summary over cards with a PSA grade gives, for each provider:

- the mean absolute gap from PSA;
- the share within ±1 grade (the methodology's target is ≥ 80%);
- the share **above** PSA. That is the costly direction, because an offer made on a grade the card doesn't get loses money.

The prediction compared is the provider's PSA grade, falling back to its overall.

How to read it once grades come back:

- Look at `withPsa` first: a handful of cards says little. The validation plan wants 1,000+ partner cards plus the 20–30-card controlled PSA set.
- Check each provider's "above PSA" share against the stressed-median target (below it < 5%) before its ±1 share. A provider that is close on average but often above PSA is the worse one for us.
- Read the notes where the two disagree. They should name the defect PSA marked down for, such as back-edge whitening or a holo scratch. If they don't, the model didn't see it.
- Don't compare rows across rubric versions (`model_version`). A changed prompt is a new provider for this purpose.

Any change to which provider feeds the distribution goes through Zach and into the methodology doc first.

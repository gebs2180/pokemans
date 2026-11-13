# Plan: read the printed frame on hard cards

Goal: measure front centering on foil, gold and full-art cards, where it matters most and where we refuse most, without ever reporting a number we can't stand behind.

Written 2026-11-01. The labelling page and scoreboard from step 1 are built, and the rest isn't. The effort figures are estimates.

## What's going wrong today

The frames are there. On the two fronts refused on 2026-11-01:

| Card | What the frame looks like | Why the measurer refuses |
|---|---|---|
| Giratina VSTAR (gold) | Clear: about 2.5 mm on the left, right and bottom, a little more on top | The copyright line sits inside the bottom border, and the rule box covers the frame line for part of the side. The measurer takes the first strong step in from the edge, so pieces of the side land on the text |
| Victini (red foil) | Faint but visible once contrast is raised: about 2.2–2.9 mm | The step is 5–15 units where Giratina's is 100+, below the weakest edge we accept, while foil texture makes stronger steps elsewhere |

Three limits in the design cause this, and only the third needs training:

1. **Each side is solved alone.** The frame is one printed rectangle, but we look for four unrelated lines. A side whose line is half covered gets no help from the other three.
2. **The rule is "first strong step".** That suits a yellow border with nothing printed in it. Text, set symbols and rule boxes in the border break it.
3. **Hand-set thresholds can't separate faint print from foil texture.** A person does this by knowing what a card looks like. That's what a small model can learn.

How precise this has to be: a border of 2.5 mm a side means 1 percentage point of centering is about 0.05 mm, or 1 px on our 20 px/mm card. The grade limits sit at 55/45 and 60/40, and our margin is 1 point. A model that's "about right" isn't good enough, so measuring the error comes first.

## Data today

Prod has 5 cards, 30 front and back photos and 0 returned grades. That's far too little to train on, so step 1 and step 2 come before any model.

## Step 1: labels and a scoreboard (about 1 week)

Everything else is judged by this.

- **Labelling tool.** An admin page shows the straightened card, and you drag four lines onto the frame's inner edges, or mark a side "can't see it". It stores border widths in mm with who and when. Each label takes about 30 seconds.
- **One label per card, many photos.** Border widths are physical, so a label applies to every photo of that card. That multiplies the training data, and it gives a test that needs no labels at all: photos of one card should read the same.
- **Scoreboard.** A repeatable run over the labelled set that reports:
  - error in percentage points (median and 95th percentile)
  - share of cards measured
  - **false measurements:** a number more than 2 points from the truth, the worst failure
  - agreement between photos of the same card
  - whether the PSA cap came out the same as with the true borders
- **Baseline.** Score today's measurer first, so every later change has a number to beat.

Splits are by card and by set, never by photo. Otherwise the model is tested on cards it has already seen.

### Labelling and scoring

Labels are made at `/admin/centering`, linked from the admin page. Cards the measurer refused come first.

1. Open a card. The lines start at a fixed 2.5 mm, never at the measurer's answer, which would pull the label towards it. The queue shows only whether it refused.
2. Drag each line onto the inside edge of the printed frame. The zoom shows the chosen line, the arrow keys move it 0.05 mm, and "Raise contrast" brings up faint frames.
3. Tick "Can't see it" for a side you can't read, and "Hard card" for gold, foil or full art. Don't guess a side: a guess becomes the truth the measurer is scored against.
4. Press "Done". Labelling is blind: until then the page shows only the widths in mm, never the ratios, the grades they allow or the measurer's reading, so nothing pulls the lines. Done saves the label, then shows it beside the measurer's fresh reading of the same photo, with the best grade PSA, BGS, CGC and TAG allow for each. Those other graders' tables are for reference only: offers and the centering cap use PSA alone.

Each label stores `revealed_at`, when its result was shown, which is when it was saved. Labels made before blind labelling (2026-11-13) have none, so the scoreboard can tell them apart.

Labelling a card again adds a row and the newest counts, so two passes can be compared. A photo that fails at the card's outer edge can't be straightened, so the page says why and offers the card's other photos. If it has none, it needs a retake, usually on a dark surface.

To score, press "Download scoreboard file" and run this straight away, because the file's photo links expire. Each photo is then kept beside the file, so later runs don't need the links. The file and that folder hold real photos' links and uploads with their EXIF, so keep them out of the repo.

```powershell
cd backend; $env:POKEMANS_CENTERING_SCOREBOARD = "C:\Users\you\centering\centering-scoreboard-2026-11-02.json"; .\gradlew.bat :app:test --tests "*RealPhotoScoreboardTest" --rerun
```

It writes `<file>.board.md` with the table below, and `<file>.photos.csv` with every photo's outcome. Diff the CSV between two runs to see exactly which photos changed.

How to read the columns:

- **Error:** the worse of left/right and top/bottom, in percentage points, for each measured photo against its label.
- **False:** photos with an error over 2 points.
- **Seen by nobody:** photos measured although the label says a side can't be seen, so nothing backs the number.
- **Same cap:** the PSA cap matches the true borders' cap at the same 1-point margin.
- **Cap too high:** the cap is above what the true borders allow with no margin. This is the failure that would raise a card, so it should stay at 0.
- **Spread:** on a face measured on two or more photos, the most those photos differ. It needs no labels.

### Baseline (2026-11-01, `centering-3`)

Six cards and 12 distinct photos from the 2026-11-01 checks, none labelled yet, so only the columns that need no labels mean anything. Six cards is far too few to set a gate, and this only records where we started.

| Group | Photos measured | Faces measured | Faces with 2+ photos | Spread median |
|---|---|---|---|---|
| all | 5/12 (42%) | 4/9 (44%) | 1 | 0.9 |
| hard fronts | 2/5 (40%) | 1/4 (25%) | 1 | 0.9 |
| other fronts | 0/1 (0%) | 0/1 (0%) | 0 | – |
| backs | 3/6 (50%) | 3/4 (75%) | 0 | – |

Why the seven photos were refused:

| Photos | Refused because |
|---|---|
| Victini front, Giratina front | Inner edge: the bottom border's pieces disagree (3 and 4 of 8 agree) |
| Rainbow full art front, one Metagross back | Outer edge: the card doesn't stand out along one side |
| Two sleeved backs, one card far from the camera | No whole card found |

So only two of the seven are the inner-edge failures the rectangle fit is meant to fix. The fit can't help the other five, because they never reach the inner edge.

The error, false measurement and cap columns have no baseline until cards are labelled. **Step 3 waits for that:** the first run over the labelled set is the real baseline, and the fit has to beat it.

## Step 2: a controlled set of cards (about 2 sessions)

Start with 200–300 fronts from Zach's collection and the second tester's, weighted to the hard cases:

- gold, rainbow, full art, special illustration rare, textured foil
- vintage holos, Japanese cards, a few ordinary yellow borders as a control
- deliberately off-centre cards, so the model doesn't just learn "about even"

Take 3–4 photos of each: white stand, dark surface, slight angle, different lighting. At 3–4 photos a card that's about 1,000 photos and 2–3 hours of labelling.

HLD says photos can feed training only when the uploading org's agreement allows it. So use Zach's own photos, and the second tester's once they've agreed to it in writing. Other beta sellers' photos stay out until that's checked. The white-stand photos in the 2026-11-01 table are the second tester's.

## Step 3: fit one rectangle (about 1 week, no training)

Replace the four separate searches with one fit: find the rectangle whose sides have the most support along their length, letting text and overlays interrupt a side.

- Every piece of every side gets a set of candidate positions instead of a single first step.
- The fit picks positions that are consistent with one printed frame, using what we know of print: borders are about 2–4 mm, and left + right is close to top + bottom.
- The same "pieces must agree" discipline stays. A rectangle resting on too little evidence is still refused.

This should rescue Giratina-type cards and is cheap to verify on the scoreboard. It may or may not rescue Victini. Either way it gives the model's output a place to plug in, because step 4 only replaces the evidence, not the fit.

## Step 4: train a small model on frame evidence (about 2–3 weeks)

Train only if the scoreboard shows step 3 still misses too many cards.

- **What it does:** it takes a straightened strip along each side, and for each point along the strip scores where the frame line is, plus "can't see it here". It doesn't output a ratio or a grade, so a person can check each line drawn on the card, and the rectangle fit stays deterministic.
- **Why not a whole-card model:** it would need thousands of labelled cards and couldn't show why it answered. Each card gives four long strips, so about 1,000 photos is a realistic start for this smaller job.
- **Size:** small enough to run on the server's 2 CPUs and 4 GB, and exported to ONNX. OpenCV may be able to run it, which would avoid a new runtime. Check that first.
- **Training:** in Python on a personal machine or a personal cloud account, never employer resources.
- **Augmentation:** glare, foil sparkle, blur, colour shifts and small warps, so that one photo teaches more than one lighting case.
- **Knowing when not to answer:** compare results across flipped strips or a small ensemble. If they disagree, the side is unmeasured.

Stored like any provider, as a new name and version, with every output kept, right or wrong.

## Step 5: shadow, then switch on

1. **Shadow.** Run beside the current measurer on real uploads with no effect on caps, and review disagreements. These are the most valuable new labels.
2. **Gates, on held-out cards and sets:**
   - false measurements under 1%
   - 95th percentile error within 1 point
   - photos of one card agree within 1 point
   - hard-card fronts measured at least 75% of the time
3. **Switch on with conservative rules:**
   - The existing measurer's answer stands where it has one.
   - The model fills in only what was unmeasured. That can only lower a cap, since an unmeasured side has no limit.
   - Results are never averaged, and when two measurements disagree the stricter cap wins.
   - If its error is wider than the current margin, the margin grows for its readings.
4. **Keep checking.** Compare with returned PSA grades as they come in, and retrain from reviewed disagreements under a new version.

The gate figures are starting proposals. Set them properly once the baseline is in.

## Open questions

- **Catalog images:** clean card scans could seed synthetic training data, and could show where the frame sits per card, but their image rights need checking first. The same care applies to any scraped or third-party photos. Don't train on another provider's outputs without clearing their terms.
- **Very faint frames:** some cards may have no frame a person can agree on. Measure how often two passes of labelling on the same cards differ. That's the limit no model can beat.
- **Methodology:** a model-measured cap should be added to the methodology doc before it affects any price.

## In the meantime

- A dark surface gives the outer edge more contrast than the white stand, which helps the first stage for pale-edged cards. It won't by itself fix the inner frame.
- Soft, even light helps Victini-type foil most, because glare and texture are what hide the line.

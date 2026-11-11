# Where to get training data for our own pre-grader

Written 2026-11-02. A plan, not code. Volumes, times and costs marked *est.* are my estimates, not quoted figures.

**What we need:** pairs of *raw* card photos (front and back, short side ≥ 2000 px, ideally to our capture standard) and the grade the *same physical card* later got. PSA first; CGC, BGS and TAG are useful for pretraining and for sub-scores. The validation target is 1,000+ cards with ±1 of the predicted median ≥ 80% of the time.

**Rule that filters everything below:** data must be licensable for commercial use, and we check terms before storing or training on anything third-party (CLAUDE.md, `docs/pregrade-providers.md`). Anything that needs scraping against a site's terms is marked **ToS breach** and isn't recommended, however much data it holds.

## The main finding

Zach's experience, that few collectors photograph before grading and keep the photos, holds online too: **there's no public, licensable archive of raw photo plus later grade.** The largest existing pools (eBay raw listings later relisted slabbed, Reddit and Discord "guess the grade" posts, YouTube grading reveals) can only be collected by scraping against the sites' terms. Grader images belong to the graders. No licensable Pokémon dataset turned up on GitHub, Kaggle, Hugging Face or Roboflow.

So up-front data has to be **bought or licensed from whoever already holds an archive**, or **made quickly from cards we buy**. The up-front shortlist below is about that. The prospective sources (data that accumulates from now on) follow it, since they're cheaper per pair but slower.

## Up-front: data that exists today

Ranked by how likely each is to give us a usable batch within weeks.

### U1. Buy the archives of raw-to-slab flippers, group submitters and shops

**Who:** people whose business is buying raw, grading and reselling, or submitting for others. Many photograph every raw card at intake (to list it, to prove condition for insurance, or to settle disputes with customers) and keep the cert when it returns. That's exactly a raw-photo-plus-grade archive, sitting on hard drives.

- **Volume:** *est.* hundreds to several thousand pairs per active operation; the first deal could deliver 1,000 at once.
- **Photos:** mixed, often decent (flippers photograph to sell). Front and back varies; filter with our quality checks.
- **Labels:** exact grade, matched by their own records. Verify a sample against PSA's cert lookup (data only, not images).
- **Cost:** *est.* $1–5 per pair or a revenue share, plus free Rawmark pre-grades and offers.
- **Time:** weeks once one says yes.
- **Licensing:** low *for photos they took of cards they owned*. Photos of a customer's card (group submitters) are fine to license as the photographer's copyright, but tell customers in a privacy note. Get a written licence: commercial use, training, perpetual, no exclusivity needed.
- **Next step:** find 10–20 high-volume Pokémon raw-to-slab eBay sellers and group submitters by hand (eBay store pages, submission-service sites, Elite Fourum vendors). Email: "Do you keep photos of raw cards you've graded, with the cert? We'd license the archive for training a grading model: $X per pair with front and back, and we never republish the images."

### U2. Photograph PSA slabs we buy (1,000 PSA-labelled images in weeks, no licence needed)

**Why:** the fastest licensable 1,000. Buy cheap PSA-graded Pokémon slabs across grades 1–10, photograph them through the plastic on our capture standard, front and back, and resell. The grade is on the label; the photos are ours.

- **Volume:** as many as we buy. 1,000 is realistic.
- **Photos:** ours, but through the slab (see "How good a proxy" below: good for centering, fair for corners and edges, poor for surface). Crop out the label.
- **Labels:** exact PSA grade, and the grade mix is ours to choose, including the low grades nobody submits.
- **Cost:** *est.* $10–30 a slab for cheap modern and low-grade vintage, so $15–25k for 1,000, mostly recovered by reselling (about 10–20% lost to fees and spread, so *est.* $2–5k net). Start with 200.
- **Time:** 2–4 weeks to buy and photograph 200.
- **Licensing:** none.
- **Use:** pretraining and centering; it can't replace raw pairs for calibration. It also tells us how much the slab costs in accuracy, which settles whether U3/U4 are worth paying for.

### U3. License TAG's archive (raw out-of-slab images plus per-defect reports)

TAG photographs every card raw during grading and publishes each defect. It's the one existing *raw* image archive with labels at scale (*est.* tens of thousands of Pokémon cards). Labels are TAG grades, not PSA, so it's for pretraining and sub-scores. Send the drafted info@taggrading.com email, now asking first for a licence to a de-identified bulk set and its price, and second whether members may share their own reports. Details in shortlist #3 below.

### U4. License slab photos from auction houses and marketplaces

Auction houses and vaults photograph PSA slabs themselves, so the copyright in *their* photos is theirs to license (unlike PSA's cert scans). Heritage Auctions' terms, as quoted on a collectors forum, allow only non-commercial single-image use without consent, so it would be a licence deal. Candidates: Heritage, Goldin, Fanatics Collect (PWCC), Alt (which also scans *raw* cards at vault intake for $5 each, the one raw source here), Courtyard (off limits for now, see below), and PSA's own eBay storefront.

- **Volume:** tens to hundreds of thousands of PSA-labelled slab images.
- **Quality:** high and uniform, studio-lit.
- **Cost:** unknown; likely a data deal they've not done before.
- **Time:** months.
- **Licensing:** clean if signed; check that the deal covers the card art (The Pokémon Company's) as it appears in the photo.
- **Next step:** one email each to Alt (raw intake scans plus later grades, the best fit) and Heritage, asking whether they license image archives for model training.

### U5. Ask PSA (Collectors) and CGC for their cert images

The biggest PSA-labelled image sets that exist: most PSA certs since about 2023 have front and back scans. PSA owns them (submission terms §23) and CGC's terms (§8) license CGC's images to CGC. Only a licence makes them usable. Ask once through PSA's API and business-development contacts and CGC's: expect no, but a research or data partnership is the only route to PSA-labelled images at millions.

### U6. Pre-grade vendors holding returns

SnapGradeAI reportedly publishes 412 predictions logged before PSA submission with the returned grade (secondhand, from a competitor). CardGrade.io trains on uploads unless users opt out, so it may hold returns. Both are small or unknown; ask whether they'd license photos plus returned grades *and* whether their users' terms allow it. A user-photo set they can't legally pass on is no use.

### Up-front sources that aren't usable

- **eBay raw-to-slab matching:** the largest pool, but scraping breaches the User Agreement §3. The only official sold-data API, Marketplace Insights, is a limited release for approved partners, covers 90 days, and was not shown to return images. Not a route without an eBay data deal.
- **Reddit, Discord, Facebook, forums, YouTube and TikTok:** scraping or training breaches their terms; posters own their photos. Use them only to find people for U1 and the bounty.
- **Public datasets:** PSA-Baseball-Grades (scraped, no licence, 150×200 px, baseball). Roboflow's card-grader sets are small (50–150 images), mostly CC BY 4.0 but without grade labels. Fine for detector experiments under CC BY with credit; nothing for grade calibration.
- **Patent to check:** US 12,106,334 B2 (granted 2024-10-01) claims training a model on pre-graded trading-card images. I couldn't read it (the gazette page 404'd); look up the assignee and claims before training commercially.

## Prospective: data that accumulates from now on

These are slower but cheaper per pair, and they're where the long-run volume comes from. Rawmark already has the pieces: the capture standard, consent records, the contributor org type and cert check, and an offers market where buyer orgs receive raw cards we photographed.

### 1. Our own offers flow: buyers report the grade of cards they bought

**Why first:** every card sold through instant offers was photographed to our standard, front and back, and has a stored distribution. Buyers of raw cards buy them largely to grade. If buyers report the cert number when the slab returns, every graded sale becomes a pair, with no new photos, no new partner, and full control of capture quality and consent.

- **Volume:** equal to sold cards that get graded. *est.* 30–60% of bought cards, at whatever volume the beta reaches.
- **Photo quality:** best of any source (our capture standard, our quality checks).
- **Label quality:** exact grade plus cert, checked by the existing PSA cert lookup.
- **Cost:** near zero. Optionally a small credit per reported cert (e.g. $2 off the next monthly label bill).
- **Time to 1,000 pairs:** depends on offer volume plus PSA turnaround (~50–100 business days for Value Bulk). Slow at beta scale, but it compounds and runs for ever.
- **Bias:** buyers pick cards they expect to grade well, so low grades are under-represented. Tag the source and measure it, as `docs/hld.md` already plans for contributor data.
- **Licensing risk:** low, if the terms say so. The photos are the seller's. Check that `seller-2026-11-01` grants storing the photos *and training and evaluating models on them*, for cards that are sold. If it doesn't, the next version must, and it applies only from acceptance.

**Next steps**
1. Read `seller-2026-11-01` and `buyer-firm-2026-11-01` in `identity.Consents`. Add, in the next versions: seller grants training use of photos of cards they post; buyer agrees to report the grader, cert and grade of any bought card they grade within 12 months (best effort, no penalty), and agrees the grade may be stored and linked to the seller's photos.
2. Add a "Graded it? Add the cert" prompt on the buyer's deal page that reuses the contributor cert check. (A small feature; plan it with the M5 returned-grade work.)
3. Ask the current buyer orgs directly whether they grade what they buy, and how much.

### 2. Our own controlled submissions, stratified on purpose

**Why:** the only source where we choose *which* cards are labelled. The validation target needs low and mid grades too, and nobody else submits a card they think is a 5. It's also the CLAUDE.md "20–30 card controlled PSA set", scaled up.

- **Volume:** whatever we pay for. Plan 100 first (*est.*), then decide on more.
- **Photo quality:** our standard, plus extra shots (close-ups, angled light) we can't ask collectors for.
- **Label quality:** exact PSA grade and cert.
- **Cost:** PSA Value Bulk is $24.99/card (20-card minimum; Collectors Club membership required, $99–249/yr per third-party guides), plus shipping both ways. 100 cards ≈ $2,700–2,900 in fees (*est.*). Cards bought for it (raw modern hits around $25–60, plus played vintage for the low grades) are mostly recovered by reselling slabbed, through our own offers or eBay. 1,000 cards ≈ $25k in fees, which is too much before the model has proven itself.
- **Time to first 100:** about 3–5 months (buy and photograph in 2–3 weeks, then PSA's turnaround).
- **Licensing risk:** none. Our photos, our cards. PSA's submission terms (§23) make PSA the owner of the *cert images*, but not of our own raw photos or of the grade as a fact we record.

**Next steps**
1. Pick a 100-card mix: about a third modern holo or full art (where the surface shift matters), a third mid-2000s, and a third played cards bought to land at grades 3–7. Prefer cards whose slabbed value covers the fee.
2. Photograph each card three times with the capture standard (doubles as the centering scoreboard's same-card test in `docs/centering-training-plan.md`), and run CardGrade on it so its prediction is stored before the grade is known (Ximilar was removed on 2026-11-10).
3. Submit in batches of 20+ so the first grades come back early.
4. Consider one batch through CGC Bulk as well (cheaper per guides, about $15–25), to learn whether CGC grades are worth having for pretraining.

### 3. TAG: a grader that shoots raw, out-of-slab images and reports each defect

**Why:** TAG photographs every card raw during grading and publishes a DIG report with front and back sub-scores and annotated defects. That's the only grader-side source whose images are *raw*. Its ToS treats images of a member's cards as the member's content and bans scraping (memory note of 2026-10-28; the ToS page rate-limited me on 2026-11-02, so re-read it before relying on it).

- **Volume:** two routes. (a) Members share their own DIG reports through our contributor upload (they can, since it's their content): tens to hundreds. (b) A data licence from TAG: potentially tens of thousands.
- **Photo quality:** very high and uniform, but taken on TAG's rig, not a phone. Good for learning defects, while our own photos must still close the phone-to-rig gap.
- **Label quality:** TAG grade (1–10, 1000-point TAG Score on higher tiers) plus defect locations. **Not a PSA grade.** Use it for pretraining and sub-scores, never to calibrate the PSA distribution.
- **Cost:** a licence is unknown. Members' own reports are free to us.
- **Time:** a reply takes weeks; a licence, months.
- **Licensing risk:** moderate. Needs TAG's written agreement for (b), and each member's consent for (a).

**Next steps**
1. Send the drafted email to info@taggrading.com (drafted 2026-10-28; check whether it went). Add two questions: (a) may a member give us their DIG raw images and reports for commercial model training, and do the members' terms already allow it? (b) would TAG license a de-identified set of raw images plus DIG scores, and at what price?
2. Ask whether TAG's regular tiers are open again (paused per a 2026 comparison). If they are, consider routing part of the controlled set through TAG to get TAG's raw images of *our* cards beside our own photos, which measures the phone-to-rig gap directly.

### 4. Prospective contributor bounty: pay people to photograph *before* they ship

**Why:** turns the problem round. Instead of hunting people who kept old photos, recruit collectors who are about to submit, get the photos into the contributor upload before the box is sealed, and pay when the grade comes back.

- **Volume:** *est.* 200–1,000 in the first 6 months if a few group-submission organisers and Discord grading servers promote it.
- **Photo quality:** phone photos through our quality checks. Better than retrospective posts, since we can refuse a photo while the card is still in hand.
- **Label quality:** exact grade plus cert, checked against PSA's lookup.
- **Cost:** a free collection report plus $3–5 per verified pair (*est.*), so $3–5k per 1,000. Cheaper per pair than our own submissions, with less control over the mix.
- **Time to 1,000:** 6–9 months (*est.*), because grading turnaround is inside it.
- **Licensing risk:** low with the contributor terms, which already ask for consent to store and train. Payment makes it a data purchase, so the terms should say so, and US tax reporting may apply to larger payouts.

**Next steps**
1. Re-read `contributor-2026-11-01`. Add the payment and that the grade is what's paid for.
2. Find the people by hand, never by collecting posts: approach 3–5 Pokémon Discord grading servers and Reddit communities through their moderators, and ask for a pinned post or a bounty channel. Their own rules usually forbid unapproved promotion, so ask first.
3. Add a "before you ship" mode to the contributor upload: photos now, cert later, with a reminder when PSA's typical turnaround has passed.

### 5. Group submitters and card shops that already pre-screen

**Why:** they handle hundreds to thousands of raw cards a month on their way to PSA, often inspect each one, and some photograph intake for their own protection. Their customers are the collectors who submit.

- **Volume:** *est.* 500–5,000 a year per active Pokémon-focused group submitter.
- **Photo quality:** varies, from none to good. Our app could become their intake tool, so the photos are ours to standard.
- **Label quality:** exact grade plus cert, since they receive the returns.
- **Cost:** offer free Rawmark pre-screening (our distribution and offer price) for each card they photograph through us, maybe plus a per-pair fee.
- **Time:** one partner signed in 1–2 months, pairs after their next return cycle.
- **Licensing risk:** moderate. The submitter must get *their customers'* consent; the cards aren't theirs. That's simplest if the customer accepts our contributor terms in the app.

**Next steps**
1. Make a list of 5–10 Pokémon group submitters and shops that offer PSA submissions (US first), by hand.
2. Email them a short pitch: "Photograph each raw card through Rawmark at intake; we give you and your customer a free pre-grade and a firm offer; your customer consents in the app; you tell us the cert when it returns."
3. Ask about volume, current intake photos, and whether customers would accept.

## Full idea list

Columns: volume, photo quality, label quality, cost, time to the first 1,000 pairs, licensing risk. "Pairs" means raw photo plus later grade of the same card.

### A. Listing histories (raw sold, then relisted slabbed)

| Idea | Volume | Photos | Labels | Cost | Time to 1,000 | Licensing |
|---|---|---|---|---|---|---|
| eBay raw listing → later slabbed relisting, matched by seller, flaws, centering | Large: probably tens of thousands of matches a year | Seller photos, mixed, often one side or sleeved | Exact grade once matched, but matching is error-prone (same card, different copy) | High engineering | Weeks if scraped | **ToS breach.** The User Agreement §3 bans robots, scrapers and data-mining tools without eBay's approval, and bans harvesting user information. API access is under eBay's API licence, which I couldn't read (403); listing photos are also the sellers' copyright. Not recommended |
| Same via eBay's official APIs | Smaller: Browse API returns current listings only, not sold history | as above | as above | Low | Months | Unknown until the API Licence Agreement is read; likely restricts storage time and reuse. Read it before any design |
| Japanese marketplaces (Mercari, Yahoo Auctions) | Large for JP cards | Mixed | Matching even harder | High | n/a | **ToS breach** (scraping) plus a second legal system. Not recommended |
| Sellers' own records: ask high-volume eBay sellers who flip raw-to-slab for their photo archives | Hundreds per seller | Often decent: flippers photograph to sell | Exact grade if they kept certs | Report or fee | 2–4 months | Low with written consent from the seller, who owns their photos. **Worth trying** as an add-on to #5 |

### B. Grader-side imagery (images of slabbed cards)

| Idea | Volume | Photos | Labels | Cost | Time | Licensing |
|---|---|---|---|---|---|---|
| PSA cert images (front and back scans on most newer certs; forum reports date full coverage to about 2023, not confirmed by PSA) | Millions | High-res, uniform, but through slab plastic | Exact | n/a | n/a | **PSA owns them** (submission terms §23, reviewed 2026-10-28). PSA's public API documents only cert data lookup by number (100 calls/day free) and its End User Agreement needs a sign-in to read. Scraping the cert pages for images is a **ToS breach**. Only usable with a PSA licence: long shot |
| Our own submitters' PSA images of their own cards | Small | as above | Exact | Free | n/a | Still PSA's under §23, even for the submitter. Not usable without PSA's permission |
| CGC cert images | Large | Holdered card | Exact | n/a | n/a | CGC's Services T&C §8 gives *CGC* an irrevocable licence to "use and commercialize" its images. Nothing grants a right to submitters or third parties. Not usable without a CGC licence |
| BGS / Beckett | Unknown | No confirmation that the lookup shows images | Exact plus sub-grades | n/a | n/a | Not checked further: low priority for Pokémon |
| TAG DIG reports | see shortlist #3 | Raw and slab images | TAG grade plus defects | — | — | Member content; licence needed for bulk |
| PCGS TrueView-style paid imaging | n/a | n/a | n/a | n/a | n/a | Coins only; cited on a forum as the model for what PSA doesn't sell |

**How good a proxy is a slab image for a raw photo?** Fair for some things, poor for others:
- **Centering:** good. The card sits flat and square behind clear plastic, so border widths read as well as raw. Slab images could even help the frame-reading model in `docs/centering-training-plan.md`.
- **Corners and edges:** fair. Visible, but the slab's inner well hides the outer edge in places, and whitening shows differently through plastic.
- **Surface:** poor. Glare and reflections from the slab hide scratches and print lines, and the grader's lighting isn't ours. Surface is where modern holos fail.
- **Label leakage:** the slab label shows the grade. Crop it or the model learns to read the label.
- **Domain shift:** a model trained on scanner images of slabs and used on phone photos of raw cards needs our own raw pairs to fine-tune and, above all, to calibrate. Slab images can pretrain; they can't replace the 1,000 raw pairs for validation.
- **Bias:** only cards someone thought worth grading, so the same top-heavy grade mix as every other source.

The proxy question is moot for now, since no slab source is licensable today.

### C. Community sources

| Idea | Volume | Photos | Labels | Cost | Time | Licensing |
|---|---|---|---|---|---|---|
| Reddit "should I grade / guess the grade" posts with results | Hundreds to low thousands a year | Phone, mixed, often front only | Exact when the poster follows up | n/a | n/a | **ToS breach if collected.** Reddit's Data API terms exclude AI training without the rightsholders' permission and need a separate agreement for commercial use. As planned in `docs/hld.md`: read by hand only to find people, then ask them to upload through the contributor flow |
| Discord grading servers | Similar | Mixed | Exact when followed up | n/a | n/a | Discord's terms ban scraping and self-bots. Use only via moderators and a bounty channel (shortlist #4) |
| Facebook groups | Similar | Mixed | Mixed | n/a | n/a | Meta's terms ban automated collection. Hand outreach only |
| Forums (Elite Fourum, Blowout, Collectors Universe) | Tens to hundreds | Mixed | Exact when followed up | n/a | n/a | Same pattern; forum posts are the posters' copyright. Hand outreach only. Elite Fourum is Pokémon-focused and worth a moderator-approved post |
| YouTube / TikTok grading-reveal videos (raw shown before, slab after) | Hundreds of videos, thousands of cards | Video frames, motion blur, sleeves | Exact | n/a | n/a | **ToS breach.** YouTube's terms ban unauthorised downloading and scraping, and its CEO has said AI training on videos breaks them. A creator could license their own raw footage to us directly: try one or two Pokémon grading creators as a contributor deal |

### D. Businesses that already hold the data

| Idea | Volume | Photos | Labels | Cost | Time | Licensing |
|---|---|---|---|---|---|---|
| Group submitters, shops (shortlist #5) | Medium | Varies | Exact | Free service | 2–4 months | Customers' consent |
| SnapGradeAI: reportedly keeps a 412-card log of predictions made before PSA submission and the actual grade (figures only secondhand, from a competitor's site) | Hundreds | Users' phone photos | Exact | Unknown | Weeks | Users' photos aren't SnapGrade's to sell unless its terms say so. Ask what its terms allow before anything else |
| CardGrade.io | Possibly large: it trains on uploads unless users opt out | Users' photos | Only if users report returns | Unknown | Unknown | Ask in the pending terms email whether it holds returned grades and would license pairs. Our own uploads stay opted out |
| PreGradeCards: claims a 10,000-card internal study | Large if true | Unknown | "Internal verification" (method unstated) | Unknown | Unknown | Self-reported, unverified. Low priority |
| Ximilar | n/a: holds no customer data (confirmed 2026-10-30) | — | — | — | — | Its *outputs* on our photos are ours to train on: useful as weak labels or teacher signals for pretraining, never as truth |
| CardGrader.AI | n/a | — | — | — | — | Its terms ban storing or training on results. Out |
| Alt vault: digitizes raw cards at intake ($5/card); raw cards can't be sold there, so many go on to grading | Unknown | Intake scans, uniform | Only if Alt links the later grade | Unknown | Long | Partnership needed; Alt's own terms over its images unknown. Long shot |
| eBay vault | Graded cards only ($250+) | — | — | — | — | No raw images. Out |
| Courtyard, Fanatics Collect (PWCC), Goldin | Unknown: couldn't confirm raw intake imaging | — | — | — | — | Not researched further. The 2026-10-16 price research removed Courtyard data on purpose, so treat it as off limits until Zach decides otherwise |
| Arena Club: grades with computer vision plus graders, takes raw submissions | Likely holds its own raw-to-grade pairs | Its rig | Arena Club grade, not PSA | Unknown | Long | A competitor in effect; unlikely to license |
| PSA / Collectors (licence for cert images or a research partnership) | Huge | Slab scans | Exact | Unknown, likely large | Months to never | Ask once through PSA's API and business contacts; expect no |
| Card Ladder (Collectors) | Sales only | — | — | — | — | No images; already blocked for price data |

### E. Our own generation

| Idea | Volume | Photos | Labels | Cost | Time to 1,000 | Licensing |
|---|---|---|---|---|---|---|
| Buyers report grades of cards bought through offers (shortlist #1) | Grows with the market | Ours | Exact | ~0 | Market-dependent | Terms update |
| Our own stratified PSA submissions (shortlist #2) | Paid for | Ours, best | Exact | ~$27/card fees, card cost mostly recovered | 3–5 months per batch | None |
| Prospective contributor bounty (shortlist #4) | Medium | Phone, checked | Exact | $3–5/pair | 6–9 months | Contributor terms |
| Zach's friend's beta cards (white-stand photos) | A handful | Good | When graded | 0 | n/a | Needs the friend's written agreement first (memory note) |
| Re-photograph cards that collectors already had graded, by cracking slabs | Paid for | Ours | Exact, from the old slab | Card value at risk, and a cracked card may no longer match its grade | — | None, but a cracked card's grade is stale. Not recommended except for cheap slabs as a sanity check |
| Photograph *graded* cards through the slab with our own capture rig | Easy: buy cheap PSA slabs | Ours, through plastic | Exact | Low; slabs resell | Weeks | None: our own photos of cards we own. **Useful slab-proxy pretraining with no licensing problem**, and it measures how much the slab hurts the model |
| Synthetic data: catalog scans with simulated whitening, scratches, dents, off-centre crops; or damage our own bulk cards, as Ximilar says it did | Unlimited | Synthetic, or ours | Defect labels we set, not grades | Low | Weeks | Catalog image rights need checking (the card art is The Pokémon Company's); our own bulk cards are fine. Good for defect detectors and centering, useless for grade calibration |
| Pseudo-labels from Ximilar on unlabelled photos | Only what's already stored | Ours | Teacher outputs, not grades | — | Weeks | Allowed in writing, but Ximilar was removed on 2026-11-10 as too inaccurate: at most the predictions already stored, pretraining only |
| Public datasets (e.g. GitHub PSA-Baseball-Grades: 11,500 images, 150×200 px, scraped, no licence, baseball, includes slabs) | — | Tiny | Noisy | — | — | Unlicensed and scraped. Don't use. No licensable Pokémon raw-to-grade dataset was found on Kaggle, Hugging Face or GitHub |
| Academic contacts: DeepCornerNet (University of Newcastle) and TCG-AI (Research Square preprint, robotic imaging rig) | Their datasets, if shared | Rig | Defect labels | Free if they share | Months | Ask the authors for data and its licence; likely research-only |

## Suggested order

1. **This week (up-front):** email 10–20 flippers and group submitters about their archives (U1); buy the first 200 PSA slabs across grades 1–10 (U2); send the TAG email with the licence question first (U3); one email each to Alt and Heritage (U4).
2. **Also this week (prospective):** read and extend the seller, buyer and contributor terms (#1, #4); add the licensing question to the pending CardGrade.io email.
3. **Next 2–3 weeks:** photograph the slabs; buy and photograph the first 100-card raw stratified set and submit it in batches (#2).
4. **When the first archive or grades arrive:** run the condition validation on whatever pairs exist, by source, and decide whether to buy more.

Don't pursue: scraping eBay, Reddit, Discord, Facebook, YouTube, PSA or CGC cert pages; CardGrader.AI outputs; unlicensed public datasets.

## Sources checked (2026-11-02)

- PSA Public API documentation: https://www.psacard.com/publicapi/documentation (cert lookup only; End User Agreement behind sign-in; Swagger at https://api.psacard.com/publicapi/swagger didn't render)
- PSA security page on imaging: https://www.psacard.com/Security
- PSA image coverage dates (forum, unconfirmed): https://www.elitefourum.com/t/when-was-my-psa-card-graded-a-visual-guide-to-the-timeline-of-psa-certification-numbers-1991-2026/60160 and https://cardcodex.com/blog/old-certs-vs-new-certs/
- PSA February 2026 pricing (Value Bulk $24.99, 20-card minimum): https://www.psacard.com/info/submission-updates
- PSA submission terms §23 (ownership of cert images): reviewed 2026-10-28, memory note `project-pregrade-providers`
- CGC Services Terms and Conditions §8: https://www.cgccards.com/legal/terms-and-conditions/
- CGC grading process (images of holdered cards): https://cgccards.com/card-grading/grading-process
- TAG DIG report: https://help.taggrading.com/en/articles/6747781-what-is-the-tag-dig-report ; TAG services (raw images in every tier): https://help.taggrading.com/en/articles/10692075-what-are-tag-s-available-grading-services ; TAG ToS: https://taggrading.com/pages/terms-of-service (rate-limited on 2026-11-02; reviewed 2026-10-28)
- TAG tiers paused (third-party): https://www.misprint.com/posts/pokemon-card-grading-costs-compared-2026
- eBay User Agreement §3 (scrapers, data mining) and §10: https://www.ebay.com/help/policies/member-behavior-policies/user-agreement?id=4259
- eBay API License Agreement (403, not read): https://developer.ebay.com/join/api-license-agreement
- eBay vault takes graded cards only: https://www.ecommercebytes.com/2023/07/10/ebay-opens-the-vault-to-trading-cards-acquired-off-ebay/
- Reddit Data API terms and AI training: https://www.theregister.com/2024/06/27/reddit_ai_info and https://the-decoder.com/reddit-ends-its-role-as-a-free-ai-training-data-goldmine/ (official page: https://redditinc.com/policies/data-api-terms, not re-read)
- YouTube on scraping and training: https://support.google.com/youtube/answer/15509945?hl=en and https://www.engadget.com/youtube-ceo-warns-openai-that-training-models-on-its-videos-is-against-the-rules-121547513.html
- Alt vault raw intake: https://intercom.help/alt-platform/en/articles/9213530-the-alt-vault and https://intercom.help/alt-platform/en/articles/9213499-supported-assets
- Arena Club grading: https://cardlines.com/arena-club-card-grading-review/
- SnapGradeAI 412-card log (secondhand, competitor's site): https://pregradecards.com/blog/best-ai-card-grading-apps-compared-2026
- PSA-Baseball-Grades dataset: https://github.com/samsilverman/PSA-Baseball-Grades
- DeepCornerNet: https://ogma.newcastle.edu.au/vital/access/manager/Repository/uon:55212 ; TCG-AI preprint: https://www.researchsquare.com/article/rs-8098898/latest.pdf
- Ximilar on building its own dataset: https://www.ximilar.com/ai-card-grading-automate-sports-cards-pre-grading
- Heritage Auctions image terms (quoted secondhand on a forum): https://forums.collectors.com/discussion/comment/12521682/
- eBay Marketplace Insights API (limited release, 90 days of sales): https://developer.ebay.com/api-docs/buy/marketplace-insights/overview.html
- Roboflow card datasets: https://universe.roboflow.com/cardsdata/card-grader-tvoab-yrch1 , https://universe.roboflow.com/jaspers-workspace-hsomq/pokemon-grading ; Roboflow's 150-image Pokémon prototype: https://blog.roboflow.com/using-computer-vision-to-make-card-grading-faster-and-cheaper/
- Patent US 12,106,334 B2 (gazette page 404'd): https://patentsgazette.uspto.gov/week40/OG/html/1527-1/US12106334-20241001.html
- Practitioner notes on training a pre-grader: https://dev.to/alex_newsome_c51e56ce7eb7/i-trained-an-ai-to-pre-grade-trading-cards-heres-what-i-learned-k5i

Not checked: Discord's and Meta's current terms (stated from general knowledge, re-read before any contact plan relies on them), Courtyard, Fanatics Collect and Goldin intake imaging, CardGrade.io's and SnapGradeAI's terms on user uploads.

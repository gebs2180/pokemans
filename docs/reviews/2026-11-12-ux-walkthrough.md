# UX walkthrough, 2026-11-12

A collector's-eye walk through Rawmark, looking for anything that confuses, slows or worries a seller or buyer. The companion design directions page (three ways to make the product feel worth showing a friend) is linked from the PR.

## How this was done

- **Live beta, read-only.** beta.rawmark.app, signed in as Zach (admin, plus the buyer org). Pages walked: home, buyer org page, Cards for sale, the photo viewer, Your deals, How it works, the admin review queue, and a seller's card page opened as admin. Each was checked at desktop width and at phone width (375×812). Nothing was submitted, posted, bought, uploaded or re-asked.
- **Write flows from the code.** Add card, upload, retake, Get an offer, Sell, Buy, label, arrival and paid were reviewed from `web/src/pages/*`, `CardStatusService` and `RetakeReason`, not exercised. The local stack wasn't started.
- **Prod is behind `main`.** Some copy on prod predates the 2026-11-11 commits (for example, How it works on prod still says nobody gets an email when a buyer takes a card). Where prod and `main` differ, findings are judged on `main`, and finding 6 covers the gap itself.
- **No screenshots are committed,** because every page header shows Zach's email and the buyer org page shows a ship-to address. Each finding describes what was on screen instead.

## Findings, by severity

Severities: **blocker** stops the flow; **high** costs trust or a sale; **medium** confuses or slows; **low** is polish.

No blockers were found. Every flow walked can be completed.

### High

#### 1. The "instant offer" takes days to weeks, with no estimate and no ping when it's ready

- **Where:** card page (`/orgs/:org/cards/:card`), Needs-you home, `/admin/review`.
- **Seen:** the review queue reads "4 to review (1 need comps)". Those four cards were added between 10/28 and 11/1, so they've been waiting 11 to 15 days. The card page says "We identify each card by hand from its photos, usually within a couple of days". Once the card is ready, the next step reads "Ready for an instant offer."
- **Why it hurts:**
  - The product's name and headline promise "instant". A collector who photographs a card and comes back two days later to "Waiting for condition review" feels the promise was broken.
  - Nothing tells them when to come back. The footer, card copy and How it works all say to keep checking.
  - The sellers most likely to leave are the ones with good cards, because they have other places to sell.
- **Fix:**
  - Say the real wait up front, on the Add card screen and the card page: "Your price is usually ready within N days. We email you when it is."
  - Send that email (or a web push for an installed PWA) when a card reaches *Ready*. This also closes the still-open half of item 3 in the 2026-11-01 review.
  - Until the wait is short, call it "a firm offer" rather than "an instant offer".
  - Show a step tracker (see finding 7) so the wait reads as progress, not silence.

#### 2. The card page hides the real blocker: "Waiting for condition review" when there are no prices to make an offer

- **Where:** card page. The example is the "Tina" card, a Giratina VSTAR from Crown Zenith GG69.
- **Seen:** the seller sees "Waiting for condition review". The admin queue shows the same card as "Needs comps: no PSA prices yet". `CardStatusService.status` checks for an assessment (ASSESSING) before it checks for prices (PRICING), so a card with neither reports only the first.
- **Why it hurts:**
  - The seller waits once for condition, then learns there's no offer at all and waits again.
  - The news they most need, "this card may never get an offer, you're free to sell it elsewhere", comes last.
- **Fix:**
  - When the PSA table is missing, show the pricing message first, even while the assessment is outstanding.
  - Better still, check for a price table at identification and warn on the card page and the Needs-you list straight away.

#### 3. The photo viewer's "Fit to screen" doesn't fit, and opens on a black screen

- **Where:** Cards for sale (`/orgs/:org/market`), clicking either photo.
- **Seen:**
  - The viewer goes black for several seconds, with no spinner, while the 3024×4032 original loads.
  - In fit mode the image rendered at 1009×1345 inside an 800×600 viewport, so the card was cropped top and bottom.
  - A horizontal scrollbar appeared on desktop.
- **Why it hurts:**
  - The photos *are* the evidence a buyer pays on, and the terms say condition shown in the photos is the buyer's risk.
  - A viewer that crops corners or shows nothing undercuts that whole bargain, right at the moment of buying.
- **Fix:**
  - **Fit.** In `web/src/index.css`, `.viewer-slide img { max-height: 100% }` doesn't resolve, because the grid row has no definite height. Give `.viewer-track` and `.viewer-slide` an explicit height (for example `height: 100%` down from a `100dvh` dialog), or use `max-height: calc(100dvh - <chrome>)`.
  - **Loading.** Show the thumbnail, scaled up and blurred, as a placeholder until the full image loads, plus a small spinner.
  - **Image size.** Serve a display-sized image (about 1600 px) for fit mode. Load the original only for "Actual size".

#### 4. The offer never adds up on screen

- **Where:** "How we priced it" on the market listing and on the seller's offer panel (`OfferPanel.tsx`, `HowPriced`).
- **Seen:**
  - Victini, Black Bolt #171: the price is $636.12.
  - The ladder shows 10 at 60% worth $843.12, 9 at 30% worth $337.25, and 8 at 10% worth $290.78, plus "PSA 7 or lower: unlikely, up to $250.32".
  - The rows do sum to $636.12, but nothing on screen shows that. There's no "chance × worth" column and no total row.
- **Why it hurts:**
  - The product's whole pitch is a price you can check, and right now checking it means doing the sum in your head.
  - The "up to $250.32" on a band with no probability reads like a possible payout.
- **Fix:**
  - Add a "counts for" column (chance × worth) and a bold total row "= $636.12, your offer".
  - Render the unlikely band without a price ("PSA 7 or lower: under 1% chance, not counted"), or say why it shows one.

#### 5. Adding a card is a free-text box, so cards are named "Ev" and "Tina"

- **Where:** binder page (`ContainerPage.tsx`), the "Card, e.g. Charizard 4/102" field.
- **Seen:** cards in the review queue are labelled "Ev" (an Eevee ex from Prismatic Evolutions) and "Tina" (a Giratina VSTAR). There's no search, and nowhere to choose the set, language or edition, so every card waits for identification by hand.
- **Why it hurts:**
  - The first thing a seller does feels like filling in a form, not using a tool built for cards.
  - Vague names make their own binder hard to scan.
  - Every card waits in the hand-identification queue (finding 1), even ones the seller could have named exactly.
- **Fix:**
  - Make it photo-first: "Take the front photo" is the first action, and the name is optional.
  - Add a search-as-you-type picker against the catalog (name, set, number), with the edition and language as chips.
  - When the seller picks a catalog entry, the admin confirms it rather than typing it, which cuts the identification wait.

#### 6. Prod tells sellers no emails are sent, while sales emails are sent and buyers get a daily email

- **Where:** How it works on prod, and the card page's next-step text on prod.
- **Seen:**
  - Prod's How it works says there are no notifications and nobody gets an email when a buyer takes a card. The card page says "We don't send notifications yet".
  - The same buyer session offers "Email me new cards once a day".
  - On `main`, all three places now say "We email you about a sale, but not when a card's condition or offer is ready" (commit 5f123cd and the ones after it).
- **Why it hurts:** about money, people believe the page that frightens them. A seller told "no emails" keeps refreshing, and may not trust the sale email when it does arrive.
- **Fix:** deploy `main` (Zach runs it). No code change is needed.

### Medium

#### 7. Before there's an offer, the "Your offer" panel holds a sales pitch instead of progress

- **Where:** card page while identifying, assessing or pricing.
- **Seen:** a large accent-bordered "Your offer" panel says "One firm price for this card, based on the grade it's likely to be. No listing, no haggling", followed by the waiting message. There's no number and no sense of where the card is up to.
- **Why it hurts:** the most prominent panel on the page is empty and reads like marketing. The seller can't tell whether two days in is normal.
- **Fix:**
  - Replace it with a four-step tracker, Photos ✓ → Identified ✓ → Condition … → Price, showing the expected time for the current step.
  - Show the pitch once, on the Add card screen.

#### 8. Confidence shows as a letter grade, next to a "Most likely PSA 10" chip that reads like a promise

- **Where:** market listing and offer panel.
- **Seen:** "Fairly sure (B), because the photos leave its condition less certain." Above it sits a pink chip, "Most likely PSA 10 (60%)".
- **Why it hurts:**
  - "(B)" is a second grading scale sitting next to PSA's, and collectors already think in grades. It looks like a card grade.
  - "Most likely PSA 10" in a chip reads, at a glance, as "this is a 10". Buyers who skim will over-read it, and sellers will screenshot it.
- **Fix:**
  - Drop the letter from the UI and keep "fairly sure" plus the reason.
  - Reword the chip as "60% chance of a 10".
  - Add one line on what would make it "very sure", for example "A sharper front photo would make us surer".

#### 9. Sellers see photo internals: pixel sizes and attempt counts

- **Where:** card page photo panels.
- **Seen:** "4032×3024" and "3 attempts, all kept; the one shown is used."
- **Why it hurts:** it's noise at best. "All kept" can also worry someone about privacy, since they may have deleted a bad shot on purpose.
- **Fix:** replace them with what the checks found: "Sharp · no glare · whole card in frame". Move attempt history behind the admin view.

#### 10. The close-up is still a grey "Optional", with no reason to take one (still open from 2026-11-01, item 10)

- **Where:** card page, the Close-up panel.
- **Why it hurts:**
  - Buyers see a "Close-up included" chip on listings, so close-ups help a card sell, but the seller is never told that.
  - On holos and full arts the surface is the grade, so close-ups also tighten the condition estimate.
- **Fix:**
  - Label it "Recommended" for holos and full arts, with "Buyers see a 'Close-up included' badge."
  - Prompt for it in the capture flow straight after the back shot.

#### 11. A card photographed in a holder appears to pass the photo checks

- **Where:** market listing (Victini). The front photo appears to show the card inside a red-tinted holder or card saver.
- **Why it hurts:**
  - The first-shot tip says to take the card out of its sleeve, but nothing enforces it.
  - A holder hides edges and adds glare. The buyer then carries the condition risk from a photo the app itself asked not to be taken.
- **Fix:**
  - Add a "card in a sleeve or holder" retake reason. Even a cheap check helps: a border colour band, or edge contrast inside the detected card outline.
  - Until that exists, add a reviewer checkbox.
  - Check one more listing before building this. It's from a single example and may be a coloured background.

#### 12. Centering appears as raw ratios, sometimes for one side only

- **Where:** card page status line.
- **Seen:** "Centering, measured from your photos: back 49/51 left-right, 50/50 top-bottom", with no front figure.
- **Why it hurts:** most collectors don't know PSA's centering table. A back-only figure invites "what about the front?"
- **Fix:** translate it into what it means: "Centering looks good enough for a 10 (back measured; front couldn't be measured — retake for a better estimate)". Keep the raw ratios in a disclosure.

#### 13. Retake messages don't say how to fix the problem (UNREADABLE is still open from 2026-11-01, item 9)

- **Where:** `RetakeReason.java`.
- **Seen:**
  - UNREADABLE: "The file isn't a readable JPEG or PNG image." The usual cause is an iPhone HEIC chosen from Photos.
  - SIZE_MISMATCH: "The upload didn't match the file size that was announced." This is jargon.
- **Fix:**
  - UNREADABLE: "We couldn't open that photo. On iPhone, use Take photo, or set Camera › Formats to Most Compatible."
  - SIZE_MISMATCH: "The upload got cut off. Try again on a steadier connection."

#### 14. The signed-out page and the invite page don't show what the product does

- **Where:** `/` signed out (`App.tsx`) and `/invite/:token`.
- **Seen:** "Instant offers on raw cards." / "A small closed test, run by Zach." and a Log in button. The invite page says "You're invited to sell your cards on Rawmark".
- **Why it hurts:** an invited collector decides whether to bother on this one screen. There's no example offer, no "three steps", and no hint of why this beats a marketplace listing.
- **Fix:**
  - Add a static example offer: an illustrated card with no real artwork, a price, and a short ladder.
  - Add three steps (photograph → we price it → sell to a buyer and ship with a prepaid label) and the one-line promise: "The price is final; nobody re-grades it on arrival."

#### 15. The terms are a monospace wall

- **Where:** `/orgs/:org/terms`, shown as `<pre className="terms">` with an I agree button.
- **Why it hurts:** this is where a seller commits to shipping and a buyer to paying. In monospace with no headings, it reads like a licence nobody reads, which makes later disputes more likely.
- **Fix:**
  - Render the terms as headed sections with a short summary at the top: the price is final, ship within 3 days, pay within 7, three return reasons.
  - Make the agree button sticky at the bottom on mobile.

#### 16. Headings show internal names

- **Where:**
  - The home page's h1 "Your cards" sits over an org list ("zach buy · Buyer", "Admin: orgs and invites"). Still open from 2026-11-01, item 12.
  - The buyer org page's h1 is the org's name ("zach buy").
- **Fix:** title the page by what it's for: "Buying" for a buyer org, "Selling" for a collector org, and "Your accounts" over a list of several.

#### 17. Tracking numbers aren't links (still open from 2026-11-01, item 6)

- **Where:** `SoldPanel.tsx` (seller) and `DealsPage` (buyer).
- **Fix:** link each one to the carrier's tracking page, or to EasyPost's public tracker URL.

#### 18. The waiting message repeats on every waiting state

- **Where:** card page next steps (`CardStatusService.NO_NOTIFICATIONS`) and the Needs-you footer.
- **Seen:** the same "We email you about a sale, but not when…" sentence ends every waiting message, and also appears in the footer.
- **Fix:** say it once, in the step tracker (finding 7). Once the ready email from finding 1 exists, change it to "We'll email you when it's ready".

### Low

#### 19. Listing thumbnails are small at every width

- **Where:** Cards for sale.
- **Seen:** the thumbnails are 94×131 px at both desktop and 375-px widths, and slow to appear.
- **Fix:**
  - On phones, make the front photo full card width, so the photo is the listing.
  - On desktop, use a two-column card grid.
  - Serve real thumbnails (about 400 px).

#### 20. "Ready for an instant offer" before the seller has asked (still open from 2026-11-01, item 12)

- **Fix:** use "Your price is ready. Tap to see it." This also sets up the reveal moment in the design directions.

#### 21. Admins see raw token claims on the home page

- **Where:** home page, the "Account details" disclosure.
- **Fix:** move it to `/admin`. It's harmless but odd on a page meant for sellers.

#### 22. "Binder" setup comes before the first card

- **Where:** a collector org's page with no binder yet. The form's placeholder is "New binder name".
- **Fix:** create a default binder silently, so that the first action is always "Add a card".

## Status of the 2026-11-01 review

| # | Item | Status |
|---|---|---|
| 1 | Payment disclosure | Fixed. The offer panel names who pays and by when, and there's a payment handle plus "I got paid" / "I haven't been paid". |
| 2 | No-comps dead end | Copy fixed, but see finding 2: the message can be hidden behind "condition review". |
| 3 | Deadlines and notifications | Partly fixed: Needs-you home and sale emails exist. Still open: no email when a condition or offer is ready (finding 1). |
| 4 | Arrival form | Fixed. |
| 5 | Contributor flow | Fixed. |
| 6 | Shipping checklist | Fixed. Still open: tracking isn't linked (finding 17). The `.alert a` contrast is fine (`color: inherit`). |
| 7 | Trust and contact | Fixed: How it works, contact address, reread the terms, "Something wrong? Tell us". |
| 8 | Buyer thumbnails | Fixed in PR #83, but the viewer now has its own problems (finding 3), and the thumbnails are still small (finding 19). |
| 9 | Photo failures | Mostly fixed. Still open: UNREADABLE gives no iPhone fix (finding 13). |
| 10 | Close-up value | Still open (finding 10). |
| 11 | Evidence | Fixed in PR #83. Still open: the ladder total (finding 4). |
| 12 | Wording | Partly fixed. Still open: "Ready for an instant offer" (finding 20) and "Your cards" over an org list (finding 16). |
| 13 | Admin | Not re-checked in this pass. |
| 14 | Route focus and errors | Fixed. |

## Suggested order

1. Deploy `main` (finding 6). It's free.
2. Fix the viewer (finding 3) and add the ladder total (finding 4). Both are small, and both are about the evidence buyers pay on.
3. Reorder the card status (finding 2) and add the ready email (finding 1).
4. Build the step tracker and the photo-first add-card flow (findings 7 and 5). This is where the design directions come in.

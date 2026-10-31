# Ready for Discord testers?

Status 2026-10-26 (first drafted 2026-10-19) · for the closed test of Rawmark with collectors and vendors from Zach's local Discord. A box is ticked only once it has been checked, not just built. Each line says what checked it.

**What the test is:** a handful of local collectors post real raw cards and get one firm price. A handful of local vendors (buyer orgs) take some at that price. Labels are real, cards really ship, and payment is between the two parties. Local helps: if something goes wrong, people can meet in person.

**Two asks, two gates.** Contributors (before-and-after photos of cards they had graded) only need the contributor terms and the contributor flow. They can start earlier than the marketplace test.

## Where it stands

Built and tested: the whole offer flow, the E2E audit fixes, the offer maximum, admin undo, the terms, and the clearer upload error. Decided: the brand (Rawmark) and going ahead without employer clearance.

Not yet done, in rough order:
1. **Deploy** the planned hosting ([hosting.md](hosting.md)).
2. **EasyPost test key**, then one real card shipped between two test orgs.
3. **Comps entered** for the cards testers will bring.
4. **Vendor spot check** of about 20 offers.
5. **Zach's own click-through** on the deployed app, including Auth0 sign-up by invite.
6. **iOS camera check.**
7. **Tester guide** reviewed and merged (PR #29).
8. **Who sends notifications by hand**, and how.

## Must have

### It works, end to end, in the running app
- [x] **The whole flow works end to end** (upload, identify, review, offer, sell, buy, label, arrival) as admin, collector and buyer, at phone and laptop widths. *Checked:* the E2E audit clicked it through on 2026-10-21 with a mock login ([e2e-audit-2026-10-21.md](e2e-audit-2026-10-21.md)). Its 31 findings were fixed, decided by Zach, or left with a reason in PR #26 and clicked through again on a separate stack. *Not covered:* a real EasyPost label, printing one, the real Auth0 sign-up and MFA, and a real phone.
- [ ] **Zach clicks through it himself** on a phone and a laptop, against the deployed app with real Auth0 logins. The audit used a mock login, so this is still his to do.
- [x] **Android camera.** *Checked:* the camera check passed in the Android emulator on 2026-10-21 (Take photo and Choose a photo, audit finding 11). A real Android phone is still worth a look. The upload failure seen there now shows a clear message ("The photo didn't upload. Check your connection and try again.", PR #28; unit tests pass, not clicked again in the emulator).
- [ ] **iOS camera.** Same check on an iPhone (Safari, added to the home screen).
- [ ] **EasyPost test key.** Create the account, set the test key, buy a test label for a deal. The audit had no key, so every label failed (finding 2; the copy is fixed and the admin has a Labels to buy page).
- [ ] **One real card ships** between two of Zach's own test orgs on the test key, then on the production key.
- [ ] **Deployed** somewhere the testers can reach, on Zach's personal infrastructure: HTTPS, backups of Postgres and the photo bucket, and an error log someone reads. *Planned, not deployed:* [hosting.md](hosting.md) (PR #23) picks a Hetzner VPS with Compose and Caddy, R2 for photos and backups, and Sentry, at about $5–7 a month. The prod stack, backups and restore test ran locally with MinIO standing in for R2. No accounts exist yet. Still to test on the real host: R2, Auth0 login on the deployed origin, Let's Encrypt (hosting.md, smoke test step 6).
- [ ] **Auth0 sign-up for invited testers** works without Zach's MFA setup getting in the way: invites, roles, and a password reset. The invite page now says "you've been invited" when logged out (audit finding 29), but nobody has signed up through the real tenant yet. Callback and origin changes for the deployed host are in hosting.md, step 4.

### The prices are defensible
- [ ] **Comps entered** for every card that gets an offer: real sold comps that can be shown. Hand-entered is fine for a closed test if they're real sales and their sources are allowed to be used; a licensed source is needed before opening it up. The admin's comp form takes a CSV in the `comps.csv` columns (PR #26). With realistic comps, three fresh sales at each of PSA 8, 9 and 10 now get an offer at confidence C (audit finding 1).
- [ ] **Zach reviews each card's condition distribution by hand.** Ximilar confirmed storing, training and commercial use in writing on 2026-10-30 ([pregrade-providers.md](pregrade-providers.md)), but it's being turned off on prod (2026-10-31), so every distribution is entered by hand. CardGrade stays off until its reply comes. Choosing A for a modern holo without a passing close-up now warns the reviewer (audit finding 25).
- [ ] **Vendor spot check:** about 20 offers compared against what a vendor would pay. If the vendors laugh, tune `m` (buyer margin: 10% at A, 13% at B, 17% at C) before anyone else sees a price.
- [x] **A maximum offer**, on the firm price: $5,000, EasyPost's insurance limit, so every label is insured for the full price. *Checked:* `OffersFlowTest` (PR #24). It was $500 until Zach lifted it on 2026-10-26, because too many cards were above it; set `pokemans.offers.max-price` lower to cap the beta again.

### People know what they're agreeing to
- [x] **Seller, buyer and contributor terms** reviewed. *Checked:* Zach agreed every call on 2026-10-21 (PR #27; recorded in [offers.md](offers.md)). The versions are `seller-2026-10-26`, `buyer-firm-2026-10-21` and `contributor-2026-10-26` (2026-10-26 dropped the $500 line) in `identity.Consents`; any change means a new version string. They are plain-language drafts with no liability cap or "as is" wording until a lawyer drafts it.
- [ ] **A one-page "how this test works"** for testers: firm price, no re-grade, pay within 7 days of arrival, the three return reasons, who pays postage, the $5,000 maximum, no notifications yet, and that it's a test. *Drafted:* `docs/tester-guide.md` in PR #29, waiting for Zach's review.
- [x] **The employer's outside-interest question:** Zach decided on 2026-10-21 to go ahead with the offers beta without clearance. Personal accounts and infrastructure only. Raise it again before the lender and insurer side resumes.
- [x] **The name:** Rawmark, with `rawmark.app` bought (2026-10-25). Renaming the visible text from "pokemans" is being done separately.

### Someone can run it
- [ ] **Notifications, by hand.** The app sends none yet (M6): buyers aren't told when a card is posted, and sellers aren't emailed their label. Decide who sends a Discord DM for each new posting, each taken card and each label, and check the admin pages give them what they need.
- [x] **Undo by appending, not editing:** void an offer, cancel a deal, refund a label, each with who, when and why. *Checked:* `OffersFlowTest` and the web tests (PR #24); the admin's Offers and deals page is at `/admin/deals`. A deal cancelled after the card has shipped still needs sorting out by hand, and a refund's later status isn't fetched.
- [ ] **Postage invoicing done by hand at least once**, from "Postage to bill". Needs a real label first.

## Should have
- [ ] Buyers can record the grade a card came back with once they slab it. This is the calibration data the firm price depends on. Still open in [offers.md](offers.md).
- [x] The concurrency hardening merged (PR #12), in case more than one app instance runs.
- [ ] Noto Sans JP bundled, if testers have Japanese cards. Only Noto Sans (Latin) is bundled; it matters for reports, not offers.
- [ ] The methodology doc updated with the decisions since 2026-10-18. Suggested wording for each is in [methodology-changes-2026-10-26.md](methodology-changes-2026-10-26.md); Zach applies it.
- [ ] Splitting "paid" from "arrived" on a deal (audit finding 5), which fits with the next buyer-terms review.

## Can wait
- Self-serve sign-up (testers come in by invite).
- Automated price refreshes (the nightly job is off until a price source is licensed).
- Payment through us.
- Email notifications (M6).
- The lender and insurer side (paused).

## How many and how long
- **Who:** 5–10 collectors and 3–5 vendors, so offers actually get taken.
- **How long:** 4–6 weeks.
- **What to watch:** the track-record page (acceptance rate, returns by reason, price paid against the offer) and what testers say in a private channel.
- **When to stop and fix:** stop if returns hit about 10%, or if vendors reject most offers.

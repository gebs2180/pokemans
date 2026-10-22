# Ready for Discord testers?

Status 2026-10-19 · for the closed test with collectors and vendors from Zach's local Discord. A box is ticked only once it has been checked, not just built.

**What the test is:** a handful of local collectors post real raw cards and get one firm price. A handful of local vendors (buyer orgs) take some at that price. Labels are real, cards really ship, and payment is between the two parties. Local helps: if something goes wrong, people can meet in person.

**Two asks, two gates.** Contributors (before-and-after photos of cards they had graded) only need the contributor terms and the contributor flow. They can start earlier than the marketplace test.

## Must have

### It works, end to end, in the running app
- [ ] Zach clicks through the whole flow as admin, as a collector and as a buyer, on a phone and a laptop: upload, identify, review, offer, sell, buy, print the label, record the arrival.
- [ ] One real card ships between two of Zach's own test orgs on an EasyPost **test** key, then on the production key.
- [ ] It's hosted somewhere the testers can reach, on Zach's personal infrastructure: HTTPS, backups of Postgres and the photo bucket, and an error log someone reads.
- [ ] Auth0 sign-up for invited testers works without Zach's MFA setup getting in the way: invites, roles, and a password reset.

### The prices are defensible
- [ ] Every card that gets an offer has sold comps that can be shown. Hand-entered is fine for a closed test if the comps are real sales and their sources are allowed to be used. A licensed source is needed before opening it up.
- [ ] Zach reviews each card's condition distribution by hand. The pre-grade APIs stay off until their terms are confirmed in writing.
- [ ] A spot check: 20 or so offers compared against what a vendor would pay. If the vendors laugh, tune `m` (buyer margin) before anyone else sees a price.
- [ ] Limits for the test: a maximum offer (for example $500), so a miscalibrated price can't hurt anyone much, and EasyPost's $5K insurance cap never applies.

### People know what they're agreeing to
- [ ] Buyer terms and contributor terms reviewed (both are drafts in `identity.Consents`; any change means a new version string).
- [ ] A one-page "how this test works" for testers: firm price, no re-grade, pay within 7 days of arrival, the three return reasons, who pays postage, and that it's a test.
- [ ] Zach's outside-interest question answered, or a deliberate decision to go ahead before it is. This is the moment it starts to matter: real people, real money, his name.

### Someone can run it
- [ ] Notifications. Without them, buyers don't know a card was posted and sellers don't know a buyer took it. For a small test this can be by hand (a Discord DM), but someone has to do it.
- [ ] A way for Zach to undo mistakes by appending, not editing: void an offer, cancel a deal, refund a label.
- [ ] Postage invoicing done by hand at least once, from "Postage to bill".

## Should have
- [ ] Buyers can record the grade a card came back with once they slab it. This is the calibration data the firm price depends on.
- [ ] The concurrency hardening merged (PR #12), if more than one app instance will run.
- [ ] Noto Sans JP bundled, if testers have Japanese cards (it matters for reports, not offers).
- [ ] The methodology doc updated with the 2026-10-18 and 2026-10-19 decisions.

## Can wait
- Self-serve sign-up (testers come in by invite).
- Automated price refreshes.
- Payment through us.
- The lender and insurer side (paused).

## How many and how long
- **Who:** 5–10 collectors and 3–5 vendors, so offers actually get taken.
- **How long:** 4–6 weeks.
- **What to watch:** the track-record page (acceptance rate, returns by reason, price paid against the offer) and what testers say in a private channel.
- **When to stop and fix:** stop if returns hit about 10%, or if vendors reject most offers.

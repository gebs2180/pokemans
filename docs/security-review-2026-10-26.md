# Security review, 2026-10-26

A review of what was merged after the full review of 2026-10-17 (`docs/review-2026-10-17.md`, commit `5109e26`), up to `15a85f2`. It was done before real testers and real money arrive in the Discord beta. It covers:

- terms enforcement (PR #27);
- admin undo and the $500 maximum (#24);
- labels and EasyPost (#17, #26);
- the photo freeze (#26);
- the production deploy (#23).

Done overnight while Zach was asleep. Every fix has a test that failed before the fix and passes after. Design and policy questions are written up below rather than changed.

**Result:** `./gradlew check` passes with `POKEMANS_REQUIRE_DOCKER=true`, so the Testcontainers tests ran. Web lint, tests and build pass. A built PWA served by Caddy with the new policy loads with no CSP violations.

## Findings

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | High | Buyers download the seller's original close-up photo, EXIF location included | Fixed, tested |
| 2 | High | Two label requests at once buy two labels on our EasyPost account | Fixed, tested |
| 3 | Medium | No Content-Security-Policy; the SPA keeps refresh tokens in localStorage | Fixed, tested, checked in a browser |
| 4 | Low | Raw provider error text was shown to buyers | Fixed, tested |
| 5 | Medium (policy) | The admin is exempt from terms, so can commit a buyer org to a purchase | For Zach |
| 6 | Medium (to check) | Photo immutability depends on R2 honouring `If-None-Match` on presigned PUTs | For Zach: one curl on the live bucket |
| 7 | Low (policy) | Pre-grading providers get the original photos, EXIF included | For Zach |
| 8 | Low | Caddy and the backup runner run as root in their containers | For Zach |
| 9 | Low | A refund can be asked of EasyPost twice if two admins click at once | Left: harmless |
| 10 | Info | Things checked and found sound | None needed |

### 1. Buyers download the seller's original close-up photo, EXIF location included (High, fixed)

**Where:** `offers/ListingService.java:494` (was `p.cropKey() != null ? p.cropKey() : p.storageKey()`). `intake/PhotoService.java:106` sets no crop for close-ups, because `wholeCardExpected` is false for `DETAIL`.

**Exploit:** a seller takes the close-up with the phone camera (`<input type="file" capture>`, `web/src/pages/CardPage.tsx:113`). The upload is the camera's file, unchanged, and a phone JPEG usually carries GPS coordinates in EXIF. A buyer on the market page opens the close-up. That link is presigned to the upload itself, so the buyer can read the seller's home location from it with any EXIF viewer. Any buyer who accepted the terms can do this without accepting a card. A front or back photo where no card was detected had the same fallback.

The seller terms (clause 7) promise that buyers never see the seller's address except as the parcel's return address. #26 kept the label URL from buyers for the same reason.

**Fix:** when a photo passes without a crop, `PhotoService.confirm` also writes a copy, decoded and re-encoded with OpenCV (`CardDetector.withoutMetadata`). The copy is stored at `<key>.view.jpg`, without metadata and with the EXIF orientation applied. Buyers are linked to the crop or that copy (`CardItems.shownKey`), never to the upload. The crops were already metadata-free, since OpenCV re-encodes them.

**Test:** `OffersFlowTest.buyersNeverGetThePhotosMetadata`. It uploads a JPEG close-up with an EXIF segment holding a GPS string, posts the card, then downloads every photo a buyer is linked to. It checks that each one decodes as an image and contains neither `Exif` nor the string.

**Note:** a close-up confirmed before this change has no `.view.jpg`, so a buyer's link to it would 404. Nothing is deployed yet, so this only affects local data. Re-taking the close-up fixes it.

### 2. Two label requests at once buy two labels on our EasyPost account (High, fixed)

**Where:** `offers/Shipping.java` `buy` checks for an existing label and then calls EasyPost, with no lock. `offers/ListingService.java:242` `buyLabel` had no lock either.

**Exploit:** this needs no bad intent. A buyer whose label didn't come with the accept presses "try again" twice, or presses it while the admin presses "buy label" (`/api/admin/deals/{id}/label`). Both requests see no label and both buy one. The second insert then fails on `shipping_label.listing_id unique` and returns a 500. That second label has been paid for on our account but is recorded nowhere: it isn't billed to the buyer, and it can't be refunded from the admin page.

The same gap let a cancel land between `requireNotCancelled` and the purchase, which bought a label for a cancelled deal.

**Fix:** `buyLabel` is now transactional and takes the card's listing lock first. Posting, voiding and cancelling already use this lock (`pg_advisory_xact_lock(hashtext('listing:' + cardId))`). With it held, `buyLabel` re-checks that the deal isn't cancelled, then checks for a label and buys one. A second request waits and then gets the first one's label.

The lock is held during the EasyPost call, which is bounded by the provider timeout. That is acceptable at beta volume, and `buy` was already holding a transaction across the call.

**Test:** `OffersFlowTest.twoLabelRequestsAtOnceBuyOneLabel`. The fake provider holds the first purchase in flight while a second request arrives. The test expects both requests to return 200 and exactly one label to be bought. Before the fix, two labels were bought and one request returned 500.

**Left:** one case remains. EasyPost may sell the label and then time out before answering, so we record nothing and a retry buys again. `buy` passes the listing id as the shipment `reference`, so such a pair can be matched in the EasyPost dashboard. A real fix would look up the shipment by reference before buying, or use an idempotency key. That is worth doing before the volume grows, but it is not urgent for the beta.

### 3. No Content-Security-Policy; the SPA keeps refresh tokens in localStorage (Medium, fixed)

**Where:** `deploy/Caddyfile:12`. `web/src/AuthProvider.tsx:18-19` keeps refresh tokens in localStorage (`useRefreshTokens`, `cacheLocation="localstorage"`).

**Exploit:** today there is no known script injection. React escapes output and links go through `safeHref`. But with refresh tokens in localStorage, any future injection, or a compromised dependency loading script from elsewhere, can read them. That would give lasting access to the account, the admin's included (MFA only guards the login). Nothing limited where script could load from.

**Fix:** Caddy now sends a policy that allows the following:

- scripts, styles, fonts, the manifest and the service worker from the site only;
- images from the site, `data:`, `blob:` and the photo bucket;
- connections to the site, Auth0 and the photo bucket (presigned uploads);
- no frames, no objects;
- `frame-ancestors 'none'`, `base-uri 'self'`, `form-action 'self'`.

The bucket and Auth0 hosts come from `R2_ENDPOINT` and `AUTH0_DOMAIN` via `PHOTO_ORIGIN` and `AUTH0_DOMAIN` on the Caddy container (`deploy/compose.prod.yml`). `docs/hosting.md` notes that `PHOTO_ORIGIN` must follow `STORAGE_PUBLIC_ENDPOINT` if that is ever set.

**Test:** `DeployConfigTest`. It checks the policy's directives, with no `unsafe-inline` or `unsafe-eval` for scripts, and that the Caddy container gets both hosts. It also checks that HSTS and `nosniff` stay, that actuator paths other than health answer 404, and that the API image runs as a non-root user.

**Checked by hand:** I built the PWA and served it with `caddy:2.10` and this Caddyfile. The page loads and is styled, with no console errors and no CSP violations. An injected inline `<script>` and `<style>` were both blocked (`script-src-elem`, `style-src-elem`).

**Not checked:**

- **Service worker:** it didn't register in the app's built-in browser, but it doesn't register there with no CSP either, so the browser is the cause, not the policy.
- **Full login and photo upload:** these need the real Auth0 tenant and R2 bucket. Do them as part of the `docs/hosting.md` smoke test (step 136), on a phone.

If something there is blocked, the browser console names the directive.

### 4. Raw provider error text was shown to buyers (Low, fixed)

**Where:** `offers/Shipping.java:94` returned `"Couldn't buy the shipping label: " + e.getMessage()`. That text reaches the buyer as `labelPending` on accept and as the 502 detail on retry.

**Exploit:** a Spring `RestClient` error message includes EasyPost's response body. For address errors, that body can quote the from-address, which is the seller's. It also exposes internals such as authentication failures.

**Fix:** the caller gets a plain reason. The provider's message goes to the log at `ERROR`, so Sentry gets it.

**Test:** `OffersFlowTest.aLabelFailureDoesNotUndoTheTakeAndCanBeBoughtAgain` now checks that the buyer sees the plain reason and not the provider's text.

### 5. The admin is exempt from terms, so can commit a buyer org to a purchase (Medium, policy: for Zach)

**Where:** `identity/Consents.java:163-164`. `OrgAccess.require` lets the admin into every org.

**What it allows:**

- **Buyer side:** the admin can accept a market listing as any buyer org, through `/api/orgs/{buyerOrg}/market/{id}/accept`, even if none of that org's members accepted the buyer terms. A label is then bought and billed to that org. The deal records the admin as the buyer's contact, so the seller sees the admin's email.
- **Seller side:** the admin can likewise price and post a collector's card for sale without the collector accepting the seller terms.

The code comment says this is deliberate: acting on someone's behalf happens after they agreed in writing. It doesn't hold an admin to checking that they did, and nothing records that the action was on someone's behalf.

**Options:**

- keep the exemption but require it only where the org has at least one member who accepted the current version;
- or record a `ListingEvent` note "accepted by the admin for X" so the track record and billing show it;
- or drop the exemption for accepts and posts.

I left behaviour unchanged.

### 6. Photo immutability depends on R2 honouring `If-None-Match` on presigned PUTs (Medium until checked: for Zach)

**Where:** `storage/S3BlobStore.java:70`. The presigned upload signs `If-None-Match: *`, so an object can't be overwritten once it exists. This is what makes the photo freeze real: the database freeze stops new photos, but the original object at a key is only protected by this header. MinIO honours it (the integration tests rely on that).

**Risk:** if R2 accepts the PUT without honouring the condition, a seller could PUT a different image to their still-valid presigned URL (10 minutes) after confirming. The bucket has no versioning on R2, so the swap would leave no trace. The crops and the new `.view.jpg` copies are written by the server and aren't reachable through a seller's URL, so buyers wouldn't see the swap. But the original is what pre-graders and the admin's review page use.

**To check (two minutes, on the live bucket):** get an upload slot, PUT a photo, then PUT again to the same URL with the same headers. The second PUT must answer `412 Precondition Failed`. I couldn't test against R2 from here.

### 7. Pre-grading providers get the original photos, EXIF included (Low, policy: for Zach)

**Where:** `condition/PredictionWorker.java:226` sends `photo.storageKey()`, the upload as-is.

The terms say photos may go to pre-grading services, but not that their location data does. Sending the crop or the `.view.jpg` copy instead would leave out the location. It might change what the providers' models see, since the crop is perspective-corrected and lower resolution, so this is your call.

### 8. Caddy and the backup runner run as root in their containers (Low: for Zach)

**Where:** `web/Dockerfile:18` (`caddy:2.10` runs as root by default) and `deploy/backup/Dockerfile:3,9` (`postgres:18` with `ENTRYPOINT []`, so the scheduler runs as root; `restore-test.sh` uses `gosu` to drop to postgres for the server itself). The API image already runs as `app`.

**Risk:** Caddy is the internet-facing process. A bug in it would run as root in the container. That is still not root on the host: there are no `privileged`, host mounts or extra capabilities.

**Fix, untested:** Caddy can run unprivileged with `user: "caddy"` (or a numeric uid) plus `cap_add: [NET_BIND_SERVICE]`. The `caddy-data` and `caddy-config` volumes then need that owner. Nothing is deployed yet, so now is the cheap time to do it. I didn't change it because I couldn't test certificate issuance from here.

### 9. A refund can be asked of EasyPost twice if two admins click at once (Low, left)

**Where:** `offers/OfferAdminService.java:139-147` → `Shipping.refund`, with no lock. Two concurrent refunds both ask EasyPost, and the second insert gets a 409 from `label_refund_answered_once`. Only one admin exists, and a second request for the same shipment should at worst be refused by EasyPost (I didn't confirm how it answers), so I left it.

### 10. Checked and found sound (Info)

**Terms (#27):** each of these requires the caller's acceptance of the current version for that org:

- seller pricing and posting (`OfferController.java` `price`, `post`);
- every buyer route (`requireBuyer`: market, accept, deals, label retry, received, returned);
- photo upload slots and confirms (`PhotoService`, partners excepted);
- contributor outcomes.

A stale version is refused (409), and an older acceptance no longer counts. Deep links reach the same controller methods, so they can't skip the check. Routes without the check change nothing a buyer relies on: withdrawing a listing, recording a ship-from address, and reading one's own cards.

**Admin undo (#24):** `/api/admin/**` needs the admin role, and that role needs the MFA claim (`SecurityConfig`, `RoleClaimConverter`).

- Void, cancel and refund only add rows: `card_offer_void`, `listing_event` VOIDED/CANCELLED and `label_refund`. Each has an update-refusing trigger.
- Double cancel and cancel-after-arrival are refused twice over. The status check comes first, and `listing_event_outcome_once` includes `CANCELLED`, so a cancel racing an arrival loses with a 409.
- Void and accept race on `listing_event_closed_once`.
- Void, cancel and post share the card's listing lock.

**Labels:**

- A buyer can only touch a deal their org accepted: `requireDeal` looks for that org's `ACCEPTED` event.
- Buyers never get the label URL (`forBuyer`) or the seller's ship-from address; `ship-address` is per org.
- The admin's on-behalf routes check that the given buyer org holds the deal.
- Labels are insured for the firm price, which the $500 maximum caps.

**Photo freeze (#26):** both upload slots and confirms are refused while the card's latest listing is open or taken. Both take the card's row lock, which posting also takes. Posting refuses an offer whose priced photos were since retaken. Buyers are shown the photos in the offer's snapshot, which are immutable keys (see 6 for R2). A sold card stays frozen, and a cancelled one is free again by design.

**Deploy (#23):**

- **Secrets:** they come only from `.env` (chmod 600, git-ignored). The prod profile has no defaults, so a missing variable stops startup. The Vite build args are public identifiers. `web/.dockerignore` excludes `.env*`, and the backend has no env files to copy. The final images copy only the jar and `dist`.
- **Actuator:** the app exposes only health and info, with health details off. Caddy forwards only `/actuator/health`; other actuator paths answer 404. Only Caddy publishes ports.
- **Headers:** HSTS (one year; `includeSubDomains` left off on purpose while the site might be on sslip.io), `nosniff`, `X-Frame-Options DENY`, `Referrer-Policy`, and no `Server` header. CSP added (3).
- **R2 CORS:** a single site origin; PUT, GET and HEAD; only `content-type` and `if-none-match` allowed.
- **Forwarded headers:** `forward-headers-strategy: framework` trusts `X-Forwarded-*`. That is safe here because the backend's port isn't published and Caddy doesn't pass through client-sent forwarding headers by default.

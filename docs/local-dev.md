# Local development

## One-time setup

- **JDK 25 and Node 24.** On this machine both are installed from zip into the user profile, because Windows Installer was failing with error 1603. Any install works.
- **Docker Desktop** for Postgres and MinIO: `winget install Docker.DockerDesktop` (needs WSL2 and a reboot).
- **Auth0:** see [auth.md](auth.md). Copy `web/.env.example` to `web/.env.local` and fill it in.

## Run it

```powershell
docker compose up -d                     # Postgres on 5432, MinIO on 9000 (console 9001)
cd backend; $env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="https://<tenant>.us.auth0.com/"; $env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES="https://api.pokemans.app"; .\gradlew.bat :app:bootRun
cd web; npm.cmd run dev                  # http://localhost:5173
```

The backend applies database migrations (Flyway) and creates the versioned `photos` bucket on startup. Every default in `application.yml` points at the compose services. Production uses the `prod` profile, which has no local defaults and reads everything from environment variables (`DB_URL`, `STORAGE_ENDPOINT`, …); see [hosting.md](hosting.md).

To run a second stack beside this one (say, the API on 8092), start Vite with `$env:API_PROXY="http://localhost:8092"`; it proxies `/api` there instead of 8080.

MinIO stopped publishing images to Docker Hub in 2025, so compose and the tests use Chainguard's free build (`cgr.dev/chainguard/minio:latest`, the only free tag).

## Try the M1 flow

1. Log in as your ADMIN user and open **Admin** on the home page.
2. Create a partner org, then click **New invite link** and copy it.
3. Open the link in a private window and sign in as a different user (another Google account, or an email/password user created in Auth0). Click **Accept invite**.
4. As that partner, create a batch, add a card, and take front and back photos. Each photo shows **Passed** or **Retake needed** with reasons.

## Capturing from a phone on the same Wi-Fi

The phone has to reach both the web app and MinIO, because uploads go straight to storage:

1. Find the PC's LAN IP (`ipconfig`, e.g. `192.168.1.20`).
2. Start the web app with `npm.cmd run dev -- --host`.
3. Start the backend with `$env:STORAGE_PUBLIC_ENDPOINT="http://192.168.1.20:9000"` as well, so upload URLs point at the LAN address.
4. In Auth0, add `http://192.168.1.20:5173` to the SPA's callback, logout and web-origin URLs.
5. Open `http://192.168.1.20:5173` on the phone. The camera opens straight from **Take front photo**.

Browsers only allow camera capture and PWA install on `https` or `localhost`. Over plain LAN http, the file picker still offers the camera on most phones, but installing the app needs https (e.g. a tunnel, or the deployed site later).

## Try the M2 flow

1. As a collector or partner, capture a card's front and back. Photos now need the whole card in frame, with a little space around it. Cut-off, too-small and blurry cards get a retake reason.
2. As ADMIN, open **Admin → Review queue** and pick the card.
3. **Identity:** search TCGdex by name, and optionally number, then pick the print, edition, finish, stamp and promo, and confirm. If a card isn't listed, enter it by hand.
4. **Condition:** with no provider enabled, enter the distribution by hand (percentages per grade, plus a reason). Each save is a new version.
5. **Contributors:** create a *Contributor* org and invite someone. They accept the data-use terms, add a card with raw front and back photos, then the grader, cert and grade. Check the cert under **Admin → Graded results to check**.

### Pre-grading providers

Both are off by default. Turn one on only after its terms are confirmed in writing ([pregrade-providers.md](pregrade-providers.md)). Set its key in the environment, never in a file:

```powershell
$env:XIMILAR_ENABLED = "true"; $env:XIMILAR_API_KEY = "<token>"
$env:CARDGRADE_ENABLED = "true"; $env:CARDGRADE_API_KEY = "cgrd_<full-access key>"   # opt the account out of AI training first
```

With a provider on, completing a card's capture queues a request automatically. It is sent as soon as the upload commits, then polled for the answer every 15 s; a system distribution appears once every provider has answered, and the review page updates itself meanwhile. **Ask again** on the review page queues another request.

### Automatic card recognition

When it's on, each card's front crop is sent to Claude to read its name, number (e.g. `11/113`), language and finish. The reading is matched in TCGdex, and the review page shows up to three suggestions with a confidence and a **Confirm** button. Nothing is confirmed without you. Every reading and suggestion is kept, so accuracy can be measured later.

```powershell
$env:CARD_READER_ENABLED = "true"; $env:ANTHROPIC_API_KEY = "<your personal key>"
```

A completed capture is read in the background straight away, usually within 5 s, and the review page shows the suggestions when they arrive. **Read the card again** reads while you wait; if the call fails, the reading stays queued and a background job retries it. Cards completed while recognition was off can be read that way too. A reading costs under a cent with the default model (`CARD_READER_MODEL` to change it). Photos without a card crop (taken before the whole-card check existed) can't be read; retake the front.

### Sold comps and prices

Until a price source is licensed, comps are entered by hand. On a card's review page, once its identity is confirmed:

1. Look up **sold** listings for that exact card, for example eBay's sold filter or 130point. Never use asking prices.
2. Enter each sale under **Sold comps and prices**: the grader and grade (or the raw condition), price, sold date, listing type, marketplace and the listing URL.
3. Click **Refresh prices**. Each grader with sales gets a table of U and grades 1 to 10, showing each grade's price, how it was set and its confidence.

One card needs sales in at least 2 grades, at least 2 apart, for example PSA 10 and 8. Grades between sales are interpolated, and grades above the highest sale carry its price. Grades below the lowest sale step down by the card's own steepest per-grade ratio, until era ratios exist (3 or more same-era cards with sales in both grades). With sales in only one grade, or two adjacent grades, the grader shows "Not priced".

### Tuning photo checks on real photos

To run the photo checks over a folder of your own photos and print the measurements, run the command below. It also writes each card crop next to its photo as `<name>.card.jpg`.

```powershell
cd backend; $env:POKEMANS_REAL_PHOTOS = "C:\Users\you\Pictures\cards"; .\gradlew.bat :app:test --tests "*RealPhotoCalibrationTest" -i
```

## Tests

```powershell
cd backend; .\gradlew.bat test
```

Database and storage tests use Testcontainers and are **skipped without Docker**. CI always runs them.

## Browser tests of the beta flow (e2e)

`e2e/` clicks through the whole instant-offer flow in Chromium, at a phone width (375 px) and a desktop one: the admin makes a collector and a buyer org with invites; the collector accepts the seller terms, creates a collection, adds a card and uploads its front and back; the admin identifies it by hand, imports comps from a CSV, refreshes prices and saves a condition distribution; the collector gets the offer, saves a ship-from address and posts it; the buyer accepts the buyer terms, saves an address, buys it (the label comes from the tests' fake shipping provider) and records the arrival; the seller sees the label and the arrival; the track record counts one more card bought, labelled and paid at the offer. On the phone run it also checks no page scrolls sideways.

One-time setup (Docker Desktop running):

```powershell
cd web; npm.cmd ci
cd ..\e2e; npm.cmd ci; npx.cmd playwright install chromium
```

Run it (about a minute; the first run longer, while Gradle compiles and Docker pulls the images):

```powershell
cd e2e; npm.cmd test
```

That one command starts everything, runs both widths, and stops and removes it all again. It never touches the dev stack: it has its own compose project (`pokemans-e2e`, with data in tmpfs, so every run starts empty) and its own ports, all on 127.0.0.1:

| | Port | |
|---|---|---|
| Postgres | 5446 | database `pokemans_e2e` |
| MinIO | 9016 | |
| API | 8096 | the app plus the tests' `FakeShippingProvider` (`:app:e2eArgs`, the `e2e` source set; never in the boot jar) |
| Web | 5186 | a production build (`vite build`) logging in to the mock issuer, served with `vite preview` |
| Mock OIDC issuer | 8098 | `e2e/stack/mock-oidc.mjs`, in place of Auth0 |

Set `E2E_POSTGRES_PORT`, `E2E_MINIO_PORT`, `E2E_API_PORT`, `E2E_WEB_PORT` or `E2E_OIDC_PORT` to move one. Everything is configured through environment variables and Vite build variables; no production default changes.

- **Login:** the mock issuer's sign-in page takes any email. One starting with `admin` gets the ADMIN role and the MFA claim, as the Auth0 Action sets them; anyone else signs in as a plain user and joins an org through its invite.
- **Off:** TCGdex, card reading, pre-grading, EasyPost, price ingestion and Sentry, whatever your shell has set. So identity and condition are entered by hand, and nothing leaves the machine.
- **Photos:** drawn in the browser (a whole card on a speckled table, like the backend's test images) and set through the "Choose a … photo" file inputs. The camera input isn't used.
- **When it fails:** `npm.cmd run report` opens the HTML report, with a trace and screenshots per failure. The API's log is in `e2e/test-results/stack/backend.log`. `$env:E2E_KEEP_STACK="true"` leaves the containers up afterwards for a look; the next run removes them.
- Selectors are roles and labels, not visible copy, where the page has them, so renaming the app doesn't break the test.

Not covered: real Auth0 (sign-up, MFA), real or test EasyPost labels, the phone camera, TCGdex search and card reading, withdrawing or expiring a listing, returns, admin undo, and contributor and graded cards.

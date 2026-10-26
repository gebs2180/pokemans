# Hosting the beta

Where the closed beta runs: 5–15 Discord testers for 4–6 weeks. It needs HTTPS, Postgres and photo backups, and an error log that someone reads. Everything goes on Zach's personal accounts and a personal card, never employer resources (see CLAUDE.md, Constraints).

**Recommendation: one small Hetzner VPS running Docker Compose (Caddy, backend, Postgres, backup runner), with photos and backups in Cloudflare R2 and errors in Sentry's free plan. About $5–7 a month.** Nothing is deployed and no accounts exist yet. Prices were researched on 2026-10-20 from third-party summaries, so check each provider's pricing page when you sign up.

## Options compared

All four use Cloudflare R2 for photos (10 GB free, then $0.015/GB-month, no egress fees) and Sentry's free Developer plan for errors (one user, about 5k errors a month), so the comparison is about compute and Postgres.

| | **Hetzner CX23 + Compose** (recommended) | Render | Fly.io | Railway |
|---|---|---|---|---|
| Compute | 2 vCPU, 4 GB, 40 GB disk: ~€4.49/mo with IPv4 (one source says €5.99) | Starter web service, 512 MB: $7. Too small for this JVM plus OpenCV; Standard (2 GB) is $25 | shared-cpu-1x 1 GB: ~$6.70 | Hobby $5/mo including $5 of usage; a 1 GB JVM uses more than that |
| Postgres | In Compose on the same disk | Basic-256mb $6, Basic-1gb $19, with daily backups | Managed Postgres Basic $38 + storage; self-run "Fly Postgres" has no managed backups | Usage-billed; volume backups and point-in-time recovery reportedly need Pro ($20) |
| Backups | Our nightly `pg_dump` and photo copy to R2 (`deploy/backup`), plus optional Hetzner snapshots (+20%) | Managed for Postgres; photos need our own copy job | Managed only on the $38 plan | Pro only |
| HTTPS | Caddy gets Let's Encrypt certificates on its own | Built in | Built in | Built in |
| Logs | `docker compose logs`, rotated; errors to Sentry | Dashboard, short retention on low plans | `fly logs`, short retention | Dashboard |
| Beta cost/mo | **~$5–7** | ~$31–44 (Standard web + Basic PG) | ~$45+ managed, ~$10 self-run | ~$20–25 with backups |
| Effort | Highest: you patch one Ubuntu box. About an hour for the first deploy with the steps below | Lowest | Medium (`fly.toml`, machines, volumes) | Low |

Why Hetzner: the backend needs about 1 GB of memory (JVM, OpenCV, PDF rendering), and that is where PaaS pricing jumps. At beta size the VPS costs less than one PaaS Postgres plan, and the one Compose file also runs anywhere with Docker. The cost is that you look after the box: unattended security upgrades, our own backup job and a restore test. For a 6-week beta with a handful of users that is fine. Revisit if the beta becomes a product: Render (or a managed Postgres) is the upgrade path, and the Dockerfiles carry over unchanged.

Not chosen: **Render**, unless your time matters more than ~$30/mo; it is the simplest PaaS here. **Fly.io**: managed Postgres alone costs $38. **Railway**: backups need the Pro plan, and usage billing has no cap by default.

### Storage: why R2

- Cheapest for this size: free up to 10 GB, no egress fees, and phones download photos for review.
- It supports `If-None-Match: *` on PutObject, which our presigned uploads and crop writes rely on (an existing photo is never overwritten). Backblaze B2's S3 API wasn't confirmed to support this.
- **No bucket versioning** (R2 doesn't implement it). The app already never overwrites a photo, and the nightly backup copies the photo bucket into a separate bucket that it never deletes from. So a deleted photo can still be recovered up to the last nightly run. If versioning is a hard requirement, use AWS S3 instead (about $0.023/GB plus egress).
- R2 rejects the AWS SDK's chunked uploads with a 403. The `prod` profile turns them off (`pokemans.storage.chunked-encoding: false`).

## What's in the repo

| Path | What it is |
|---|---|
| `backend/Dockerfile` | Builds the boot jar (tests run in CI, not here) and runs it on a Temurin 25 JRE as a non-root user, with the `prod` profile and a health check |
| `web/Dockerfile` | Builds the PWA with the Auth0 build args and puts it in a Caddy image |
| `deploy/compose.prod.yml` | Caddy (ports 80/443), backend, Postgres 18 and backup, with log rotation. Only Caddy is reachable from outside |
| `deploy/Caddyfile` | HTTPS, `/api/*` and `/actuator/health` proxied to the backend, other actuator paths answer 404, SPA fallback, cache headers, and a Content-Security-Policy naming `R2_ENDPOINT` (photos) and `AUTH0_DOMAIN` (login) |
| `deploy/.env.example` | Every variable the stack reads. Copy it to `deploy/.env` on the server; `.env` is git-ignored |
| `deploy/backup/` | Backup image (Postgres 18 tools + rclone), `schedule.sh` (daily), `backup.sh`, `restore-test.sh` |
| `deploy/r2-cors.json` | CORS rule for the photo bucket (browsers upload straight to it) |
| `application.yml`, `prod` profile | No local defaults: a missing variable stops the app at startup. Sets R2 storage settings, forwarded headers and graceful shutdown |

### Backend environment (prod profile)

Compose sets these from `deploy/.env`; another host would set them directly.

| Variable | Required | Notes |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes | `prod` (the image sets it) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | yes | JDBC URL, e.g. `jdbc:postgresql://postgres:5432/pokemans` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | yes | `https://<tenant>.<region>.auth0.com/`, trailing slash included |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | yes | The API identifier, e.g. `https://api.pokemans.app` |
| `STORAGE_ENDPOINT` | yes | `https://<account-id>.r2.cloudflarestorage.com` |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | yes | R2 token: Object Read & Write on the photo bucket only |
| `STORAGE_PHOTO_BUCKET` | yes | e.g. `pokemans-photos` |
| `STORAGE_PUBLIC_ENDPOINT` | no | Only if browsers must use a different endpoint than the app. Then set the Caddy container's `PHOTO_ORIGIN` to it too, or the Content-Security-Policy blocks photos |
| `STORAGE_REGION` | no | Default `auto` (R2) |
| `SENTRY_DSN`, `SENTRY_ENVIRONMENT` | no | Without a DSN, Sentry is off |
| `CARD_READER_ENABLED` + `ANTHROPIC_API_KEY`, `EASYPOST_ENABLED` + `EASYPOST_API_KEY`, `XIMILAR_*`, `CARDGRADE_*`, `PRICING_INGESTION_ENABLED`, `COLLATERAL_ENABLED` | no | Feature switches, all off by default (`docs/local-dev.md`) |

Web build args (baked into the bundle, not secrets): `VITE_AUTH0_DOMAIN`, `VITE_AUTH0_CLIENT_ID`, `VITE_AUTH0_AUDIENCE`, `VITE_COLLATERAL_ENABLED`.

## First deploy, step by step

You need about an hour, a personal card, and an SSH key (`ssh-keygen -t ed25519` if you don't have one).

### 1. Accounts (personal email and card)

1. **Hetzner Cloud** (console.hetzner.cloud): sign up and verify (they sometimes ask for ID). Create a project `pokemans`, then add your SSH public key under Security → SSH keys.
2. **Cloudflare**: sign up, then open R2 and enable it. It needs a payment method on file even within the free tier.
3. **Sentry** (sentry.io): Developer (free) plan. Create a project of type *Spring Boot* and copy its DSN.
4. **healthchecks.io** (free): create a check named `pokemans backup` with a 1-day period and a 2-hour grace. Copy its ping URL. It emails you when a nightly backup doesn't report in.
5. **UptimeRobot** (free), or similar: you'll add a monitor in step 8.

### 2. Server

1. Hetzner → Servers → Add server: **CX23**, Ubuntu 24.04, location Nuremberg/Falkenstein/Helsinki (the CX line is the cheapest; US locations cost about 20% more), your SSH key, IPv4 on. Optionally tick **Backups** (+20%, weekly whole-disk snapshots on top of our nightly ones).
2. Firewalls → Create: allow inbound TCP 22 (ideally from your IP only), TCP 80, TCP 443 and UDP 443, then apply it to the server.
3. Pick the site address:
   - with a domain: add an A record such as `beta.yourdomain.com` → the server's IPv4;
   - without one: use `<ip-with-dashes>.sslip.io` (e.g. `203-0-113-7.sslip.io`), which resolves to that IP. Caddy can get a certificate for it. Free, but the URL is ugly.
4. SSH in and set up the box:
   ```bash
   ssh root@<server-ip>
   apt update && apt -y upgrade && apt -y install unattended-upgrades git
   dpkg-reconfigure -plow unattended-upgrades       # answer Yes
   curl -fsSL https://get.docker.com | sh            # Docker Engine + compose plugin
   adduser --disabled-password --gecos "" deploy && usermod -aG docker deploy
   mkdir -p /home/deploy/.ssh && cp ~/.ssh/authorized_keys /home/deploy/.ssh/ && chown -R deploy:deploy /home/deploy/.ssh
   sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config && systemctl reload ssh
   ```
   Add 2 GB of swap: the first build compiles Java and the web app on the box.
   ```bash
   fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile && echo '/swapfile none swap sw 0 0' >> /etc/fstab
   ```

### 3. R2 buckets and tokens

1. R2 → Create bucket `pokemans-photos` and bucket `pokemans-backups`. Leave both private, with no public access and no r2.dev URL.
2. `pokemans-photos` → Settings → CORS policy: paste `deploy/r2-cors.json` with `https://<site address>` in `AllowedOrigins`.
3. R2 → Manage API tokens → Create three **Account API tokens**, each limited to specific buckets:
   - `pokemans-app`: Object Read & Write, `pokemans-photos` only;
   - `pokemans-backup-read`: Object Read only, `pokemans-photos` only;
   - `pokemans-backup-write`: Object Read & Write, `pokemans-backups` only.
   Copy each Access Key ID and Secret Access Key (they're shown once), plus the S3 endpoint `https://<account-id>.r2.cloudflarestorage.com`.

The backup can read photos but not delete them, and the app can't touch the backups.

### 4. Auth0 (dev tenant, or a new prod tenant)

The current tenant works for a beta. In the dashboard:

1. **Applications → (the SPA) → Settings**: add `https://<site address>` to **Allowed Callback URLs**, **Allowed Logout URLs** and **Allowed Web Origins**, comma-separated after the existing `http://localhost:5173`. Save.
2. Refresh-token rotation, offline access, the post-login Action and the `ADMIN` role are already set up (`docs/auth.md`). Nothing else changes: the API identifier (audience) is a name, not a URL that has to resolve.
3. Testers sign in with Google or email/password as they do now. Each one then joins through an invite link created under **Admin**.

### 5. Configure and start

```bash
ssh deploy@<server-ip>
git clone https://github.com/gebs2180/pokemans.git && cd pokemans/deploy
cp .env.example .env && chmod 600 .env && nano .env      # fill every value; see the comments
docker compose -f compose.prod.yml up -d --build          # first build takes ~10 minutes on a CX23
docker compose -f compose.prod.yml ps                     # all four should be "running" / backend "healthy"
docker compose -f compose.prod.yml logs -f backend        # Flyway migrations, then "Started ..."
```

Generate `POSTGRES_PASSWORD` with `openssl rand -base64 32 | tr -d '/+='`. Keep a copy of `.env` in your password manager: the backups are no use without the R2 keys.

### 6. Smoke test

1. `https://<site address>/actuator/health` shows `{"status":"UP"}`, with a valid certificate.
2. Log in as your ADMIN user (MFA). The home page shows your roles from `/api/me`.
3. Create a batch, add a card and take a front photo on your phone. **Passed** or **Retake needed** means the browser upload to R2 worked (CORS and `If-None-Match`). On the review page, the card crop appearing means the app's own writes to R2 work (non-chunked upload). A 403 at either point means the CORS rule or the token (step 3).
4. Install the PWA from the phone's browser menu (needs HTTPS, which this is).

### 7. Backups and the restore test

The `backup` service runs every day at `BACKUP_AT` (UTC). Run it once now, then run the restore test straight after:

```bash
docker compose -f compose.prod.yml run --rm backup bash -c "backup.sh && restore-test.sh"
```

`restore-test.sh` downloads the newest dump and restores it into a throwaway Postgres inside the container, never production. It prints row counts per table, restored against live, and checks that no migration failed. It then checks that every photo in the photo bucket is in the backup copy with the same size, and ends with `restore-test: OK` or `FAILED`. **Repeat it weekly during the beta**, and before any risky migration.

### 8. Errors and uptime

- **Sentry** gets every unhandled exception and every `ERROR` log line from the backend. Request bodies, headers, cookies and IPs are not sent (`send-default-pii: false`). In Sentry → Alerts, keep the default "new issue" email alert, and read new issues at least daily during the beta. The free plan has one user and drops events above its monthly quota rather than billing for them.
- **UptimeRobot**: an HTTPS monitor on `https://<site address>/actuator/health` every 5 minutes, with email alerts.
- **healthchecks.io** emails you if a nightly backup fails or doesn't run.
- Raw logs: `docker compose -f compose.prod.yml logs --since 24h backend` (also `caddy`, `backup`), rotated at 5 × 20 MB per service.

## Day to day

**Deploy a new version** (back up first: Flyway migrations only run forward):

```bash
cd ~/pokemans && git pull
cd deploy && docker compose -f compose.prod.yml exec backup backup.sh
docker compose -f compose.prod.yml up -d --build
```

Logins and uploads fail for about 30–60 s while the backend restarts. Tell Discord before deploying.

**Restore after a disaster** (lost server or a bad migration):

1. New server: steps 2 and 5, but run `docker compose -f compose.prod.yml up -d postgres` only.
2. Restore the newest dump into it (`--clean` drops what's there):
   ```bash
   docker compose -f compose.prod.yml run --rm backup bash -c '
     f=$(rclone lsf backups:$BACKUP_BUCKET/postgres | sort | tail -n 1) &&
     rclone copyto backups:$BACKUP_BUCKET/postgres/$f /tmp/restore.dump &&
     pg_restore --clean --if-exists --no-owner -d pokemans /tmp/restore.dump'
   ```
3. Lost photos: copy them back with the app token, which can write to the photo bucket (the backup read token can't):
   ```bash
   docker compose -f compose.prod.yml run --rm \
     -e RCLONE_CONFIG_PHOTOS_ACCESS_KEY_ID=<app key id> -e RCLONE_CONFIG_PHOTOS_SECRET_ACCESS_KEY=<app secret> \
     backup rclone copy backups:pokemans-backups/photos photos:pokemans-photos
   ```
4. `docker compose -f compose.prod.yml up -d`, then the smoke test.

At most a day of data is lost (the last nightly backup), plus whatever Hetzner's weekly snapshot doesn't cover if you use it.

## Monthly cost

| Item | Cost |
|---|---|
| Hetzner CX23 with IPv4 | ~€4.49 (≈ $5.20) excl. VAT, billed hourly; one source says €5.99 |
| Hetzner backups (optional) | +20%, ~€0.90 |
| Cloudflare R2 | $0 up to 10 GB. The beta is likely 5–15 GB counting the backup copy (photos are 3–8 MB; 15 testers × ~50 cards × 2–3 photos), so $0–0.10; requests stay inside the free tier |
| Sentry Developer, healthchecks.io, UptimeRobot | $0 |
| Domain (optional) | ~$10–15 a year, or $0 with sslip.io |
| **Total** | **~$5–7 a month**, about $10 for the whole 6-week beta |

The card reader (Claude API) and EasyPost labels, when switched on, are billed per use on their own accounts and are not counted here.

After the beta, delete the server (billing stops by the hour) and, if wanted, the R2 buckets. Keep a final dump: the photos, predictions and grades are training data (CLAUDE.md, Store everything).

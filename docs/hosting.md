# Hosting the beta

Where the closed beta runs: 5–15 Discord testers for 4–6 weeks. It needs HTTPS, Postgres and photo backups, and an error log that someone reads. Everything goes on Zach's personal accounts and a personal card, never employer resources (see CLAUDE.md, Constraints).

**Recommendation: one small Hetzner VPS running Docker Compose (Caddy, backend, Postgres, backup runner), with photos and backups in Cloudflare R2 and errors in Sentry's free plan. About $5–7 a month.** The beta has been live at https://beta.rawmark.app since 2026-10-28. Prices were researched on 2026-10-20 from third-party summaries.

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
- **No bucket versioning** (R2 doesn't implement it). The app never overwrites a photo, except once to take its location out ([data-retention.md](data-retention.md)), and the nightly backup copies the photo bucket into a separate bucket that it never deletes from. So a deleted photo can still be recovered up to the last nightly run. If versioning is a hard requirement, use AWS S3 instead (about $0.023/GB plus egress).
- R2 rejects the AWS SDK's chunked uploads with a 403. The `prod` profile turns them off (`pokemans.storage.chunked-encoding: false`).

## What's in the repo

| Path | What it is |
|---|---|
| `backend/Dockerfile` | Builds the boot jar (tests run in CI, not here) and runs it on a Temurin 25 JRE as a non-root user, with the `prod` profile and a health check |
| `web/Dockerfile` | Builds the PWA with the Auth0 build args and puts it in a Caddy image |
| `.github/workflows/images.yml` | On every push to main, builds the backend, web and backup images and pushes them to GHCR (private) as `ghcr.io/gebs2180/pokemans-<name>:<commit SHA>` and `:main` |
| `deploy/compose.prod.yml` | Caddy (ports 80/443), backend, Postgres 18 and backup, with log rotation. Only Caddy is reachable from outside. Runs the images tagged `deployed` |
| `deploy/deploy.sh` | Deploys one commit's images (latest main by default), with a backup first and a health wait; also rolls back. See [Day to day](#day-to-day) |
| `deploy/Caddyfile` | HTTPS, `/api/*` and `/actuator/health` proxied to the backend, other actuator paths answer 404, SPA fallback, cache headers, and a Content-Security-Policy naming `R2_ENDPOINT` (photos) and `AUTH0_DOMAIN` (login) |
| `deploy/.env.example` | Every variable the stack reads. Copy it to `deploy/.env` on the server; `.env` is git-ignored |
| `deploy/backup/` | Backup image (Postgres 18 tools, rclone, age), `schedule.sh` (daily), `backup.sh` (encrypts), `backup-check.sh` (on the server), `restore-test.sh` and `restore-photos.sh` (on your machine, with the key) |
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
| `CARD_READER_ENABLED` + `ANTHROPIC_API_KEY`, `EASYPOST_ENABLED` + `EASYPOST_API_KEY`, `CARDGRADE_*`, `CENTERING_ENABLED`, `POKETRACE_ENABLED` + `POKETRACE_API_KEY`, `PRICING_INGESTION_ENABLED` | no | Feature switches, all off by default (`docs/local-dev.md`). `deploy/compose.prod.yml` passes only these to the backend, so a new switch needs a line there too. `COLLATERAL_ENABLED` exists in the app but compose doesn't forward it, so it stays off on prod until it does |

Web build args (baked into the bundle, not secrets): `VITE_AUTH0_DOMAIN`, `VITE_AUTH0_CLIENT_ID`, `VITE_AUTH0_AUDIENCE`. `VITE_COLLATERAL_ENABLED` is always `false`. CI takes the first three from the repository variables `AUTH0_DOMAIN`, `AUTH0_SPA_CLIENT_ID` and `AUTH0_AUDIENCE` (GitHub → Settings → Secrets and variables → Actions → Variables), and the images workflow fails until they are set. They must match the server's `.env`. Only `deploy.sh --build` reads them from `.env`.

## First deploy, step by step

You need about an hour, a personal card, and an SSH key (`ssh-keygen -t ed25519` if you don't have one).

### 1. Accounts (personal email and card)

1. **Hetzner Cloud** (console.hetzner.cloud): sign up and verify (they sometimes ask for ID). Create a project `pokemans`, then add your SSH public key under Security → SSH keys.
2. **Cloudflare**: sign up, then open R2 and enable it. It needs a payment method on file even within the free tier.
3. **Sentry** (sentry.io): Developer (free) plan. Create a project of type *Spring Boot* and copy its DSN.
4. **healthchecks.io** (free): create three checks and copy each ping URL. `pokemans backup` (period 1 day, grace 2 hours) emails you when a nightly backup doesn't report in; `pokemans backup check` (1 day, 2 hours) when the server-side check of it fails; `pokemans restore test` (period 30 days, grace 3 days) when you haven't run the restore test on your machine for a month.
5. **UptimeRobot** (free), or similar: you'll add a monitor in step 8.

### 2. Server

1. Hetzner → Servers → Add server: **CX23**, Ubuntu 24.04, location Nuremberg/Falkenstein/Helsinki (the CX line is the cheapest; US locations cost about 20% more), your SSH key, IPv4 on. Optionally tick **Backups** (+20%, weekly whole-disk snapshots on top of our nightly ones).
2. Firewalls → Create: allow inbound TCP 22 (ideally from your IP only), TCP 80, TCP 443 and UDP 443, then apply it to the server.
3. Pick the site address. The product is called Rawmark, and Zach bought `rawmark.app` on 2026-10-25. The beta site is `https://beta.rawmark.app` (live since 2026-10-28); the bare `rawmark.app` is kept for the product site. For a new server:
   - add an A record for `beta` (for `beta.rawmark.app`) → the server's IPv4, at the registrar or wherever the domain's DNS is hosted;
   - set `SITE_ADDRESS` in `.env` (step 5) to that host name, without `https://`. `.app` domains only work over HTTPS, which Caddy provides.
   - Before DNS is ready you can test on `<ip-with-dashes>.sslip.io` (e.g. `203-0-113-7.sslip.io`), which resolves to that IP and gets a certificate too.
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
   Add 2 GB of swap. Deploys pull built images, but the `--build` fallback compiles Java and the web app on the box.
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

4. `pokemans-backups` → Settings → **Bucket lock rules** → Add rule: name `keep-30-days`, no prefix (the whole bucket), retention **30 days**. No one can delete or overwrite a backup in its first 30 days, whoever holds a token.
5. `pokemans-backups` → Settings → **Object lifecycle rules** → Add rule: name `expire-dumps`, prefix `postgres/`, delete objects **35 days** after upload. Photos in the backup are never expired.
6. A fourth token for the restore test on your own machine: `pokemans-backup-restore`, Object Read only, `pokemans-backups` only. It goes in your local `restore-test.env` (step 7), never on the server.

The backup can read photos but not delete them, and the app can't touch the backups. R2 has no write-only token, so the backup's write token could still delete a backup; the lock rule is what stops it, for 30 days. The backup job itself never deletes: old dumps go by the lifecycle rule. A copy outside this Cloudflare account comes later (review D1).

### Backup encryption key

Every backup (the database dump and each photo) is encrypted with [age](https://age-encryption.org) to a public key before it leaves the server. The server holds only the public half, so it can write backups but not read them; a leaked R2 token or server shows nothing. Make the pair once, on your own machine, not the server:

1. Install age: `winget install FiloSottile.age` (Windows), `brew install age` or `apt install age`.
2. `age-keygen -o rawmark-backup.key`. It prints `Public key: age1...` and writes a file holding a `# public key:` comment and one `AGE-SECRET-KEY-1...` line.
3. **Private half:** paste the whole `AGE-SECRET-KEY-1...` line into a new Bitwarden secure note, `Rawmark backup key`, with the public key in the note too. Then delete the file (`Remove-Item rawmark-backup.key`). Bitwarden is the only copy: **lose it and every backup is unreadable**. A second copy somewhere safe (printed, in a safe) is worth having; or make a second pair, keep its private half apart, and list both public keys.
4. **Public half:** `BACKUP_AGE_RECIPIENT=age1...` in the server's `.env` (and the `.env` copy in Bitwarden). Several keys, space- or comma-separated, each can decrypt.

Without `BACKUP_AGE_RECIPIENT`, or with something that isn't an age public key, `backup.sh` refuses to run and pings `/fail`; there is no unencrypted fallback. Changing the key: put the new public key in `.env` (both, while you switch), recreate the backup service, and keep the old private key until the last backup made with it has expired (35 days for dumps; photos copied before the switch stay encrypted to the old key for good, so keep it).

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
docker login ghcr.io -u gebs2180                          # once: paste the read-only token (below)
bash ~/pokemans/deploy/deploy.sh                          # pulls the latest main's images and starts everything
docker compose -f compose.prod.yml ps                     # all four should be "running" / backend "healthy"
docker compose -f compose.prod.yml logs -f backend        # Flyway migrations, then "Started ..."
```

**The GHCR token.** The images are private, so the server logs in once with a read-only token. On GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)**, create one with only the `read:packages` scope and an expiry you'll notice (GHCR doesn't accept fine-grained tokens). Keep it in the Bitwarden note, never in chat or the repo, and paste it at the `docker login` password prompt. Docker keeps it in `~/.docker/config.json` on the server. When it expires, deploys stop at "No backend image": create a new one and run `docker login ghcr.io -u gebs2180` again.

Generate `POSTGRES_PASSWORD` with `openssl rand -base64 32 | tr -d '/+='`. Keep a copy of `.env` in your password manager: the backups are no use without the R2 keys.

### 6. Smoke test

1. `https://<site address>/actuator/health` shows `{"status":"UP"}`, with a valid certificate.
2. Log in as your ADMIN user (MFA). The home page shows your roles from `/api/me`.
3. Create a batch, add a card and take a front photo on your phone. **Passed** or **Retake needed** means the browser upload to R2 worked (CORS and `If-None-Match`). On the review page, the card crop appearing means the app's own writes to R2 work (non-chunked upload). A 403 at either point means the CORS rule or the token (step 3).
4. Install the PWA from the phone's browser menu (needs HTTPS, which this is).

### 7. Backups and the restore test

The `backup` service runs every day at `BACKUP_AT` (UTC): it dumps the database, encrypts the dump and every new photo (photos under an hour old wait for the next night), and uploads them to `postgres/pokemans-<time>.dump.age` and `photos-age/<key>.age`, with a plain row count per table next to the dump (`pokemans-<time>.counts`). After each backup, `backup-check.sh` checks on the server that the newest dump is there, under 26 hours old and encrypted, and that every photo has an encrypted copy (also off-site when that's on). Run both once now:

```bash
docker compose -f compose.prod.yml run --rm backup bash -c "backup.sh && backup-check.sh"
```

#### Restore test

The server can't decrypt, so the real restore test runs on your machine, with the private key from Bitwarden. It needs Docker Desktop and a `restore-test.env` file kept outside the repo (e.g. `~\rawmark\restore-test.env`; it holds a read-only key, not the age key):

```
BACKUP_BUCKET=pokemans-backups
RCLONE_CONFIG_BACKUPS_TYPE=s3
RCLONE_CONFIG_BACKUPS_PROVIDER=Cloudflare
RCLONE_CONFIG_BACKUPS_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
RCLONE_CONFIG_BACKUPS_ACCESS_KEY_ID=<pokemans-backup-restore key id>
RCLONE_CONFIG_BACKUPS_SECRET_ACCESS_KEY=<pokemans-backup-restore secret>
RCLONE_CONFIG_BACKUPS_NO_CHECK_BUCKET=true
RESTORE_TEST_HEALTHCHECK_URL=<the "pokemans restore test" ping URL>
```

Then in PowerShell, with the `AGE-SECRET-KEY-1...` line copied from Bitwarden (it stays in this window's memory; nothing writes it to disk):

```powershell
docker login ghcr.io -u gebs2180
$env:AGE_IDENTITY = Get-Clipboard; Set-Clipboard -Value ' '
docker run --rm -e AGE_IDENTITY --env-file $HOME\rawmark\restore-test.env ghcr.io/gebs2180/pokemans-backup:main restore-test.sh
Remove-Item Env:AGE_IDENTITY
```

`restore-test.sh` decrypts the newest dump and restores it into a throwaway Postgres inside the container. It prints row counts per table, restored against those counted at backup time, fails if any table has fewer or a key table is empty, and checks that no migration failed. It then decrypts the five newest photos and checks each is a JPEG or PNG, and ends with `restore-test: OK` or `FAILED`. **Run it monthly** (the healthcheck emails you when you haven't), and before any risky migration.

#### Deleting the plain-text backups

Backups made before encryption (`postgres/pokemans-*.dump` and the `photos/` prefix) are not encrypted, and the photos in them still carry GPS (the location strip only changes the photo bucket). Delete them once the first encrypted backup has passed `backup-check.sh` **and** `restore-test.sh` on your machine. The 30-day bucket lock refuses deletes until each object is 30 days old, so do it on or after the 31st day after the switch; anything younger is refused and can be retried later. Old dumps also go by themselves after 35 days (lifecycle rule); the `photos/` prefix never does.

On the server, from `~/pokemans/deploy`, using the backup write token:

```bash
# 1. See what's there (counts only)
docker compose -f compose.prod.yml run --rm backup bash -c 'rclone lsf backups:$BACKUP_BUCKET/postgres --include "pokemans-*.dump" | wc -l; rclone size backups:$BACKUP_BUCKET/photos'
# 2. Dry run: lists what would go, deletes nothing
docker compose -f compose.prod.yml run --rm backup bash -c 'rclone delete backups:$BACKUP_BUCKET/postgres --include "pokemans-*.dump" --dry-run; rclone delete backups:$BACKUP_BUCKET/photos --dry-run'
# 3. Delete. --include "pokemans-*.dump" never matches the encrypted *.dump.age or the *.counts files
docker compose -f compose.prod.yml run --rm backup bash -c 'rclone delete backups:$BACKUP_BUCKET/postgres --include "pokemans-*.dump"; rclone delete backups:$BACKUP_BUCKET/photos --rmdirs'
# 4. Check: both should print 0 / empty, and the next backup-check stops reporting them
docker compose -f compose.prod.yml exec backup backup-check.sh
```

Off-site (when it's on), delete the same two from that provider's console or with the same `rclone delete` on `offsite:postgres` and `offsite:photos`; with B2 Object Lock or Hetzner snapshots they last until their retention runs out. Older Hetzner server snapshots, if you take them, hold the plain database until they expire too.

### 8. Errors and uptime

- **Sentry** gets every unhandled exception and every `ERROR` log line from the backend. Request bodies, headers, cookies and IPs are not sent (`send-default-pii: false`). In Sentry → Alerts, keep the default "new issue" email alert, and read new issues at least daily during the beta. The free plan has one user and drops events above its monthly quota rather than billing for them.
- **UptimeRobot**: an HTTPS monitor on `https://<site address>/actuator/health` every 5 minutes, with email alerts.
- **healthchecks.io** emails you if a nightly backup fails or doesn't run.
- Raw logs: `docker compose -f compose.prod.yml logs --since 24h backend` (also `caddy`, `backup`), rotated at 5 × 20 MB per service.

## Day to day

**Deploy a new version.** Every push to main builds images in CI (GitHub → Actions → **Images**, a few minutes after the merge). `deploy/deploy.sh` then pulls that commit's images, moves the checkout to it, backs up the database, restarts, and waits up to 5 minutes for the backend to report healthy. Nothing is built on the server. Start it with `nohup` from the home directory, so a dropped SSH connection can't stop it:

```bash
nohup bash ~/pokemans/deploy/deploy.sh > ~/deploy.log 2>&1 & tail -f ~/deploy.log
```

Logins and uploads fail for about 30–60 s while the backend restarts. Tell Discord before deploying. If the images aren't built yet, the script stops before changing anything; wait for the Images run and start it again. Each deploy is appended to `~/deploy-history` (time and SHA), and the images of the last three stay on the server.

**Roll back.** Pass the commit to go back to. The end of every deploy prints the previous one, and `~/deploy-history` lists them all:

```bash
nohup bash ~/pokemans/deploy/deploy.sh <sha> > ~/deploy.log 2>&1 & tail -f ~/deploy.log
```

It's the same deploy for an older commit: backup, restart, health wait. The checkout is left on that commit (detached), and the next plain `deploy.sh` goes back to the latest main. **Database migrations don't roll back.** Flyway only runs forward, so going back past a commit that changed `db/migration` leaves the newer schema under the older code. The script refuses that unless you add `--force`. Only force it when the migration only added things the older code ignores (a new table, column with a default, or index). Otherwise fix forward, or restore the pre-deploy backup the deploy took (see the restore steps below), which loses anything written since.

**Fallback: build on the server.** If GHCR or the Images workflow is down, `deploy.sh --build` (optionally with a SHA) builds the three images on the box from that commit, as deploys used to, then deploys them the same way. It takes about 10 minutes on a CX23 and competes with the running backend for memory. The web image then takes its Auth0 settings from `.env`.

**Check prod without changing anything.** Run this from a checkout. It reports the commit, services, health and recent backend errors:

```bash
ssh deploy@beta.rawmark.app 'bash -s' < deploy/prod-check.sh
```

**Restore after a disaster** (lost server or a bad migration):

1. New server: steps 2 and 5 up to `docker login`, then run only `docker compose -f compose.prod.yml up -d postgres`. Until the first deploy there are no `deployed` images, so the commands in steps 2 and 3 use the latest main's (`IMAGE_TAG=main`).
2. Decrypt the newest dump **on your machine** (the key never goes to the server), copy it up, restore it (`--clean` drops what's there), and delete it. In PowerShell, with `restore-test.env` from step 7 and the key copied from Bitwarden. Decrypt to a file, not through a pipe: PowerShell 5.1 pipes corrupt binary data.
   ```powershell
   $env:AGE_IDENTITY = Get-Clipboard; Set-Clipboard -Value ' '
   mkdir $HOME\rawmark\restore -Force
   docker run --rm -e AGE_IDENTITY --env-file $HOME\rawmark\restore-test.env -v "$HOME\rawmark\restore:/out" --user root ghcr.io/gebs2180/pokemans-backup:main decrypt-dump.sh /out
   Remove-Item Env:AGE_IDENTITY
   scp $HOME\rawmark\restore\restore.dump deploy@<server-ip>:/tmp/restore.dump
   Remove-Item $HOME\rawmark\restore\restore.dump
   ```
   Then on the server:
   ```bash
   IMAGE_TAG=main docker compose -f compose.prod.yml run --rm -v /tmp/restore.dump:/tmp/restore.dump:ro backup \
     pg_restore --clean --if-exists --no-owner -d pokemans /tmp/restore.dump
   shred -u /tmp/restore.dump
   ```
3. Lost photos: decrypt them back into the photo bucket, also on your machine. Copy `restore-test.env` to `restore-photos.env` and add the photo bucket with the app token, which can write to it (the backup read tokens can't):
   ```
   PHOTO_BUCKET=pokemans-photos
   RCLONE_CONFIG_PHOTOS_TYPE=s3
   RCLONE_CONFIG_PHOTOS_PROVIDER=Cloudflare
   RCLONE_CONFIG_PHOTOS_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
   RCLONE_CONFIG_PHOTOS_ACCESS_KEY_ID=<app key id>
   RCLONE_CONFIG_PHOTOS_SECRET_ACCESS_KEY=<app secret>
   RCLONE_CONFIG_PHOTOS_NO_CHECK_BUCKET=true
   ```
   ```powershell
   $env:AGE_IDENTITY = Get-Clipboard; Set-Clipboard -Value ' '
   docker run --rm -e AGE_IDENTITY --env-file $HOME\rawmark\restore-photos.env ghcr.io/gebs2180/pokemans-backup:main restore-photos.sh
   Remove-Item Env:AGE_IDENTITY
   ```
   It only uploads photos missing from the bucket and never overwrites one. Delete `restore-photos.env` afterwards (it holds the app's write key).
4. `bash ~/pokemans/deploy/deploy.sh`, then the smoke test.

At most a day of data is lost (the last nightly backup), plus whatever Hetzner's weekly snapshot doesn't cover if you use it.

## Monthly cost

| Item | Cost |
|---|---|
| Hetzner CX23 with IPv4 | ~€4.49 (≈ $5.20) excl. VAT, billed hourly; one source says €5.99 |
| Hetzner backups (optional) | +20%, ~€0.90 |
| Cloudflare R2 | $0 up to 10 GB. The beta is likely 5–15 GB counting the backup copy (photos are 3–8 MB; 15 testers × ~50 cards × 2–3 photos), so $0–0.10; requests stay inside the free tier |
| Sentry Developer, healthchecks.io, UptimeRobot | $0 |
| Domain | `rawmark.app`, already bought (2026-10-25) |
| **Total** | **~$5–7 a month**, about $10 for the whole 6-week beta |

The card reader (Claude API) and EasyPost labels, when switched on, are billed per use on their own accounts and are not counted here.

After the beta, delete the server (billing stops by the hour) and, if wanted, the R2 buckets. Keep a final dump: the photos, predictions and grades are training data (CLAUDE.md, Store everything).

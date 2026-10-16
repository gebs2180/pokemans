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

The backend applies database migrations (Flyway) and creates the versioned `photos` bucket on startup. Every default in `application.yml` points at the compose services. Production overrides them with environment variables (`DB_URL`, `STORAGE_ENDPOINT`, …), with `STORAGE_AUTO_CREATE_BUCKETS=false`.

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

## Tests

```powershell
cd backend; .\gradlew.bat test
```

Database and storage tests use Testcontainers and are **skipped without Docker**. CI always runs them.

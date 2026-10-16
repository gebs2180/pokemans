# Auth setup

The backend is an OAuth2 resource server (HLD §7). The SPA logs in with a managed OIDC provider and calls the API with the access token as a bearer token. The backend only validates tokens and reads ADMIN from them, so any OIDC provider works. The steps below use Auth0's free tier as the example.

## What the backend expects

| Setting | Environment variable | Example |
|---|---|---|
| Issuer | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | `https://pokemans.us.auth0.com/` |
| Audience (API identifier) | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | `https://api.pokemans.app` |
| Roles claim | `POKEMANS_SECURITY_ROLES_CLAIM` (optional) | default `https://pokemans.app/roles` |
| MFA claim | `POKEMANS_SECURITY_MFA_CLAIM` (optional) | default `https://pokemans.app/mfa` |
| Require MFA for ADMIN | `POKEMANS_SECURITY_REQUIRE_ADMIN_MFA` (optional) | default `true` |

- **ADMIN comes from the token.** The roles claim is a list of strings; only `ADMIN` (case-insensitive) is read from it.
- **PARTNER and COLLECTOR come from the database.** An admin creates an org and an invite link; whoever accepts the link while signed in becomes a member of that org with the org's role. Nothing needs to be set per user in Auth0. Users are created in the database on their first API call, keyed by the token's `sub`.
- ADMIN is granted only for an MFA login: the token's `amr` contains `mfa`, or the MFA claim is `true`. Auth0 puts `amr` only in the ID token, so the post-login Action sets the MFA claim on the access token.
- With no issuer set, the app still starts, but every bearer token is rejected (fail closed). `/actuator/health` stays public.

| Path | Who |
|---|---|
| `/actuator/health`, `/actuator/info` | anyone |
| `/api/admin/**` | ADMIN |
| `/api/orgs/{orgId}/**` | members of that org, and ADMIN (checked per request; others get 404) |
| other `/api/**` (`/api/me`, `/api/invites/...`) | any signed-in user |
| everything else | denied (opened for the SPA's static files when the app serves them) |

`GET /api/me` returns the caller's subject, issuer, roles (ADMIN plus membership roles), org memberships, the raw roles claim and whether the token shows MFA. The web app displays these after login, which is the quickest way to check the wiring.

## Auth0 setup (free tier)

1. Create a tenant. Its domain gives the issuer: `https://<tenant>.<region>.auth0.com/`, trailing slash included.
2. **Applications → APIs → Create API.** The identifier is the audience, e.g. `https://api.pokemans.app` (it can't be changed later). Use RS256.
3. **Applications → Create Application → Single Page Application** (React) for the web app. Allowed Callback URLs, Allowed Logout URLs and Allowed Web Origins: `http://localhost:5173`.
4. **Applications → APIs → (your API) → Application Access:** authorize the SPA for user access. Newer tenants block apps from APIs by default ("Client … is not authorized to access resource server").
5. **User Management → Roles:** create `ADMIN` and assign it to admin users. Partners and collectors join through invite links instead.
6. **Actions → Library → Build from scratch** (trigger Login / Post Login), deploy it, then add it to **Actions → Triggers → post-login** and click Apply. It must be the only action that sets these claims:
   ```js
   exports.onExecutePostLogin = async (event, api) => {
     const roles = event.authorization?.roles ?? [];
     api.accessToken.setCustomClaim('https://pokemans.app/roles', roles);

     if (roles.includes('ADMIN')) {
       // Admins must pass MFA on every login; no token is issued unless they do.
       api.multifactor.enable('any', { allowRememberBrowser: false });
       api.accessToken.setCustomClaim('https://pokemans.app/mfa', true);
     } else {
       const mfa = (event.authentication?.methods ?? []).some((m) => m.name === 'mfa');
       api.accessToken.setCustomClaim('https://pokemans.app/mfa', mfa);
     }
   };
   ```
   Why the Action requires MFA itself: post-login Actions run *before* a policy-triggered MFA prompt, so checking `event.authentication.methods` alone misses the MFA done in the same login. Auth0 also puts `amr` only in the ID token, not the access token.
7. **Security → Multi-factor Auth:** enable One-time Password (the Action needs at least one factor turned on).

8. Run the backend with the issuer and audience set:
   ```powershell
   $env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI = "https://<tenant>.<region>.auth0.com/"
   $env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES = "https://api.pokemans.app"
   .\gradlew.bat :app:bootRun
   ```

Changes to Actions can take a minute to take effect. Log out and back in to get a fresh token.

## Web app

The SPA uses `@auth0/auth0-react`. Copy `web/.env.example` to `web/.env.local` and set the tenant domain, the SPA application's client ID and the API audience (none of these are secrets). After login it calls `/api/me` and shows the roles the backend sees.

Logins survive a page reload through rotating refresh tokens cached in `localStorage`. That needs two Auth0 settings:
- **Applications → APIs → (your API) → Settings → Access Settings:** turn on **Allow Offline Access**.
- **Applications → (the SPA) → Settings → Refresh Token Rotation:** turn on **Allow Refresh Token Rotation**.

`localStorage` is readable by any script on the page, so this relies on rotation, short token lifetimes and keeping third-party scripts off the app.

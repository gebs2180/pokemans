# Auth setup

The backend is an OAuth2 resource server (HLD §7). The SPA logs in with a managed OIDC provider and calls the API with the access token as a bearer token. The backend only validates tokens and reads roles from them, so any OIDC provider works. The steps below use Auth0's free tier as the example.

## What the backend expects

| Setting | Environment variable | Example |
|---|---|---|
| Issuer | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | `https://pokemans.us.auth0.com/` |
| Audience (API identifier) | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | `https://api.pokemans.app` |
| Roles claim | `POKEMANS_SECURITY_ROLES_CLAIM` (optional) | default `https://pokemans.app/roles` |
| MFA claim | `POKEMANS_SECURITY_MFA_CLAIM` (optional) | default `https://pokemans.app/mfa` |
| Require MFA for ADMIN | `POKEMANS_SECURITY_REQUIRE_ADMIN_MFA` (optional) | default `true` |

- The roles claim is a list containing any of `ADMIN`, `PARTNER`, `COLLECTOR` (case-insensitive). Unknown values are ignored.
- ADMIN is granted only for an MFA login: the token's `amr` contains `mfa`, or the MFA claim is `true`. Auth0 puts `amr` only in the ID token, so the post-login Action sets the MFA claim on the access token.
- With no issuer set, the app still starts, but every bearer token is rejected (fail closed). `/actuator/health` stays public.

| Path | Who |
|---|---|
| `/actuator/health`, `/actuator/info` | anyone |
| `/api/admin/**` | ADMIN |
| `/api/partner/**` | PARTNER, ADMIN |
| `/api/collector/**` | COLLECTOR, ADMIN |
| other `/api/**` | any signed-in user |
| everything else | denied (opened for the SPA's static files when the app serves them) |

`GET /api/me` returns the caller's subject, issuer and roles, which is the quickest way to check the wiring.

## Auth0 setup (free tier)

1. Create a tenant. Its domain gives the issuer: `https://<tenant>.<region>.auth0.com/`, trailing slash included.
2. **Applications → APIs → Create API.** The identifier is the audience, e.g. `https://api.pokemans.app`. Use RS256.
3. **Applications → Create Application → Single Page Application** for the React app. Allowed callback and logout URLs: `http://localhost:5173`.
4. **User Management → Roles:** create `ADMIN`, `PARTNER`, `COLLECTOR` and assign them to users.
5. **Actions → Triggers → post-login:** add an action that copies roles and MFA status into the access token:
   ```js
   exports.onExecutePostLogin = async (event, api) => {
     api.accessToken.setCustomClaim('https://pokemans.app/roles', event.authorization?.roles ?? []);
     const mfa = (event.authentication?.methods ?? []).some((m) => m.name === 'mfa');
     api.accessToken.setCustomClaim('https://pokemans.app/mfa', mfa);
   };
   ```
6. **Security → Multi-factor Auth:** turn on a factor (e.g. one-time password) and require it for your admin account, so admin logins pass the MFA check.
7. Run the backend with the issuer and audience set:
   ```powershell
   $env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI = "https://<tenant>.<region>.auth0.com/"
   $env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES = "https://api.pokemans.app"
   .\gradlew.bat :app:bootRun
   ```

## Web app

The SPA uses `@auth0/auth0-react`. Copy `web/.env.example` to `web/.env.local` and set the tenant domain, the SPA application's client ID and the API audience (none of these are secrets). After login it calls `/api/me` and shows the roles the backend sees.

Tokens are cached in memory, so a page reload may send you through the Auth0 login again. Turning on refresh token rotation in the SPA application's settings would fix this; that's not done yet.

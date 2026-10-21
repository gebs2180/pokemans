# Production image for the web app (docs/hosting.md): the built PWA served by Caddy, which also
# terminates HTTPS and proxies /api to the backend. Build context: web/. Caddyfile: deploy/Caddyfile, mounted.

FROM node:24-bookworm-slim AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Vite bakes these into the bundle at build time. None are secrets (see .env.example).
ARG VITE_AUTH0_DOMAIN
ARG VITE_AUTH0_CLIENT_ID
ARG VITE_AUTH0_AUDIENCE
ARG VITE_COLLATERAL_ENABLED=false
RUN test -n "$VITE_AUTH0_DOMAIN" && test -n "$VITE_AUTH0_CLIENT_ID" && test -n "$VITE_AUTH0_AUDIENCE" \
    || { echo "Set VITE_AUTH0_DOMAIN, VITE_AUTH0_CLIENT_ID and VITE_AUTH0_AUDIENCE build args" >&2; exit 1; }
RUN npm run build

FROM caddy:2.10
COPY --from=build /src/dist /srv

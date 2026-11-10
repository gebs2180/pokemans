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
# Caddy faces the internet, so it runs as its own user (security review 2026-10-26, #8). It still binds 80 and 443:
# the image gives the binary cap_net_bind_service, and compose.prod.yml keeps only that capability. A fixed uid so
# the deploy's chown of the existing caddy-data and caddy-config volumes can name it (10001:10001).
RUN addgroup -S -g 10001 caddy \
    && adduser -S -D -H -u 10001 -G caddy -s /sbin/nologin caddy \
    && mkdir -p /data/caddy /config/caddy \
    && chown -R caddy:caddy /data /config
COPY --from=build /src/dist /srv
USER caddy

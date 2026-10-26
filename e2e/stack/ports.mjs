// The e2e stack's own ports, clear of the local dev stack (5432, 9000/9001, 8080, 5173) and of the audits' (5442-5443, 9010-9011,
// 8091-8092, 5183-5184, 8099). Override any of them with the matching E2E_* variable.
const port = (name, fallback) => Number(process.env[name] ?? fallback)

export const PORTS = {
  postgres: port('E2E_POSTGRES_PORT', 5446),
  minio: port('E2E_MINIO_PORT', 9016),
  api: port('E2E_API_PORT', 8096),
  web: port('E2E_WEB_PORT', 5186),
  oidc: port('E2E_OIDC_PORT', 8098),
}

// 127.0.0.1 rather than localhost, which Java and Node may resolve to ::1 while a server listens on IPv4. Everything binds
// to loopback only.
export const ISSUER = `http://127.0.0.1:${PORTS.oidc}/`
export const AUDIENCE = 'https://api.e2e.test'
export const CLIENT_ID = 'e2e-spa'
export const WEB_URL = `http://127.0.0.1:${PORTS.web}`
export const API_URL = `http://127.0.0.1:${PORTS.api}`
// docker compose project: its containers and volumes are separate from the dev stack's.
export const COMPOSE_PROJECT = 'pokemans-e2e'

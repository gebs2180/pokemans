// Starts the e2e Postgres and MinIO, then the API on its own port, with the fake shipping provider and every outside
// service off. Playwright runs this as a web server and stops it after the tests; global-teardown removes the containers.
import { execFileSync, execSync, spawn } from 'node:child_process'
import { mkdirSync, openSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { API_URL, AUDIENCE, COMPOSE_PROJECT, ISSUER, PORTS } from './ports.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const backend = join(here, '..', '..', 'backend')
const logs = join(here, '..', 'test-results', 'stack')
mkdirSync(logs, { recursive: true })
const windows = process.platform === 'win32'
const portEnv = { E2E_POSTGRES_PORT: String(PORTS.postgres), E2E_MINIO_PORT: String(PORTS.minio) }

const compose = (...args) =>
  execFileSync('docker', ['compose', '-p', COMPOSE_PROJECT, '-f', join(here, 'docker-compose.yml'), ...args], {
    stdio: 'inherit',
    env: { ...process.env, ...portEnv },
  })
// From empty every time, even after a run that died before its teardown.
compose('down', '-v', '--remove-orphans')
compose('up', '-d', '--wait')

// Absolute: Windows may not look in the current directory (NoDefaultCurrentDirectoryInExePath).
execSync(`"${join(backend, windows ? 'gradlew.bat' : 'gradlew')}" :app:e2eArgs --console=plain -q`, { cwd: backend, stdio: 'inherit' })

const env = {
  ...process.env,
  SERVER_ADDRESS: '127.0.0.1',
  SERVER_PORT: String(PORTS.api),
  DB_URL: `jdbc:postgresql://127.0.0.1:${PORTS.postgres}/pokemans_e2e`,
  DB_USERNAME: 'pokemans_e2e',
  DB_PASSWORD: 'pokemans_e2e',
  STORAGE_ENDPOINT: `http://127.0.0.1:${PORTS.minio}`,
  STORAGE_PUBLIC_ENDPOINT: '',
  STORAGE_ACCESS_KEY: 'pokemans_e2e',
  STORAGE_SECRET_KEY: 'pokemans_e2e',
  STORAGE_PHOTO_BUCKET: 'photos',
  STORAGE_AUTO_CREATE_BUCKETS: 'true',
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: ISSUER,
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES: AUDIENCE,
  // Nothing leaves the machine, whatever the caller's shell has set: identity is entered by hand, condition by hand,
  // and labels come from the fake provider.
  CATALOG_TCGDEX_ENABLED: 'false',
  CARD_READER_ENABLED: 'false',
  XIMILAR_ENABLED: 'false',
  CARDGRADE_ENABLED: 'false',
  EASYPOST_ENABLED: 'false',
  PRICING_INGESTION_ENABLED: 'false',
  COLLATERAL_ENABLED: 'false',
  SENTRY_DSN: '',
  SENTRY_ENVIRONMENT: 'e2e',
  SPRING_PROFILES_ACTIVE: '',
}

const log = openSync(join(logs, 'backend.log'), 'w')
const app = spawn('java', [`@${join(backend, 'app', 'build', 'e2e', 'java.args')}`], { cwd: backend, env, stdio: ['ignore', log, log] })
console.log(`API starting on ${API_URL} (log: test-results/stack/backend.log)`)
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => app.kill())
app.on('exit', (code) => process.exit(code ?? 0))

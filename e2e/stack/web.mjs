// Builds the web app as production would, but logging in to the mock issuer, and serves it on its own port with /api
// proxied to the e2e API. The build goes to e2e/.web-dist, so web/dist and web/.env.local are left alone (variables set
// here win over .env files).
import { execFileSync, spawn } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { API_URL, AUDIENCE, CLIENT_ID, ISSUER, PORTS } from './ports.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const web = join(here, '..', '..', 'web')
const out = join(here, '..', '.web-dist')
const vite = join(web, 'node_modules', 'vite', 'bin', 'vite.js')

const env = {
  ...process.env,
  VITE_AUTH0_DOMAIN: ISSUER.replace(/\/$/, ''), // with http://, auth0-spa-js uses it as is
  VITE_AUTH0_CLIENT_ID: CLIENT_ID,
  VITE_AUTH0_AUDIENCE: AUDIENCE,
  VITE_COLLATERAL_ENABLED: 'false',
  API_PROXY: API_URL,
}

execFileSync(process.execPath, [vite, 'build', '--outDir', out, '--emptyOutDir', '--logLevel', 'warn'], { cwd: web, env, stdio: 'inherit' })
const server = spawn(process.execPath, [vite, 'preview', '--outDir', out, '--host', '127.0.0.1', '--port', String(PORTS.web), '--strictPort'], {
  cwd: web,
  env,
  stdio: 'inherit',
})
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.kill())
server.on('exit', (code) => process.exit(code ?? 0))

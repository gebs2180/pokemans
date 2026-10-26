// Removes the e2e containers and their data after the run. Playwright stops the web servers itself.
import { execFileSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { COMPOSE_PROJECT } from './ports.mjs'

export default function teardown() {
  if (process.env.E2E_KEEP_STACK === 'true') return
  const here = dirname(fileURLToPath(import.meta.url))
  execFileSync('docker', ['compose', '-p', COMPOSE_PROJECT, '-f', join(here, 'docker-compose.yml'), 'down', '-v', '--remove-orphans'], {
    stdio: 'inherit',
  })
}

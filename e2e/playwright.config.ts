import { defineConfig } from '@playwright/test'
import { API_URL, ISSUER, WEB_URL } from './stack/ports.mjs'

// `npm test` starts the whole stack (mock issuer, Postgres and MinIO, API, built web app) on its own ports, runs the beta
// flow at a phone and a desktop width, then stops everything. See docs/local-dev.md.
export default defineConfig({
  testDir: 'tests',
  // One stack and one database: the projects run one after another, each with its own orgs and users.
  workers: 1,
  fullyParallel: false,
  timeout: 4 * 60_000,
  expect: { timeout: 15_000 },
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never' }]],
  globalTeardown: './stack/global-teardown.mjs',
  use: {
    baseURL: WEB_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // The built app registers a PWA service worker; it would cache the shell between runs.
    serviceWorkers: 'block',
  },
  projects: [
    {
      name: 'phone',
      use: { browserName: 'chromium', viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 },
    },
    { name: 'desktop', use: { browserName: 'chromium', viewport: { width: 1280, height: 800 } } },
  ],
  webServer: [
    { command: 'node stack/mock-oidc.mjs', url: `${ISSUER}.well-known/openid-configuration`, reuseExistingServer: false, timeout: 15_000 },
    // First run compiles the backend and pulls the images: allow for it.
    { command: 'node stack/backend.mjs', url: `${API_URL}/actuator/health`, reuseExistingServer: false, timeout: 6 * 60_000, stdout: 'pipe' },
    { command: 'node stack/web.mjs', url: WEB_URL, reuseExistingServer: false, timeout: 3 * 60_000 },
  ],
})

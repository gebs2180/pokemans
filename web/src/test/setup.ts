import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'
import { resetAuth0 } from './auth0'

// Pages read the offer terms' numbers from the policy; tests get the beta's (policy.test.tsx covers the loading).
vi.mock('../policy', async (importActual) => {
  const actual = await importActual<typeof import('../policy')>()
  const { testPolicy } = await import('./policy')
  return { ...actual, usePolicy: () => testPolicy }
})

vi.mock('@auth0/auth0-react', async () => {
  const { auth0 } = await import('./auth0')
  return { useAuth0: () => auth0 }
})

beforeEach(() => {
  resetAuth0()
  sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  vi.useRealTimers()
})

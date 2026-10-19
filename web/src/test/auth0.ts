import { vi } from 'vitest'

/** What the mocked useAuth0() returns; reset before every test. */
export const auth0 = {
  getAccessTokenSilently: vi.fn<() => Promise<string>>(),
  loginWithRedirect: vi.fn<(options?: unknown) => Promise<void>>(),
}

export function resetAuth0() {
  auth0.getAccessTokenSilently.mockReset().mockResolvedValue('test-token')
  auth0.loginWithRedirect.mockReset().mockResolvedValue()
}

/** A fetch Response for stubbing; no body means an empty one. */
export function respond(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
  })
}

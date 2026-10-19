import { renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, apiPath, useApi } from './api'
import { auth0, respond } from './test/auth0'

function api() {
  return renderHook(() => useApi()).result.current
}

describe('useApi', () => {
  it('sends the access token and JSON body', async () => {
    const fetch = vi.fn(async () => respond(200, { ok: true }))
    vi.stubGlobal('fetch', fetch)

    await expect(api()('POST', '/api/things', { a: 1 })).resolves.toEqual({ ok: true })

    expect(fetch).toHaveBeenCalledWith('/api/things', {
      method: 'POST',
      headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' },
      body: '{"a":1}',
    })
  })

  it('turns a problem-detail body into an ApiError', async () => {
    vi.stubGlobal('fetch', async () => respond(409, { title: 'Conflict', detail: 'Already identified' }))

    const error = await api()('GET', '/api/things').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, message: 'Already identified' })
  })

  it('returns undefined for an empty 2xx body', async () => {
    vi.stubGlobal('fetch', async () => respond(201))
    await expect(api()('POST', '/api/things')).resolves.toBeUndefined()
  })

  it('sends the user back to login on a 401, returning to this page', async () => {
    window.history.pushState({}, '', '/orgs/o1?tab=cards')
    vi.stubGlobal('fetch', async () => respond(401))

    await expect(api()('GET', '/api/me')).rejects.toMatchObject({ status: 401 })

    expect(auth0.loginWithRedirect).toHaveBeenCalledWith({ appState: { returnTo: '/orgs/o1?tab=cards' } })
  })

  it('does not loop through login when the API keeps rejecting the new token', async () => {
    vi.stubGlobal('fetch', async () => respond(401))

    await expect(api()('GET', '/api/me')).rejects.toMatchObject({ status: 401 })
    await expect(api()('GET', '/api/me')).rejects.toThrow('issuer and audience')

    expect(auth0.loginWithRedirect).toHaveBeenCalledTimes(1)
  })

  it('sends the user back to login when the refresh token is gone', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    auth0.getAccessTokenSilently.mockRejectedValue(Object.assign(new Error('Missing Refresh Token'), { error: 'missing_refresh_token' }))

    await expect(api()('GET', '/api/me')).rejects.toBeInstanceOf(ApiError)

    expect(auth0.loginWithRedirect).toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('passes other token errors through without a login redirect', async () => {
    auth0.getAccessTokenSilently.mockRejectedValue(new Error('network down'))
    await expect(api()('GET', '/api/me')).rejects.toThrow('network down')
    expect(auth0.loginWithRedirect).not.toHaveBeenCalled()
  })
})

describe('apiPath', () => {
  it('encodes every interpolated segment', () => {
    expect(apiPath`/api/invites/${'a/../b?c'}/accept`).toBe('/api/invites/a%2F..%2Fb%3Fc/accept')
  })
})

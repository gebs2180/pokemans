import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { OpenInvite, OrgMember } from '../api'
import { respond } from '../test/auth0'
import { OrgPeople } from './OrgPeople'

const member: OrgMember = { id: 'm1', email: 'sam@example.com', role: 'COLLECTOR', joinedAt: '2026-10-21T10:00:00Z' }
const invite: OpenInvite = { id: 'i1', createdAt: '2026-11-01T10:00:00Z', expiresAt: '2026-11-08T10:00:00Z' }

function server(posts: { url: string; body?: unknown }[], status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push({ url, body: init.body ? JSON.parse(String(init.body)) : undefined })
        return Promise.resolve(status === 200 ? new Response(null, { status: 200 }) : respond(status, { detail: 'This invite was already used, revoked or has expired' }))
      }
      if (url.endsWith('/members')) return Promise.resolve(respond(200, posts.length ? [] : [member]))
      return Promise.resolve(respond(200, posts.length ? [] : [invite]))
    }),
  )
}

describe('OrgPeople', () => {
  it('removes a member, with the reason', async () => {
    const user = userEvent.setup()
    const posts: { url: string; body?: unknown }[] = []
    server(posts)
    render(<OrgPeople orgId="o1" invitesMade={0} />)

    await user.click(await screen.findByRole('button', { name: 'Remove' }))
    expect(screen.getByRole('button', { name: 'Remove from this org' })).toBeDisabled()
    await user.type(screen.getByLabelText("Why they're being removed"), 'broke the terms')
    await user.click(screen.getByRole('button', { name: 'Remove from this org' }))

    await waitFor(() => expect(posts).toEqual([{ url: '/api/admin/orgs/o1/members/m1/end', body: { reason: 'broke the terms' } }]))
    expect(await screen.findByText('No members.')).toBeInTheDocument()
  })

  it('revokes a link, and says why when it cannot', async () => {
    const user = userEvent.setup()
    const posts: { url: string; body?: unknown }[] = []
    server(posts, 409)
    render(<OrgPeople orgId="o1" invitesMade={0} />)

    await user.click(await screen.findByRole('button', { name: 'Revoke' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('already used')
    expect(posts).toEqual([{ url: '/api/admin/orgs/o1/invites/i1/revoke' }])
  })
})

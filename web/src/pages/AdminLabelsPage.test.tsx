import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { AwaitingLabel } from '../api'
import { respond } from '../test/auth0'
import { AdminLabelsPage } from './AdminLabelsPage'

const deal: AwaitingLabel = {
  listingId: 'l1',
  buyerOrgId: 'b1',
  buyer: { orgName: 'Card Shop', email: 'shop@example.com' },
  seller: { orgName: 'Zach', email: 'zach@example.com' },
  card: { name: 'Pikachu VMAX', setName: 'Vivid Voltage', number: '188', language: 'en', edition: 'UNLIMITED', variant: 'HOLO', label: 'x', distribution: {}, gradedAs: null },
  offer: {
    id: 'o1', price: 100.54, fmv: 160, confidence: 'B', buyerMargin: 0.13, graded: false, subjectToAuthentication: false, ladder: [],
    createdAt: '2026-10-21T10:00:00Z', expiresAt: '2026-10-28T10:00:00Z', expired: false, voided: null,
  },
  photos: [],
  acceptedAt: '2026-10-21T11:00:00Z',
}

describe('AdminLabelsPage', () => {
  it('buys a failed label again, and says why when it fails again', async () => {
    const user = userEvent.setup()
    const posts: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (init?.method === 'POST') {
          posts.push(url)
          return Promise.resolve(respond(503, { detail: 'No shipping account is set up yet.' }))
        }
        return Promise.resolve(respond(200, [deal]))
      }),
    )
    render(
      <MemoryRouter>
        <AdminLabelsPage />
      </MemoryRouter>,
    )

    expect(await screen.findByText(/\$100.54 · bought .* by Card Shop \(shop@example.com\) from Zach \(zach@example.com\)/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Buy the label again' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No shipping account is set up yet.')
    expect(posts).toEqual(['/api/admin/deals/l1/label?buyerOrgId=b1'])
  })

  it('records an arrival by hand for a card that shipped another way', async () => {
    const user = userEvent.setup()
    const posts: { url: string; body: unknown }[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (init?.method === 'POST') {
          posts.push({ url, body: JSON.parse(String(init.body)) })
          return Promise.resolve(new Response(null, { status: 201 }))
        }
        return Promise.resolve(respond(200, posts.length ? [] : [deal]))
      }),
    )
    render(
      <MemoryRouter>
        <AdminLabelsPage />
      </MemoryRouter>,
    )

    await user.click(await screen.findByRole('button', { name: 'It shipped another way: record the arrival' }))
    await user.type(screen.getByLabelText('Note'), "seller's own stamps")
    await user.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() =>
      expect(posts).toEqual([{ url: '/api/admin/deals/l1/received?buyerOrgId=b1', body: { pricePaid: 100.54, note: "seller's own stamps" } }]),
    )
    expect(await screen.findByText('No deals are waiting for a label.')).toBeInTheDocument()
  })
})

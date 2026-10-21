import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { AdminListing, AdminOffer } from '../api'
import { respond } from '../test/auth0'
import { AdminOffersPage } from './AdminOffersPage'

const card = { name: 'Umbreon', setName: 'Evolving Skies', number: '215', language: 'en', edition: 'UNLIMITED', variant: 'HOLO', label: 'x', distribution: {}, gradedAs: null }
const label = { carrier: 'USPS', service: 'GroundAdvantage', trackingCode: '9400111', labelUrl: 'https://labels.example/l.pdf', insuredValue: 300, boughtAt: '2026-10-20T10:00:00Z' }

const listing = (over: Partial<AdminListing> = {}): AdminListing => ({
  id: 'l1',
  status: 'OPEN',
  listedAt: '2026-10-20T10:00:00Z',
  closedAt: null,
  buyerOrgId: null,
  buyer: null,
  label: null,
  labelRefund: null,
  outcome: null,
  undone: null,
  ...over,
})

const offer = (id: string, name: string, over: Partial<AdminOffer> = {}): AdminOffer => ({
  offerId: id,
  orgId: 'org1',
  sellerName: 'Sam',
  cardItemId: 'c-' + id,
  card: { ...card, name },
  price: 300,
  fmv: 450,
  confidence: 'B',
  createdAt: '2026-10-20T10:00:00Z',
  expiresAt: '2099-10-27T10:00:00Z',
  expired: false,
  voided: null,
  listing: null,
  ...over,
})

function stub(offers: AdminOffer[]) {
  const posts: { url: string; body: unknown }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push({ url, body: JSON.parse(String(init.body)) })
        return Promise.resolve(respond(200, offers[0]))
      }
      return Promise.resolve(respond(200, offers))
    }),
  )
  return posts
}

function renderPage() {
  render(
    <MemoryRouter>
      <AdminOffersPage />
    </MemoryRouter>,
  )
}

describe('AdminOffersPage', () => {
  it('offers the undo that fits each state', async () => {
    stub([
      offer('o1', 'On the market', { listing: listing() }),
      offer('o2', 'Taken', { listing: listing({ id: 'l2', status: 'ACCEPTED', buyer: { orgName: 'Card Shop', email: null }, label }) }),
      offer('o3', 'Arrived', { listing: listing({ id: 'l3', status: 'ACCEPTED', label, outcome: 'RECEIVED' }) }),
      offer('o4', 'Cancelled', {
        voided: { reason: 'Deal cancelled: seller sold it', at: '2026-10-21T10:00:00Z', by: 'zach@example.com' },
        listing: listing({ id: 'l4', status: 'CANCELLED', label, undone: { reason: 'seller sold it', at: '2026-10-21T10:00:00Z', by: 'zach@example.com' } }),
      }),
    ])
    renderPage()

    const market = await screen.findByRole('region', { name: 'Offer on On the market' })
    expect(within(market).getByRole('button', { name: 'Void offer' })).toBeInTheDocument()
    const taken = screen.getByRole('region', { name: 'Offer on Taken' })
    expect(within(taken).getByText(/Taken by Card Shop/)).toBeInTheDocument()
    expect(within(taken).getByRole('button', { name: 'Cancel deal' })).toBeInTheDocument()
    expect(within(taken).queryByRole('button', { name: 'Void offer' })).not.toBeInTheDocument()
    const arrived = screen.getByRole('region', { name: 'Offer on Arrived' })
    expect(within(arrived).queryByRole('button')).not.toBeInTheDocument()
    const cancelled = screen.getByRole('region', { name: 'Offer on Cancelled' })
    expect(within(cancelled).getByText(/Cancelled .* by zach@example.com: seller sold it/)).toBeInTheDocument()
    expect(within(cancelled).getByText(/Voided .*: Deal cancelled: seller sold it/)).toBeInTheDocument()
    expect(within(cancelled).getByRole('button', { name: 'Refund label' })).toBeInTheDocument()
  })

  it('needs a reason, and posts it', async () => {
    const user = userEvent.setup()
    const posts = stub([offer('o1', 'Flareon', { listing: listing() })])
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Void offer' }))
    const confirm = screen.getByRole('button', { name: 'Void offer' })
    expect(confirm).toBeDisabled()
    await user.type(screen.getByLabelText('Void offer: reason'), 'wrong set')
    await user.click(confirm)

    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0]).toEqual({ url: '/api/admin/offers/o1/void', body: { reason: 'wrong set' } })
  })

  it('shows a refund the provider could not be asked for, and lets it be asked again', async () => {
    stub([
      offer('o1', 'Leafeon', {
        listing: listing({
          status: 'CANCELLED',
          label,
          labelRefund: { reason: 'unused', at: '2026-10-21T10:00:00Z', providerStatus: null, providerError: 'Couldn\'t ask for the refund: carrier is down' },
        }),
      }),
      offer('o2', 'Glaceon', {
        listing: listing({ id: 'l2', status: 'CANCELLED', label, labelRefund: { reason: 'unused', at: '2026-10-21T10:00:00Z', providerStatus: 'submitted', providerError: null } }),
      }),
    ])
    renderPage()

    const failed = await screen.findByRole('region', { name: 'Offer on Leafeon' })
    expect(within(failed).getByText(/carrier is down/)).toBeInTheDocument()
    expect(within(failed).getByRole('button', { name: 'Refund label' })).toBeInTheDocument()
    const asked = screen.getByRole('region', { name: 'Offer on Glaceon' })
    expect(within(asked).getByText(/Provider: submitted/)).toBeInTheDocument()
    expect(within(asked).queryByRole('button', { name: 'Refund label' })).not.toBeInTheDocument()
  })
})

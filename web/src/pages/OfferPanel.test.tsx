import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { likelyRungs, type MarketItem, type Offer, type Sale } from '../api'
import { respond } from '../test/auth0'
import { InspectionForm, MarketPage } from './MarketPage'
import { OfferPanel } from './OfferPanel'

const rungs = [
  { grade: '10', probability: 0.3, price: 688.5 },
  { grade: '9', probability: 0.5, price: 344.25 },
  { grade: '8', probability: 0.2, price: 206.55 },
  { grade: '7', probability: 0, price: 150 },
  { grade: '6', probability: 0, price: 100 },
  { grade: 'U', probability: 0, price: 0 },
]

const offer = (over: Partial<Offer> = {}): Offer => ({
  id: 'offer1',
  expectedOffer: 419.98,
  fmv: 600,
  confidence: 'B',
  graded: false,
  subjectToAuthentication: false,
  ladder: rungs,
  createdAt: '2026-10-19T10:00:00Z',
  expiresAt: '2099-10-26T10:00:00Z',
  expired: false,
  ...over,
})

/** Serves GETs in turn from {@code sales}; records POSTs, answering with {@code postAnswer}. */
function stubApi(sales: Sale[], postAnswer: () => Response = () => new Response(null, { status: 201 })) {
  const posts: { url: string; body: unknown }[] = []
  let i = 0
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push({ url, body: init.body ? JSON.parse(String(init.body)) : undefined })
        return Promise.resolve(postAnswer())
      }
      return Promise.resolve(respond(200, sales[Math.min(i++, sales.length - 1)]))
    }),
  )
  return posts
}

describe('likelyRungs', () => {
  it('shows the likely grades, then the best price for anything lower', () => {
    const { likely, lower } = likelyRungs(rungs)
    expect(likely.map((r) => r.grade)).toEqual(['10', '9', '8'])
    expect(lower?.grade).toBe('7')
  })
})

describe('OfferPanel', () => {
  it('gets an offer, then posts it to buyers', async () => {
    const user = userEvent.setup()
    const posts = stubApi([{ offer: null, listing: null }, { offer: offer(), listing: null }])
    render(<OfferPanel orgId="org1" cardId="card1" />)

    await user.click(await screen.findByRole('button', { name: 'Get an offer' }))
    expect(await screen.findByText('PSA 7 or lower')).toBeInTheDocument()
    expect(screen.getByText('$344.25')).toBeInTheDocument()
    expect(screen.getByText(/isn't sold until one does/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Sell at this offer' }))

    await waitFor(() => expect(posts).toHaveLength(2))
    expect(posts[0].url).toBe('/api/orgs/org1/cards/card1/offers')
    expect(posts[1]).toEqual({ url: '/api/orgs/org1/cards/card1/listings', body: { offerId: 'offer1' } })
  })

  it('says why a card gets no offer', async () => {
    const user = userEvent.setup()
    stubApi([{ offer: null, listing: null }], () => respond(409, { detail: "We haven't identified this card yet." }))
    render(<OfferPanel orgId="org1" cardId="card1" />)

    await user.click(await screen.findByRole('button', { name: 'Get an offer' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("We haven't identified this card yet.")
  })

  it('shows who took the card and what they paid', async () => {
    stubApi([
      {
        offer: offer(),
        listing: {
          id: 'l1', offerId: 'offer1', status: 'ACCEPTED', listedAt: '2026-10-19T10:00:00Z', expiresAt: '2026-11-02T10:00:00Z',
          closedAt: '2026-10-20T10:00:00Z', buyer: { orgName: 'Card Shop', email: 'shop@example.com' },
          inspection: { inspectedGrade: 9, authentic: true, pricePaid: 344.25, ladderPrice: 344.25, declinedReason: null, note: null, recordedAt: '2026-10-25T10:00:00Z' },
        },
      },
    ])
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/Taken by Card Shop \(shop@example.com\)/)).toBeInTheDocument()
    expect(screen.getByText(/Inspected at PSA 9; paid \$344.25/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sell at this offer' })).not.toBeInTheDocument()
  })
})

describe('MarketPage', () => {
  const item: MarketItem = {
    listingId: 'l1',
    card: { name: 'Umbreon VMAX', setName: 'Evolving Skies', number: '215', language: 'en', edition: 'UNLIMITED', variant: 'HOLO', label: 'x', distribution: {}, gradedAs: null },
    offer: offer({ subjectToAuthentication: true }),
    photos: [{ side: 'FRONT', url: 'javascript:alert(1)' }],
    listedAt: '2026-10-19T10:00:00Z',
    expiresAt: '2026-11-02T10:00:00Z',
  }

  it('asks the buyer to confirm, and says when another buyer was first', async () => {
    const user = userEvent.setup()
    const fetch = vi.fn((_url: string, init?: RequestInit) =>
      Promise.resolve(init?.method === 'POST' ? respond(409, { detail: 'Another buyer took this card first.' }) : respond(200, [item])),
    )
    vi.stubGlobal('fetch', fetch)
    render(
      <MemoryRouter initialEntries={['/orgs/b1/market']}>
        <Routes>
          <Route path="/orgs/:orgId/market" element={<MarketPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('Umbreon VMAX')).toBeInTheDocument()
    expect(screen.getByText(/Subject to the buyer authenticating/)).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument() // a non-web photo link is never rendered
    await user.click(screen.getByRole('button', { name: 'Accept' }))
    await user.click(screen.getByRole('button', { name: 'Confirm: I commit to buy at these prices' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Another buyer took this card first.')
    expect(fetch.mock.calls.some(([url]) => url === '/api/orgs/b1/market/l1/accept')).toBe(true)
  })
})

describe('InspectionForm', () => {
  it('sends no price for a declined card', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], () => respond(201, []))
    const onSaved = vi.fn()
    render(<InspectionForm orgId="b1" listingId="l1" onSaved={onSaved} />)

    await user.type(screen.getByLabelText('Inspected grade'), '7')
    await user.click(screen.getByLabelText('Genuine'))
    await user.selectOptions(screen.getByLabelText('Declined because'), 'NOT_GENUINE')
    expect(screen.queryByLabelText('Price paid')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Record inspection' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(posts[0].body).toEqual({ inspectedGrade: 7, authentic: false, pricePaid: null, declinedReason: 'NOT_GENUINE', note: null })
  })
})

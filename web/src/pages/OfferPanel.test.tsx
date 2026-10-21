import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { likelyRungs, type CardStatus, type Deal, type MarketItem, type Offer, type Sale, type ShipAddress } from '../api'
import { respond } from '../test/auth0'
import { ArrivalForm, DealsPage, MarketPage } from './MarketPage'
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
  price: 419.98,
  fmv: 600,
  confidence: 'B',
  buyerMargin: 0.13,
  graded: false,
  subjectToAuthentication: false,
  ladder: rungs,
  createdAt: '2026-10-19T10:00:00Z',
  expiresAt: '2099-10-26T10:00:00Z',
  expired: false,
  ...over,
})

const address: ShipAddress = { name: 'Sam', company: null, street1: '1 Main St', street2: null, city: 'Springfield', state: 'IL', zip: '62701', country: 'US', phone: null }

const ready: CardStatus = { cardId: 'card1', stage: 'READY', identity: 'Umbreon VMAX · Evolving Skies 215 · EN', nextStep: 'Ready for an instant offer.', canOffer: true }

/** Answers GETs by path (sale answers in turn), records POSTs and answers them with {@code postAnswer}. */
function stubApi(
  sales: Sale[],
  shipFrom: ShipAddress | null,
  postAnswer: () => Response = () => new Response(null, { status: 201 }),
  status: CardStatus = ready,
) {
  const posts: { url: string; body: unknown }[] = []
  let i = 0
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push({ url, body: init.body ? JSON.parse(String(init.body)) : undefined })
        return Promise.resolve(postAnswer())
      }
      if (url.endsWith('/ship-address')) return Promise.resolve(shipFrom ? respond(200, shipFrom) : new Response(null, { status: 200 }))
      if (url.endsWith('/status')) return Promise.resolve(respond(200, status))
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
  it('gets one firm price, then sells at it', async () => {
    const user = userEvent.setup()
    const posts = stubApi([{ offer: null, listing: null }, { offer: offer(), listing: null }], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    await user.click(await screen.findByRole('button', { name: 'Get an offer' }))
    expect(await screen.findByText('$419.98')).toBeInTheDocument()
    expect(screen.getByText(/isn't sold until a buyer accepts/)).toBeInTheDocument()
    expect(screen.getByText('How we priced it')).toBeInTheDocument()
    expect(screen.getByText(/leaves the buyer a 13% margin, set by how sure we are of the card's condition \(confidence B\)/)).toBeInTheDocument()
    expect(screen.getByText(/Ships from Sam, 1 Main St, Springfield, IL 62701/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Sell for $419.98' }))
    // Posting commits the seller to ship: one more step, like the buyer's.
    expect(screen.getByText(/committing to ship them the card/)).toBeInTheDocument()
    expect(posts).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Confirm: post for $419.98' }))

    await waitFor(() => expect(posts).toHaveLength(2))
    expect(posts[0].url).toBe('/api/orgs/org1/cards/card1/offers')
    expect(posts[1]).toEqual({ url: '/api/orgs/org1/cards/card1/listings', body: { offerId: 'offer1' } })
  })

  it('asks where the card ships from before it can be sold', async () => {
    stubApi([{ offer: offer(), listing: null }], null)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText('Where will you ship from?')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Sell for/ })).not.toBeInTheDocument()
    expect(screen.getByLabelText('ZIP code')).toBeRequired()
  })

  it('says why a card gets no offer', async () => {
    const user = userEvent.setup()
    stubApi([{ offer: null, listing: null }], address, () => respond(409, { detail: "There isn't enough sales data to make a firm offer on this card yet." }))
    render(<OfferPanel orgId="org1" cardId="card1" />)

    await user.click(await screen.findByRole('button', { name: 'Get an offer' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("There isn't enough sales data")
  })

  it("hides Get an offer until it can work, and says what happens first", async () => {
    const identifying: CardStatus = {
      cardId: 'card1',
      stage: 'IDENTIFYING',
      identity: null,
      nextStep: "We identify each card by hand from its photos, usually within a couple of days. We don't send notifications yet, so check back here.",
      canOffer: false,
    }
    stubApi([{ offer: null, listing: null }], address, undefined, identifying)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/We identify each card by hand/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Get an offer' })).not.toBeInTheDocument()
  })

  it('lets the seller change the ship-from address', async () => {
    const user = userEvent.setup()
    stubApi([{ offer: offer(), listing: null }], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    await user.click(await screen.findByRole('button', { name: 'Change' }))
    expect(screen.getByText('Where will you ship from?')).toBeInTheDocument()
    expect(screen.getByLabelText('ZIP code')).toHaveAttribute('inputmode', 'numeric')
    expect(screen.getByLabelText('ZIP code')).toHaveAttribute('autocomplete', 'postal-code')
    expect(screen.getByLabelText('State')).toHaveAttribute('placeholder', 'State, e.g. IL')
  })

  it('asks before withdrawing a posted card', async () => {
    const user = userEvent.setup()
    const posts = stubApi(
      [
        {
          offer: offer(),
          listing: {
            id: 'l1', offerId: 'offer1', status: 'OPEN', listedAt: '2026-10-19T10:00:00Z', expiresAt: '2099-11-02T10:00:00Z',
            closedAt: null, buyer: null, label: null, outcome: null,
          },
        },
      ],
      address,
    )
    render(<OfferPanel orgId="org1" cardId="card1" />)

    await user.click(await screen.findByRole('button', { name: 'Withdraw' }))
    expect(posts).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Confirm: take it off the market' }))
    await waitFor(() => expect(posts).toEqual([{ url: '/api/orgs/org1/listings/l1/withdraw', body: undefined }]))
  })

  it("tells the seller not to ship while the label hasn't been bought", async () => {
    stubApi(
      [
        {
          offer: offer(),
          listing: {
            id: 'l1', offerId: 'offer1', status: 'ACCEPTED', listedAt: '2026-10-19T10:00:00Z', expiresAt: '2026-11-02T10:00:00Z',
            closedAt: '2026-10-20T10:00:00Z', buyer: { orgName: 'Card Shop', email: null }, label: null, outcome: null,
          },
        },
      ],
      address,
    )
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/Your prepaid label isn't ready yet/)).toHaveTextContent("Don't ship the card until the label shows here")
    expect(screen.queryByText(/on its way/)).not.toBeInTheDocument()
  })

  it('hands the seller the prepaid label once a buyer takes the card', async () => {
    stubApi(
      [
        {
          offer: offer(),
          listing: {
            id: 'l1', offerId: 'offer1', status: 'ACCEPTED', listedAt: '2026-10-19T10:00:00Z', expiresAt: '2026-11-02T10:00:00Z',
            closedAt: '2026-10-20T10:00:00Z', buyer: { orgName: 'Card Shop', email: 'shop@example.com' },
            label: { carrier: 'USPS', service: 'GroundAdvantage', trackingCode: '9400111', labelUrl: 'https://labels.example/l1.pdf', insuredValue: 419.98, boughtAt: '2026-10-20T10:00:00Z' },
            outcome: null,
          },
        },
      ],
      address,
    )
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/Sold to Card Shop \(shop@example.com\)/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Print your prepaid label' })).toHaveAttribute('href', 'https://labels.example/l1.pdf')
    expect(screen.getByText(/tracking 9400111, insured for \$419.98/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Sell for/ })).not.toBeInTheDocument()
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

  function renderMarket() {
    render(
      <MemoryRouter initialEntries={['/orgs/b1/market']}>
        <Routes>
          <Route path="/orgs/:orgId/market" element={<MarketPage />} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it('asks the buyer to confirm, and says when another buyer was first', async () => {
    const user = userEvent.setup()
    const fetch = vi.fn((_url: string, init?: RequestInit) =>
      Promise.resolve(init?.method === 'POST' ? respond(409, { detail: 'Another buyer took this card first.' }) : respond(200, [item])),
    )
    vi.stubGlobal('fetch', fetch)
    renderMarket()

    expect(await screen.findByText('Umbreon VMAX')).toBeInTheDocument()
    expect(screen.getByText('$419.98')).toBeInTheDocument()
    expect(screen.getByText(/Not yet authenticated/)).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument() // a non-web photo link is never rendered
    await user.click(screen.getByRole('button', { name: 'Buy' }))
    await user.click(screen.getByRole('button', { name: 'Confirm: buy for $419.98' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Another buyer took this card first.')
    expect(fetch.mock.calls.some(([url]) => url === '/api/orgs/b1/market/l1/accept')).toBe(true)
  })

  it('says when the label is still to come', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) =>
        Promise.resolve(init?.method === 'POST' ? respond(200, { deals: [], labelPending: 'No shipping account is set up yet' }) : respond(200, [item])),
      ),
    )
    renderMarket()

    await user.click(await screen.findByRole('button', { name: 'Buy' }))
    await user.click(screen.getByRole('button', { name: 'Confirm: buy for $419.98' }))
    expect(await screen.findByRole('status')).toHaveTextContent(
      "Bought. The seller's label didn't go through yet (No shipping account is set up yet). Try it again from Your deals, or we'll buy it for you.",
    )
  })

  it('shows each photo labelled, and links the missing-address refusal to where the address goes', async () => {
    const user = userEvent.setup()
    const photographed = {
      ...item,
      photos: [
        { side: 'FRONT' as const, url: 'https://photos.example/f.jpg' },
        { side: 'BACK' as const, url: 'https://photos.example/b.jpg' },
      ],
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) =>
        Promise.resolve(init?.method === 'POST' ? respond(409, { detail: 'Add the address cards should ship to first.' }) : respond(200, [photographed])),
      ),
    )
    renderMarket()

    expect(await screen.findByRole('img', { name: 'Front photo' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Back photo' })).toBeInTheDocument()
    expect(screen.getByText('Front')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Buy' }))
    await user.click(screen.getByRole('button', { name: 'Confirm: buy for $419.98' }))
    expect(await screen.findByRole('link', { name: 'Add your address' })).toHaveAttribute('href', '/orgs/b1')
  })
})

describe('ArrivalForm', () => {
  const deal: Deal = {
    listingId: 'l1',
    card: { name: 'x', setName: 's', number: '1', language: 'en', edition: 'UNLIMITED', variant: 'NORMAL', label: 'x', distribution: {}, gradedAs: null },
    offer: offer(),
    photos: [],
    seller: { orgName: 'Sam', email: null },
    acceptedAt: '2026-10-20T10:00:00Z',
    label: null,
    outcome: null,
  }

  it('records the price paid, prefilled with the offer', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    const onSaved = vi.fn()
    render(<ArrivalForm path="/api/orgs/b1/deals/l1" price={deal.offer.price} onSaved={onSaved} />)

    expect(screen.getByLabelText('Price paid')).toHaveValue('419.98')
    await user.click(screen.getByRole('button', { name: 'Record' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(posts[0]).toEqual({ url: '/api/orgs/b1/deals/l1/received', body: { pricePaid: 419.98, note: null } })
  })

  it('sends a card back only for an allowed reason', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    render(<ArrivalForm path="/api/orgs/b1/deals/l1" price={deal.offer.price} onSaved={vi.fn()} />)

    await user.click(screen.getByLabelText('Sending it back'))
    expect(screen.queryByLabelText('Price paid')).not.toBeInTheDocument()
    // No reason is picked for the buyer: it's data, and a default would skew it.
    expect(screen.getByLabelText('Return reason')).toHaveValue('')
    expect(screen.getByLabelText('Return reason')).toBeRequired()
    await user.click(screen.getByRole('button', { name: 'Record' }))
    expect(posts).toHaveLength(0)
    await user.selectOptions(screen.getByLabelText('Return reason'), 'NOT_GENUINE')
    await user.click(screen.getByRole('button', { name: 'Record' }))

    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0]).toEqual({ url: '/api/orgs/b1/deals/l1/returned', body: { reason: 'NOT_GENUINE', note: null } })
  })

  it('needs a reason when the amount paid differs from the offer', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    render(<ArrivalForm path="/api/orgs/b1/deals/l1" price={deal.offer.price} onSaved={vi.fn()} />)

    expect(screen.getByLabelText('Note')).not.toBeRequired()
    await user.clear(screen.getByLabelText('Price paid'))
    await user.type(screen.getByLabelText('Price paid'), '95')
    expect(screen.getByLabelText('Note')).toBeRequired()
    await user.click(screen.getByRole('button', { name: 'Record' }))
    expect(posts).toHaveLength(0)
    await user.type(screen.getByLabelText('Note'), 'agreed a discount')
    await user.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() => expect(posts[0]).toEqual({ url: '/api/orgs/b1/deals/l1/received', body: { pricePaid: 95, note: 'agreed a discount' } }))
  })

  it("lets the admin record for the buyer, saying how it shipped", async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    render(<ArrivalForm path="/api/admin/deals/l1?buyerOrgId=b1" price={deal.offer.price} onSaved={vi.fn()} noteRequired />)

    expect(screen.getByLabelText('Note')).toBeRequired()
    await user.type(screen.getByLabelText('Note'), 'own stamps')
    await user.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() =>
      expect(posts[0]).toEqual({ url: '/api/admin/deals/l1/received?buyerOrgId=b1', body: { pricePaid: 419.98, note: 'own stamps' } }),
    )
  })
})

describe('DealsPage', () => {
  const deal: Deal = {
    listingId: 'l1',
    card: { name: 'Umbreon VMAX', setName: 's', number: '1', language: 'en', edition: 'UNLIMITED', variant: 'NORMAL', label: 'x', distribution: {}, gradedAs: null },
    offer: offer(),
    photos: [{ side: 'FRONT', url: 'https://photos.example/f.jpg' }],
    seller: { orgName: 'Sam', email: null },
    acceptedAt: '2026-10-20T10:00:00Z',
    label: null,
    outcome: null,
  }

  function renderDeals(deals: Deal[]) {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(respond(200, deals))))
    render(
      <MemoryRouter initialEntries={['/orgs/b1/deals']}>
        <Routes>
          <Route path="/orgs/:orgId/deals" element={<DealsPage />} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it("shows the listed photos, and no arrival form before there's a label", async () => {
    renderDeals([deal])
    expect(await screen.findByRole('img', { name: 'Front photo' })).toBeInTheDocument()
    expect(screen.getByText(/Once the label is bought and the card ships/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record' })).not.toBeInTheDocument()
  })

  it('records the arrival once the card is labelled', async () => {
    renderDeals([
      { ...deal, label: { carrier: 'USPS', service: 'Ground', trackingCode: '94', labelUrl: 'https://l', insuredValue: 419.98, boughtAt: '2026-10-20T10:00:00Z' } },
    ])
    expect(await screen.findByRole('button', { name: 'Record' })).toBeInTheDocument()
  })
})

import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { likelyRungs, type BuyerOutcomeRecord, type CardStatus, type Deal, type MarketItem, type DealProgress, type Listing, type Offer, type PostageEstimate, type Sale, type ShipAddress } from '../api'
import { respond } from '../test/auth0'
import { ArrivalForm, DealsPage, MarketPage, PaidForm } from './MarketPage'
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
  voided: null,
  sold: [
    { gradingCompany: 'PSA', grade: '10', price: 900, sales: 3, windowDays: 90 },
    { gradingCompany: 'PSA', grade: '9', price: 450, sales: 1, windowDays: 180 },
  ],
  lessSureBecause: ['FEW_SALES'],
  ...over,
})

const address: ShipAddress = { name: 'Sam', company: null, street1: '1 Main St', street2: null, city: 'Springfield', state: 'IL', zip: '62701', country: 'US', phone: null, paymentMethod: 'VENMO', paymentHandle: '@sam-cards' }

const ready: CardStatus = { cardId: 'card1', stage: 'READY', identity: 'Umbreon VMAX · Evolving Skies 215 · EN', nextStep: 'Ready for an instant offer.', canOffer: true, condition: null, deadline: null }

/** Answers GETs by path (sale answers in turn), records POSTs and answers them with {@code postAnswer}. */
function stubApi(
  sales: Sale[],
  shipFrom: ShipAddress | null | 'fails',
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
      if (url.endsWith('/ship-address')) {
        if (shipFrom === 'fails') return Promise.resolve(respond(500, { detail: 'Server error' }))
        return Promise.resolve(shipFrom ? respond(200, shipFrom) : new Response(null, { status: 200 }))
      }
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
    expect(await screen.findByTestId('offer-reveal')).toHaveTextContent('$419.98')
    expect(screen.getByText(/isn't sold until a buyer accepts/)).toBeInTheDocument()
    expect(screen.getByText(/The buyer pays you directly, not us/)).toBeInTheDocument()
    expect(screen.getByText('How we priced it')).toBeInTheDocument()
    expect(screen.getByText(/leaves the buyer a 13% margin, set by how sure we are of the card's condition: the less sure/)).toBeInTheDocument()
    expect(screen.getByText(/Ships from Sam, 1 Main St, Springfield, IL 62701/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Sell for $419.98' }))
    // Posting commits the seller to ship: one more step, like the buyer's.
    expect(screen.getByText(/committing to ship them the card/)).toHaveTextContent('They pay you directly, by Venmo, within 7 days of it arriving.')
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
    expect(screen.getByLabelText('Pay me by')).toBeRequired()
    expect(screen.getByLabelText('Your payment details')).toBeRequired()
  })

  it('says how the seller gets paid before they commit', async () => {
    const user = userEvent.setup()
    stubApi([{ offer: offer(), listing: null }], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/The buyer pays you directly, not us, by Venmo/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Sell for $419.98' }))
    expect(screen.getByText(/committing to ship them the card/)).toHaveTextContent('They pay you directly, by Venmo, within 7 days of it arriving.')
  })

  it('asks for payment details an older address lacks, before the card can be sold', async () => {
    stubApi([{ offer: offer(), listing: null }], { ...address, paymentMethod: null, paymentHandle: null })
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByLabelText('Your payment details')).toBeInTheDocument()
    expect(screen.getByLabelText('Street address')).toHaveValue('1 Main St')
    expect(screen.queryByRole('button', { name: /Sell for/ })).not.toBeInTheDocument()
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
      condition: null,
      deadline: null,
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
            closedAt: null, buyer: null, label: null, outcome: null, undone: null, progress: null,
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
            closedAt: '2026-10-20T10:00:00Z', buyer: { orgName: 'Card Shop' }, label: null, outcome: null, undone: null, progress: null,
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
            closedAt: '2026-10-20T10:00:00Z', buyer: { orgName: 'Card Shop' },
            label: { carrier: 'USPS', service: 'GroundAdvantage', trackingCode: '9400111', labelUrl: 'https://labels.example/l1.pdf', insuredValue: 419.98, boughtAt: '2026-10-20T10:00:00Z', shipBy: '2026-10-25T10:00:00Z' },
            outcome: null,
            undone: null,
            progress: null,
          },
        },
      ],
      address,
    )
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/Sold to Card Shop on/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Print your prepaid label' })).toHaveAttribute('href', 'https://labels.example/l1.pdf')
    // The tracking number is said once, in the timeline's label step.
    expect(screen.getByText('Tracking 9400111')).toBeInTheDocument()
    expect(screen.getByText(/GroundAdvantage, insured for \$419.98/)).toBeInTheDocument()
    expect(screen.queryByText(/, tracking 9400111/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Sell for/ })).not.toBeInTheDocument()
    expect(screen.getByText(/in a bubble mailer/)).toBeInTheDocument()
    // The API's ship-by date, not one worked out from boughtAt (review finding 17).
    expect(screen.getByText(`Hand it to the carrier by ${'Oct 25'}.`)).toBeInTheDocument()
  })

  it("says so when it can't load the seller's address", async () => {
    // Review finding 48: the error was dropped, leaving Sell disabled with no reason.
    stubApi([{ offer: offer(), listing: null }], 'fails')
    render(<OfferPanel orgId="org1" cardId="card1" />)
    expect(await screen.findByText(/Couldn't load your shipping address/)).toBeInTheDocument()
  })

  it('says why we withdrew an offer, and offers a new one instead of selling', async () => {
    stubApi([{ offer: offer({ voided: { reason: 'wrong set', at: '2026-10-21T10:00:00Z' } }), listing: null }], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/We withdrew this offer on .*: wrong set\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Get a new offer' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Sell for/ })).not.toBeInTheDocument()
  })

  it('tells the seller not to ship once we cancel the sale', async () => {
    stubApi(
      [
        {
          offer: offer({ voided: { reason: 'Deal cancelled: buyer closed', at: '2026-10-21T10:00:00Z' } }),
          listing: {
            id: 'l1', offerId: 'offer1', status: 'CANCELLED', listedAt: '2026-10-19T10:00:00Z', expiresAt: '2026-11-02T10:00:00Z',
            closedAt: '2026-10-20T10:00:00Z', buyer: { orgName: 'Card Shop' },
            label: { carrier: 'USPS', service: 'GroundAdvantage', trackingCode: '9400111', labelUrl: 'https://labels.example/l1.pdf', insuredValue: 419.98, boughtAt: '2026-10-20T10:00:00Z', shipBy: '2026-10-23T10:00:00Z' },
            outcome: null,
            undone: { reason: 'buyer closed', at: '2026-10-21T10:00:00Z' },
            progress: null,
          },
        },
      ],
      address,
    )
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/We cancelled the sale to Card Shop on .*: buyer closed\. Don't ship the card/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Print your prepaid label' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Get a new offer' })).toBeInTheDocument()
  })
})

describe('OfferPanel after the sale', () => {
  const printable = { carrier: 'USPS', service: 'GroundAdvantage', trackingCode: '9400111', labelUrl: 'https://labels.example/l1.pdf', insuredValue: 419.98, boughtAt: '2026-10-20T10:00:00Z', shipBy: '2026-10-23T10:00:00Z' }
  const none: DealProgress = {
    stage: 'SOLD', shippedAt: null, arrivedAt: null, payBy: null, paidAt: null, pricePaid: null, paidNote: null,
    confirmedAt: null, disputedAt: null, disputeNote: null, review: null, held: false, cardBackAt: null,
  }
  const sold = (progress: Partial<DealProgress>, over: Partial<Listing> = {}): Sale => ({
    offer: offer(),
    listing: {
      id: 'l1', offerId: 'offer1', status: 'ACCEPTED', listedAt: '2026-10-19T10:00:00Z', expiresAt: '2026-11-02T10:00:00Z',
      closedAt: '2026-10-20T10:00:00Z', buyer: { orgName: 'Card Shop' }, label: { ...printable, labelUrl: null }, outcome: null, undone: null,
      progress: { ...none, ...progress },
      ...over,
    },
  })
  const arrived = { stage: 'ARRIVED' as const, arrivedAt: '2026-10-24T10:00:00Z', payBy: '2099-10-31T10:00:00Z' }
  const returned = {
    type: 'RETURNED' as const, pricePaid: null, returnedReason: 'NOT_GENUINE' as const, note: 'wrong font', inspectedGrade: null, recordedAt: '2026-10-24T10:00:00Z',
  }

  it('says how the buyer pays before the card ships, and takes the label away once it is shipped', async () => {
    const user = userEvent.setup()
    const posts = stubApi([sold({}, { label: printable })], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/directly, by Venmo \(@sam-cards\), within 7 days/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Print your prepaid label' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: "I've shipped it" }))
    expect(posts).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: "Confirm: it's shipped" }))
    await waitFor(() => expect(posts).toEqual([{ url: '/api/orgs/org1/listings/l1/shipped', body: undefined }]))
  })

  it('has no label to print once it shipped', async () => {
    stubApi([sold({ stage: 'SHIPPED', shippedAt: '2026-10-21T10:00:00Z' })], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect((await screen.findAllByText('Shipped')).length).toBeGreaterThan(0)
    expect(screen.queryByRole('link', { name: 'Print your prepaid label' })).not.toBeInTheDocument()
    expect(screen.queryByText('How to ship it')).not.toBeInTheDocument()
    // Payment is due once it arrives, so the seller isn't asked about it yet.
    expect(screen.queryByRole('button', { name: 'I got paid' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: "I haven't been paid" })).not.toBeInTheDocument()
    expect(screen.getByText('Tracking 9400111')).toBeInTheDocument()
  })

  it('shows the pay-by date, and takes "I got paid" with a confirm', async () => {
    const user = userEvent.setup()
    const posts = stubApi([sold(arrived)], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText('Oct 31')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'I got paid' }))
    expect(posts).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Confirm: I got paid' }))
    await waitFor(() => expect(posts).toEqual([{ url: '/api/orgs/org1/listings/l1/payment-confirmed', body: undefined }]))
  })

  it('takes "I haven\'t been paid", and says the date has not passed yet', async () => {
    const user = userEvent.setup()
    const posts = stubApi([sold(arrived)], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    await user.click(await screen.findByRole('button', { name: "I haven't been paid" }))
    expect(screen.getByText(/They have until .* to pay/)).toBeInTheDocument()
    await user.type(screen.getByLabelText('Anything we should know (optional)'), 'no reply to my emails')
    await user.click(screen.getByRole('button', { name: "Confirm: I haven't been paid" }))
    await waitFor(() => expect(posts).toEqual([{ url: '/api/orgs/org1/listings/l1/dispute', body: { note: 'no reply to my emails' } }]))
  })

  it('shows a dispute as raised, without offering it again', async () => {
    stubApi([sold({ ...arrived, disputedAt: '2026-10-25T10:00:00Z', disputeNote: 'nothing yet' })], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/You told us about this on .*: "nothing yet"\. Zach will be in touch\./)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: "I haven't been paid" })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'I got paid' })).toBeInTheDocument()
  })

  it('asks whether the buyer\'s payment came, then has nothing more to do', async () => {
    const paid = { ...arrived, stage: 'PAID' as const, paidAt: '2026-10-25T10:00:00Z', pricePaid: 419.98 }
    stubApi([sold(paid)], address)
    const { unmount } = render(<OfferPanel orgId="org1" cardId="card1" />)
    expect(await screen.findByText(/They recorded paying \$419\.98 on .* Did you get it\?/)).toBeInTheDocument()
    unmount()

    stubApi([sold({ ...paid, confirmedAt: '2026-10-26T10:00:00Z' })], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)
    expect(await screen.findByText(/Nothing more to do/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'I got paid' })).not.toBeInTheDocument()
  })

  it('on a return says what happens next, and asks when the card is back', async () => {
    const user = userEvent.setup()
    const posts = stubApi([sold({ stage: 'RETURNED', held: true }, { outcome: returned })], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText('Returned: not genuine')).toBeInTheDocument()
    expect(screen.getByText('They said: "wrong font"')).toBeInTheDocument()
    expect(screen.getByText(/they send it back to you, tracked and at their cost/)).toHaveTextContent("can't get a new offer until he has")
    expect(screen.queryByRole('link', { name: 'Print your prepaid label' })).not.toBeInTheDocument()
    expect(screen.queryByText(/ship it/i)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: "The card's back with me" }))
    await user.click(screen.getByRole('button', { name: "Confirm: it's back" }))
    await waitFor(() => expect(posts).toEqual([{ url: '/api/orgs/org1/listings/l1/card-back', body: undefined }]))
  })

  it('lets the seller disagree with a return, and needs to hear why', async () => {
    const user = userEvent.setup()
    const posts = stubApi([sold({ stage: 'RETURNED' }, { outcome: returned })], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    await user.click(await screen.findByRole('button', { name: 'I disagree' }))
    expect(screen.getByRole('button', { name: 'Send it to Zach' })).toBeDisabled()
    await user.type(screen.getByLabelText('Why you disagree'), 'it came from a sealed pack')
    await user.click(screen.getByRole('button', { name: 'Send it to Zach' }))
    await waitFor(() => expect(posts).toEqual([{ url: '/api/orgs/org1/listings/l1/dispute', body: { note: 'it came from a sealed pack' } }]))
  })

  it('offers a new offer once the card is back, and never the one it sold at', async () => {
    stubApi([sold({ stage: 'BACK', cardBackAt: '2026-10-28T10:00:00Z' }, { status: 'BACK', outcome: { ...returned, returnedReason: 'DOES_NOT_MATCH_PHOTOS' } })], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByRole('button', { name: 'Get a new offer' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Sell for/ })).not.toBeInTheDocument()
  })

  it('keeps a held card from asking for an offer, and says why', async () => {
    const held: CardStatus = { ...ready, stage: 'RETURNED', canOffer: false, nextStep: "A buyer said this card isn't genuine, so we review that before it can get a new offer." }
    stubApi([sold({ stage: 'BACK', held: true, cardBackAt: '2026-10-28T10:00:00Z' }, { status: 'BACK', outcome: returned })], address, undefined, held)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/we review that before it can get a new offer/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Get a new offer' })).not.toBeInTheDocument()
  })

  it('asks a seller who gave no payment details for them, keeping their address', async () => {
    stubApi([sold({ stage: 'SHIPPED' })], { ...address, paymentMethod: null, paymentHandle: null })
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/The buyer can't pay you until you say how/)).toBeInTheDocument()
    expect(screen.getByLabelText('Street address')).toHaveValue('1 Main St')
    expect(screen.getByLabelText('Your payment details')).toBeRequired()
  })
})

describe('MarketPage', () => {
  const item: MarketItem = {
    listingId: 'l1',
    card: { name: 'Umbreon VMAX', setName: 'Evolving Skies', number: '215', language: 'en', edition: 'UNLIMITED', variant: 'HOLO', label: 'x', distribution: {}, gradedAs: null },
    offer: offer({ subjectToAuthentication: true }),
    photos: [{ side: 'FRONT', url: 'javascript:alert(1)', fullUrl: 'javascript:alert(2)' }],
    listedAt: '2026-10-19T10:00:00Z',
    expiresAt: '2026-11-02T10:00:00Z',
  }

  const estimate: PostageEstimate = { postage: 5.1, insurance: 4.2, total: 9.3, carrier: 'USPS', service: 'GroundAdvantage', quoted: true }

  /** The market, the buyer's address and the postage by path; POSTs get {@code post}. */
  function stubMarket(
    items: MarketItem[],
    post: () => Response,
    { shipTo = address as ShipAddress | null, postage = () => respond(200, estimate) } = {},
  ) {
    const fetch = vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return Promise.resolve(post())
      if (url.endsWith('/ship-address')) return Promise.resolve(shipTo ? respond(200, shipTo) : new Response(null, { status: 200 }))
      if (url.endsWith('/postage')) return Promise.resolve(postage())
      return Promise.resolve(respond(200, items))
    })
    vi.stubGlobal('fetch', fetch)
    return fetch
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
    const fetch = stubMarket([item], () => respond(409, { detail: 'Another buyer took this card first.' }))
    renderMarket()

    expect(await screen.findByText('Umbreon VMAX')).toBeInTheDocument()
    expect(screen.getAllByText('$419.98').length).toBeGreaterThan(0)
    expect(screen.getByText(/Not yet authenticated/)).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument() // a non-web photo link is never rendered
    await user.click(screen.getByRole('button', { name: 'Buy' }))
    await user.click(screen.getByRole('button', { name: 'Confirm: buy for $419.98' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Another buyer took this card first.')
    expect(fetch.mock.calls.some(([url]) => url === '/api/orgs/b1/market/l1/accept')).toBe(true)
  })

  it('says when the label is still to come', async () => {
    const user = userEvent.setup()
    stubMarket([item], () => respond(200, { deals: [], labelPending: 'No shipping account is set up yet' }))
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
        { side: 'FRONT' as const, url: 'https://photos.example/f.jpg', fullUrl: 'https://photos.example/f-full.jpg' },
        { side: 'BACK' as const, url: 'https://photos.example/b.jpg', fullUrl: 'https://photos.example/b-full.jpg' },
      ],
    }
    stubMarket([photographed], () => respond(409, { detail: 'Add the address cards should ship to first.', code: 'NO_BUYER_ADDRESS' }))
    renderMarket()

    expect(await screen.findByRole('img', { name: 'Front photo' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Back photo' })).toBeInTheDocument()
    expect(screen.getByText('Front')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Buy' }))
    await user.click(screen.getByRole('button', { name: 'Confirm: buy for $419.98' }))
    expect(await screen.findByRole('link', { name: 'Add your address' })).toHaveAttribute('href', '/orgs/b1')
  })

  it('opens the photos full screen, as taken, and moves between them', async () => {
    const user = userEvent.setup()
    const photographed = {
      ...item,
      photos: [
        { side: 'FRONT' as const, url: 'https://photos.example/f.jpg', fullUrl: 'https://photos.example/f-full.jpg' },
        { side: 'BACK' as const, url: 'https://photos.example/b.jpg', fullUrl: 'https://photos.example/b-full.jpg' },
        { side: 'DETAIL' as const, url: 'https://photos.example/d.jpg', fullUrl: 'https://photos.example/d-full.jpg' },
      ],
    }
    stubMarket([photographed], () => respond(500, {}))
    renderMarket()

    await user.click(await screen.findByRole('button', { name: /Back photo/ }))
    const viewer = screen.getByRole('dialog', { name: 'Photos' })
    expect(viewer).toHaveTextContent('Back (2 of 3)')
    expect(screen.getByRole('img', { name: 'Close-up photo, full size' })).toHaveAttribute('src', 'https://photos.example/d-full.jpg')
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus()

    await user.click(screen.getByRole('button', { name: 'Next →' }))
    expect(viewer).toHaveTextContent('Close-up (3 of 3)')
    expect(screen.getByRole('button', { name: 'Next →' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Actual size' }))
    expect(screen.getByRole('button', { name: 'Fit to screen' })).toBeInTheDocument()
    await user.keyboard('{ArrowLeft}')
    expect(viewer).toHaveTextContent('Back (2 of 3)')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows the likely grade and that a close-up is included', async () => {
    stubMarket([{ ...item, photos: [{ side: 'DETAIL', url: 'https://photos.example/d.jpg', fullUrl: 'https://photos.example/d-full.jpg' }] }], () => respond(500, {}))
    renderMarket()

    expect(await screen.findByText('Most likely PSA 9 (50%)')).toBeInTheDocument()
    expect(screen.getByText('Close-up included')).toBeInTheDocument()
  })

  it('leaves out the close-up chip without one, and a tie goes to the lower grade', async () => {
    const tied = rungs.map((r) => ({ ...r, probability: r.grade === '10' || r.grade === '9' ? 0.5 : 0 }))
    stubMarket([{ ...item, offer: offer({ ladder: tied }) }], () => respond(500, {}))
    renderMarket()

    expect(await screen.findByText('Most likely PSA 9 (50%)')).toBeInTheDocument()
    expect(screen.queryByText('Close-up included')).not.toBeInTheDocument()
  })

  it('shows the postage and insurance, and the 7 days to pay, before the buyer confirms', async () => {
    const user = userEvent.setup()
    const fetch = stubMarket([item], () => respond(500, {}))
    renderMarket()

    await user.click(await screen.findByRole('button', { name: 'Buy' }))

    expect(await screen.findByText(/Postage and insurance: about/)).toHaveTextContent(
      'Postage and insurance: about $9.30 (USPS GroundAdvantage $5.10, plus $4.20 to insure it for $419.98), billed to you monthly.',
    )
    expect(screen.getByText(/within 7 days of it arriving/)).toBeInTheDocument()
    expect(fetch.mock.calls.some(([url]) => url === '/api/orgs/b1/market/l1/postage')).toBe(true)
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false) // nothing bought for an estimate
  })

  it('says when the postage is our usual rate rather than a quote', async () => {
    const user = userEvent.setup()
    stubMarket([item], () => respond(500, {}), {
      postage: () => respond(200, { postage: 6, insurance: 4.2, total: 10.2, carrier: null, service: null, quoted: false }),
    })
    renderMarket()

    await user.click(await screen.findByRole('button', { name: 'Buy' }))
    expect(await screen.findByText(/our usual postage of \$6.00/)).toBeInTheDocument()
  })

  it("still lets the buyer confirm when the postage can't be estimated", async () => {
    const user = userEvent.setup()
    stubMarket([item], () => respond(500, {}), { postage: () => respond(409, { detail: 'This card is no longer for sale.' }) })
    renderMarket()

    await user.click(await screen.findByRole('button', { name: 'Buy' }))
    expect(await screen.findByText(/We couldn't estimate the postage \(This card is no longer for sale\)/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm: buy for $419.98' })).toBeInTheDocument()
  })

  it('asks for the shipping address before showing anything for sale', async () => {
    stubMarket([item], () => respond(500, {}), { shipTo: null })
    renderMarket()

    expect(await screen.findByRole('heading', { name: 'Where should cards ship to?' })).toBeInTheDocument()
    expect(screen.getByLabelText('Street address')).toBeInTheDocument()
    expect(screen.queryByText('Umbreon VMAX')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Buy' })).not.toBeInTheDocument()
  })
})

describe('HowPriced', () => {
  function renderOffer(o: Offer) {
    stubApi([{ offer: o, listing: null }], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)
  }

  it('shows what each likely grade recently sold for, and the confidence in words', async () => {
    renderOffer(offer())

    expect(await screen.findByText('Fairly sure, because there are only a few recent sales.')).toBeInTheDocument()
    expect(screen.queryByText(/\(B\)/)).not.toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Recently sold for' })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /PSA 10/ })).toHaveTextContent('$900.00 (at least 3 sales, 90 days)')
    expect(screen.getByRole('row', { name: /PSA 9/ })).toHaveTextContent('$450.00 (at least 1 sale, 180 days)')
    expect(screen.getByRole('row', { name: /^PSA 8 / })).toHaveTextContent('no sales of its own')
  })

  it('itemises what each grade counts for, ending in the firm offer', async () => {
    renderOffer(offer())

    expect(await screen.findByRole('columnheader', { name: 'Counts for' })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /PSA 10/ })).toHaveTextContent('30%')
    expect(screen.getByRole('row', { name: /PSA 10/ })).toHaveTextContent('$206.55')
    expect(screen.getByRole('row', { name: /Firm offer/ })).toHaveTextContent('$419.98')
  })

  /** The dollar amounts in the "Counts for" column (the last cell of each grade row), in cents. */
  const countsFor = () =>
    screen
      .getAllByRole('row')
      .filter((row) => /^PSA \d+/.test(row.textContent ?? '') && !/or lower/.test(row.textContent ?? ''))
      .map((row) => Math.round(Number(row.lastElementChild!.textContent!.replace(/[$,]/g, '')) * 100))

  it('adds the rows up to the firm offer exactly, the last row taking the rounding', async () => {
    renderOffer(offer())

    await screen.findByRole('columnheader', { name: 'Counts for' })
    expect(countsFor().reduce((a, b) => a + b, 0)).toBe(41998)
  })

  it('shows the lower grades as a combined row that is not counted', async () => {
    renderOffer(offer())

    const row = await screen.findByRole('row', { name: /or lower/ })
    expect(row).toHaveTextContent('0%')
    expect(row).toHaveTextContent('not counted')
    expect(row).toHaveTextContent('$0.00')
    expect(row).not.toHaveTextContent('up to')
  })

  it('puts the rounding on the last row on the Victini example', async () => {
    renderOffer(
      offer({
        price: 636.12,
        ladder: [
          { grade: '10', probability: 0.6, price: 843.12 },
          { grade: '9', probability: 0.3, price: 337.25 },
          { grade: '8', probability: 0.1, price: 290.78 },
        ],
      }),
    )

    expect(await screen.findByRole('row', { name: /PSA 10/ })).toHaveTextContent('$505.87')
    expect(screen.getByRole('row', { name: /PSA 9/ })).toHaveTextContent('$101.18')
    expect(screen.getByRole('row', { name: /^PSA 8 / })).toHaveTextContent('$29.07')
    expect(screen.getByRole('row', { name: /Firm offer/ })).toHaveTextContent('$636.12')
    expect(countsFor().reduce((a, b) => a + b, 0)).toBe(63612)
  })

  it('leaves the sold prices out when they are switched off, and needs no reason at an A', async () => {
    renderOffer(offer({ sold: null, confidence: 'A', lessSureBecause: [] }))

    expect(await screen.findByText('Very sure.')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Recently sold for' })).not.toBeInTheDocument()
  })
})

describe('raw sales flag', () => {
  it('tells the seller plainly when the offer is below recent raw sales', async () => {
    stubApi([{ offer: offer({ rawSales: { lowest: 140, sales: 3, since: '2026-08-11', below: true } }), listing: null }], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText(/This offer is below recent raw sales of \$140\.00/)).toBeInTheDocument()
  })

  it('says nothing when the offer clears them, or there were none', async () => {
    stubApi([{ offer: offer({ rawSales: { lowest: 90, sales: 3, since: '2026-08-11', below: false } }), listing: null }], address)
    render(<OfferPanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText('Fairly sure, because there are only a few recent sales.')).toBeInTheDocument()
    expect(screen.queryByText(/below recent raw sales/)).not.toBeInTheDocument()
  })
})

/** The form as a buyer sees it after tapping "It arrived". */
function OpenArrivalForm({ onSaved }: { onSaved: () => void }) {
  return <ArrivalForm path="/api/orgs/b1/deals/l1" onSaved={onSaved} noteRequired={false} />
}

async function openArrival(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'It arrived' }))
}

describe('ArrivalForm', () => {
  it('shows nothing to record until the buyer says it arrived', async () => {
    const user = userEvent.setup()
    stubApi([], null, () => respond(201, []))
    render(<OpenArrivalForm onSaved={vi.fn()} />)

    expect(screen.queryByLabelText('Arrived as shown')).not.toBeInTheDocument()
    await openArrival(user)
    expect(screen.getByLabelText('Arrived as shown')).not.toBeChecked()
    expect(screen.getByLabelText('Sending it back')).not.toBeChecked()
    expect(screen.queryByRole('button', { name: 'Record' })).not.toBeInTheDocument()
  })

  it('records the arrival alone, and asks once more', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    const onSaved = vi.fn()
    render(<OpenArrivalForm onSaved={onSaved} />)

    await openArrival(user)
    await user.click(screen.getByLabelText('Arrived as shown'))
    expect(screen.queryByLabelText('Price paid')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Record' }))
    expect(screen.getByText('Record that it arrived as shown? The 7 days to pay start now.')).toBeInTheDocument()
    expect(posts).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Confirm: record it' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(posts[0]).toEqual({ url: '/api/orgs/b1/deals/l1/received', body: { note: null } })
  })

  it('asks again after a change, so what is confirmed is what is recorded', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    render(<OpenArrivalForm onSaved={vi.fn()} />)

    await openArrival(user)
    await user.click(screen.getByLabelText('Arrived as shown'))
    await user.click(screen.getByRole('button', { name: 'Record' }))
    await user.click(screen.getByLabelText('Sending it back'))
    expect(screen.queryByRole('button', { name: 'Confirm: record it' })).not.toBeInTheDocument()
    expect(posts).toHaveLength(0)
  })

  it('sends a card back only for an allowed reason', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    render(<OpenArrivalForm onSaved={vi.fn()} />)

    await openArrival(user)
    await user.click(screen.getByLabelText('Sending it back'))
    // No reason is picked for the buyer: it's data, and a default would skew it.
    expect(screen.getByLabelText('Return reason')).toHaveValue('')
    expect(screen.getByLabelText('Return reason')).toBeRequired()
    await user.click(screen.getByRole('button', { name: 'Record' }))
    expect(posts).toHaveLength(0)
    await user.selectOptions(screen.getByLabelText('Return reason'), 'DOES_NOT_MATCH_PHOTOS')
    await user.click(screen.getByRole('button', { name: 'Record' }))
    await user.click(screen.getByRole('button', { name: 'Confirm: record it' }))

    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0]).toEqual({ url: '/api/orgs/b1/deals/l1/returned', body: { reason: 'DOES_NOT_MATCH_PHOTOS', note: null } })
  })

  it('needs a note and a plain warning before calling a card not genuine', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    render(<OpenArrivalForm onSaved={vi.fn()} />)

    await openArrival(user)
    await user.click(screen.getByLabelText('Sending it back'))
    expect(screen.getByLabelText('Note')).not.toBeRequired()
    await user.selectOptions(screen.getByLabelText('Return reason'), 'NOT_GENUINE')
    expect(screen.getByLabelText('Note')).toBeRequired()
    await user.click(screen.getByRole('button', { name: 'Record' }))
    expect(screen.queryByRole('button', { name: /^Confirm/ })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Note'), 'wrong font on the back')
    await user.click(screen.getByRole('button', { name: 'Record' }))
    expect(screen.getByText(/they see it with your note, and Zach reviews it/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: "Confirm: it isn't genuine" }))

    await waitFor(() => expect(posts[0]).toEqual({ url: '/api/orgs/b1/deals/l1/returned', body: { reason: 'NOT_GENUINE', note: 'wrong font on the back' } }))
  })

  it('lets the admin record for the buyer, saying how it shipped', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    render(<ArrivalForm path="/api/admin/deals/l1?buyerOrgId=b1" onSaved={vi.fn()} noteRequired />)

    await user.click(screen.getByLabelText('Arrived as shown'))
    expect(screen.getByLabelText('Note')).toBeRequired()
    await user.type(screen.getByLabelText('Note'), 'own stamps')
    await user.click(screen.getByRole('button', { name: 'Record' }))
    await user.click(screen.getByRole('button', { name: 'Confirm: record it' }))
    await waitFor(() => expect(posts[0]).toEqual({ url: '/api/admin/deals/l1/received?buyerOrgId=b1', body: { note: 'own stamps' } }))
  })
})

describe('PaidForm', () => {
  it('records the price paid, prefilled with the offer, and asks once more', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    const onSaved = vi.fn()
    render(<PaidForm path="/api/orgs/b1/deals/l1" price={419.98} onSaved={onSaved} />)

    expect(screen.getByLabelText('Price paid')).toHaveValue('419.98')
    await user.click(screen.getByRole('button', { name: "I've paid" }))
    expect(posts).toHaveLength(0)
    expect(screen.getByText(/Record that you paid \$419\.98\?/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirm: record it' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(posts[0]).toEqual({ url: '/api/orgs/b1/deals/l1/paid', body: { pricePaid: 419.98, note: null } })
  })

  it('needs a reason when the amount paid differs from the offer', async () => {
    const user = userEvent.setup()
    const posts = stubApi([], null, () => respond(201, []))
    render(<PaidForm path="/api/orgs/b1/deals/l1" price={419.98} onSaved={vi.fn()} />)

    expect(screen.getByLabelText('Note')).not.toBeRequired()
    await user.clear(screen.getByLabelText('Price paid'))
    await user.type(screen.getByLabelText('Price paid'), '95')
    expect(screen.getByLabelText('Note')).toBeRequired()
    await user.click(screen.getByRole('button', { name: "I've paid" }))
    expect(screen.queryByRole('button', { name: 'Confirm: record it' })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Note'), 'agreed a discount')
    await user.click(screen.getByRole('button', { name: "I've paid" }))
    await user.click(screen.getByRole('button', { name: 'Confirm: record it' }))
    await waitFor(() => expect(posts[0]).toEqual({ url: '/api/orgs/b1/deals/l1/paid', body: { pricePaid: 95, note: 'agreed a discount' } }))
  })
})

describe('DealsPage', () => {
  const deal: Deal = {
    listingId: 'l1',
    card: { name: 'Umbreon VMAX', setName: 's', number: '1', language: 'en', edition: 'UNLIMITED', variant: 'NORMAL', label: 'x', distribution: {}, gradedAs: null },
    offer: offer(),
    photos: [{ side: 'FRONT', url: 'https://photos.example/f.jpg', fullUrl: 'https://photos.example/f-full.jpg' }],
    seller: { orgName: 'Sam' },
    acceptedAt: '2026-10-20T10:00:00Z',
    label: null,
    outcome: null,
    cancelled: null,
    payTo: null,
    progress: null,
  }

  const noOutcomes: BuyerOutcomeRecord = { graded: 0, withinOne: 0, verified: 0, resold: 0, kept: 0, outcomes: [] }

  /** Answers the deals and the org's outcome record by path; POSTs are recorded and answered with {@code after}. */
  function renderDeals(deals: Deal[], record: BuyerOutcomeRecord = noOutcomes, after: BuyerOutcomeRecord = record) {
    const posts: { url: string; body: unknown }[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (init?.method === 'POST') {
          posts.push({ url, body: JSON.parse(String(init.body)) })
          return Promise.resolve(respond(201, after))
        }
        return Promise.resolve(respond(200, url.endsWith('/deal-outcomes') ? (posts.length ? after : record) : deals))
      }),
    )
    render(
      <MemoryRouter initialEntries={['/orgs/b1/deals']}>
        <Routes>
          <Route path="/orgs/:orgId/deals" element={<DealsPage />} />
        </Routes>
      </MemoryRouter>,
    )
    return posts
  }

  it("shows the listed photos, and no arrival form before there's a label", async () => {
    renderDeals([deal])
    expect(await screen.findByRole('img', { name: 'Front photo' })).toBeInTheDocument()
    expect(screen.getByText(/Once the seller has shipped it/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record' })).not.toBeInTheDocument()
  })

  it('records the arrival once the card is labelled', async () => {
    renderDeals([
      { ...deal, label: { carrier: 'USPS', service: 'Ground', trackingCode: '94', labelUrl: 'https://l', insuredValue: 419.98, boughtAt: '2026-10-20T10:00:00Z', shipBy: '2026-10-23T10:00:00Z' } },
    ])
    expect(await screen.findByRole('button', { name: 'It arrived' })).toBeInTheDocument()
    expect(screen.getByText(/Pay them directly, within 7 days of it arriving as shown/)).toBeInTheDocument()
  })

  const labelled = { carrier: 'USPS', service: 'Ground', trackingCode: '94', labelUrl: 'https://l', insuredValue: 419.98, boughtAt: '2026-10-20T10:00:00Z', shipBy: '2026-10-23T10:00:00Z' }
  const waiting: DealProgress = {
    stage: 'SOLD', shippedAt: null, arrivedAt: null, payBy: null, paidAt: null, pricePaid: null, paidNote: null,
    confirmedAt: null, disputedAt: null, disputeNote: null, review: null, held: false, cardBackAt: null,
  }

  it('asks for "It arrived" only after the seller has shipped, and never tells the buyer to ship', async () => {
    renderDeals([{ ...deal, label: labelled, progress: waiting }])
    expect(await screen.findByText('Seller ships by Oct 23')).toBeInTheDocument()
    expect(screen.queryByText(/Ship it/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'It arrived' })).not.toBeInTheDocument()
    cleanup()
    renderDeals([{ ...deal, label: labelled, progress: { ...waiting, stage: 'SHIPPED', shippedAt: '2026-10-21T10:00:00Z' } }])
    expect(await screen.findByRole('button', { name: 'It arrived' })).toBeInTheDocument()
  })

  const progress: DealProgress = {
    stage: 'ARRIVED', shippedAt: null, arrivedAt: '2026-10-24T10:00:00Z', payBy: '2026-10-31T10:00:00Z', paidAt: null, pricePaid: null, paidNote: null,
    confirmedAt: null, disputedAt: null, disputeNote: null, review: null, held: false, cardBackAt: null,
  }
  const outcome = { type: 'RECEIVED' as const, pricePaid: null, note: null, returnedReason: null, inspectedGrade: null, recordedAt: '2026-10-24T10:00:00Z' }

  it('shows where to pay and by when, once the card arrived', async () => {
    renderDeals([{ ...deal, outcome, progress, payTo: { method: 'VENMO', handle: '@sam-cards' } }])
    expect(await screen.findByText('@sam-cards')).toBeInTheDocument()
    expect(screen.getByText('Venmo')).toBeInTheDocument()
    expect(screen.getByText('Oct 31')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: "I've paid" })).toBeInTheDocument()
  })

  it('stops asking once the buyer has paid, and shows what the seller said', async () => {
    renderDeals([
      {
        ...deal,
        outcome,
        progress: { ...progress, stage: 'PAID', paidAt: '2026-10-25T10:00:00Z', pricePaid: 419.98, disputedAt: '2026-10-26T10:00:00Z' },
        payTo: { method: 'VENMO', handle: '@sam-cards' },
      },
    ])
    expect(await screen.findByText(/You recorded paying \$419\.98/)).toBeInTheDocument()
    expect(screen.getByText(/saying they haven't been paid/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: "I've paid" })).not.toBeInTheDocument()
    expect(screen.queryByText('@sam-cards')).not.toBeInTheDocument()
  })

  it('says what to do with a card going back, as a warning', async () => {
    renderDeals([
      {
        ...deal,
        outcome: { ...outcome, type: 'RETURNED', returnedReason: 'DOES_NOT_MATCH_PHOTOS', note: 'crease' },
        progress: { ...progress, stage: 'RETURNED', arrivedAt: null, payBy: null },
      },
    ])
    expect(await screen.findByText("Returned: doesn't match its photos.")).toBeInTheDocument()
    expect(screen.getByText(/Send it back tracked, at your cost/).closest('.alert')).toHaveClass('alert-warning')
    expect(screen.queryByText(/hasn't said how to pay/)).not.toBeInTheDocument()
    expect(screen.queryByText('What happened to this card?')).not.toBeInTheDocument()
  })

  it('asks what happened to a card once it arrived, and records a grade', async () => {
    const user = userEvent.setup()
    const recorded = {
      id: 'd1', listingId: 'l1', offerId: 'offer1', cardItemId: 'card1', buyerOrgId: 'b1', kind: 'GRADED' as const, gradeOutcomeId: 'g1',
      grader: 'PSA' as const, certNumber: '12345678', grade: 8.5, gradeLabel: null, gradedOn: null, certStatus: 'UNVERIFIED' as const,
      lookupUrl: 'https://psa.example/12345678', resalePrice: null, note: null, recordedAt: '2026-11-08T10:00:00Z', earlier: 0,
    }
    const posts = renderDeals([{ ...deal, outcome, progress }], noOutcomes, { ...noOutcomes, graded: 1, withinOne: 1, outcomes: [recorded] })

    expect(await screen.findByText('What happened to this card?')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'I had it graded' }))
    await user.type(screen.getByRole('spinbutton', { name: 'Grade' }), '8.5')
    await user.type(screen.getByRole('textbox', { name: 'Cert number' }), '12345678')
    await user.click(screen.getByRole('button', { name: 'Record what happened' }))
    expect(posts).toHaveLength(0)
    expect(screen.getByText(/Record it as PSA 8\.5, cert 12345678\?/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirm: record what happened' }))

    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0].url).toContain('/api/orgs/b1/deals/l1/outcomes')
    expect(posts[0].body).toEqual({ kind: 'GRADED', grader: 'PSA', certNumber: '12345678', grade: 8.5, gradeLabel: null, gradedOn: null, note: null })
    expect(await screen.findByText(/You said it came back PSA 8\.5, cert 12345678: waiting for Zach to check the cert/)).toBeInTheDocument()
    expect(screen.getByText(/Of the 1 card you had graded/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Correct it' })).toBeInTheDocument()
  })

  it('needs a price for a card resold raw', async () => {
    const user = userEvent.setup()
    const posts = renderDeals([{ ...deal, outcome, progress }])
    await user.click(await screen.findByRole('radio', { name: 'I resold it raw' }))
    await user.type(screen.getByRole('textbox', { name: 'Sold for' }), '120.50')
    await user.click(screen.getByRole('button', { name: 'Record what happened' }))
    await user.click(screen.getByRole('button', { name: 'Confirm: record what happened' }))
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0].body).toEqual({ kind: 'RESOLD_RAW', resalePrice: 120.5, note: null })
  })
})

describe('DealsPage', () => {
  it('shows a cancelled deal with nothing left to record', async () => {
    const cancelled: Deal = {
      listingId: 'l1',
      card: { name: 'Leafeon', setName: 's', number: '1', language: 'en', edition: 'UNLIMITED', variant: 'NORMAL', label: 'x', distribution: {}, gradedAs: null },
      offer: offer(),
      photos: [],
      seller: { orgName: 'Sam' },
      acceptedAt: '2026-10-20T10:00:00Z',
      label: null,
      outcome: null,
      cancelled: { reason: 'seller sold it elsewhere', at: '2026-10-21T10:00:00Z' },
      payTo: null,
      progress: null,
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        Promise.resolve(respond(200, url.endsWith('/deal-outcomes') ? { graded: 0, withinOne: 0, verified: 0, resold: 0, kept: 0, outcomes: [] } : [cancelled])),
      ),
    )
    render(
      <MemoryRouter initialEntries={['/orgs/b1/deals']}>
        <Routes>
          <Route path="/orgs/:orgId/deals" element={<DealsPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText(/We cancelled this deal on .*: seller sold it elsewhere\. You don't pay for it/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try the label again' })).not.toBeInTheDocument()
  })
})

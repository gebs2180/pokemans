import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { Acceptance, TrackRecord } from '../api'
import { respond } from '../test/auth0'
import { OfferTrackRecordPage } from './OfferTrackRecordPage'

const acceptance = (band: string, over: Partial<Acceptance> = {}): Acceptance => ({
  band,
  listings: 0,
  open: 0,
  accepted: 0,
  withdrawn: 0,
  expired: 0,
  closed: 0,
  acceptanceRate: null,
  medianHoursToAccept: null,
  voided: 0,
  cancelled: 0,
  ...over,
})

const record: TrackRecord = {
  asOf: '2026-10-19T20:00:00Z',
  overall: acceptance('All', { listings: 7, open: 1, accepted: 4, withdrawn: 1, expired: 1, closed: 6, acceptanceRate: 0.6667, medianHoursToAccept: 3.5 }),
  byPriceBand: [acceptance('Under $50'), acceptance('$100 to $250', { listings: 7, accepted: 4, closed: 6, acceptanceRate: 0.6667 })],
  deliveries: {
    taken: 4,
    labels: 4,
    inTransit: 1,
    awaitingLabel: 0,
    received: 2,
    returned: 1,
    returnRate: 0.3333,
    returnedBy: { DOES_NOT_MATCH_PHOTOS: 1 },
    earlierModel: 0,
    medianHoursToArrival: 72,
  },
  prices: { compared: 2, belowOffer: 1, atOffer: 1, aboveOffer: 0, medianRatio: 0.75, totalPaid: 150, totalOffered: 200 },
  shipping: { labels: 4, postage: 20.4, insurance: 6, meanPerLabel: 6.6 },
  caveats: ['Sellers choose which offers to post.', 'Arrivals are recorded by the buyer.'],
}

describe('OfferTrackRecordPage', () => {
  it('shows every rate with the counts behind it, and the caveats', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(respond(200, record))))
    render(
      <MemoryRouter>
        <OfferTrackRecordPage />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: 'Offer track record' })).toBeInTheDocument()
    expect(screen.getByText('Sellers choose which offers to post.')).toBeInTheDocument()
    const all = screen.getByRole('row', { name: /^All/ })
    expect(within(all).getByText('66.7%')).toBeInTheDocument()
    expect(within(all).getByText('(4 of 6, too few to read)')).toBeInTheDocument()
    expect(within(all).getByText('3.5 h')).toBeInTheDocument()
    // A band with nothing closed says so instead of showing 0%.
    expect(within(screen.getByRole('row', { name: /^Under \$50/ })).getByText('– (none yet)')).toBeInTheDocument()

    const returned = screen.getByRole('row', { name: /^Returned/ })
    expect(within(returned).getByText('33.3%')).toBeInTheDocument()
    expect(within(returned).getByText(/doesn't match its photos: 1/)).toBeInTheDocument()
    expect(screen.getByText('3 days')).toBeInTheDocument()
    expect(screen.getByText('1 / 1 / 0')).toBeInTheDocument()
    expect(screen.getByText('$150.00 against $200.00')).toBeInTheDocument()
    expect(screen.getByText(/4 labels: \$20.40 postage and \$6.00 insurance, \$6.60 a card/)).toBeInTheDocument()
  })
})

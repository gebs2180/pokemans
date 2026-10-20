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
  ...over,
})

const record: TrackRecord = {
  asOf: '2026-10-19T20:00:00Z',
  overall: acceptance('All', { listings: 7, open: 1, accepted: 4, withdrawn: 1, expired: 1, closed: 6, acceptanceRate: 0.6667, medianHoursToAccept: 3.5 }),
  byPriceBand: [acceptance('Under $50'), acceptance('$100 to $250', { listings: 7, accepted: 4, closed: 6, acceptanceRate: 0.6667 })],
  grades: {
    source: 'BUYER_INSPECTION',
    inspected: 3,
    excluded: 1,
    withinOne: 1,
    withinOneShare: 0.3333,
    withinOneTarget: 0.8,
    belowStressed: 1,
    belowStressedShare: 0.3333,
    belowStressedTarget: 0.05,
    meanDifference: -2.17,
  },
  prices: { source: 'BUYER_INSPECTION', compared: 2, declined: 2, belowLadder: 1, atLadder: 1, aboveLadder: 0, medianRatio: 0.75, totalPaid: 150, totalLadder: 200 },
  caveats: ['Sellers choose which offers to post.', 'Inspected grades are self-reported by the buyer.'],
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

    const within1 = screen.getByRole('row', { name: /Within ±1/ })
    expect(within(within1).getByText('33.3%')).toBeInTheDocument()
    expect(within(within1).getByText('target ≥ 80%')).toBeInTheDocument()
    expect(screen.getByText(/1 left out/)).toBeInTheDocument()
    expect(screen.getByText('-2.17 grades')).toBeInTheDocument()
    expect(screen.getByText('1 / 1 / 0')).toBeInTheDocument()
    expect(screen.getByText('$150.00 against $200.00')).toBeInTheDocument()
  })
})

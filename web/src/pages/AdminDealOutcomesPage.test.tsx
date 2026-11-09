import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { AdminCalibration, AdminDealOutcomeRow, RecordedDealOutcome } from '../api'
import { respond } from '../test/auth0'
import { AdminDealOutcomesPage } from './AdminDealOutcomesPage'

const outcome: RecordedDealOutcome = {
  id: 'd1', listingId: 'l1', offerId: 'o1', cardItemId: 'c1', buyerOrgId: 'b1', kind: 'GRADED', gradeOutcomeId: 'g1', grader: 'PSA',
  certNumber: '12345678', grade: 6.5, gradeLabel: 'EX-MT', gradedOn: '2026-11-01', certStatus: 'VERIFIED', lookupUrl: 'https://www.psacard.com/cert/12345678',
  resalePrice: null, note: 'Surface scratch under the holo', recordedAt: '2026-11-08T10:00:00Z', earlier: 1,
}

const card = { name: 'Umbreon VMAX', setName: 'Evolving Skies', number: '215', language: 'en', edition: 'UNLIMITED', variant: 'NORMAL', label: 'x', distribution: {}, gradedAs: null }

const graded: AdminDealOutcomeRow = {
  listingId: 'l1', offerId: 'o1', buyerOrgId: 'b1', buyer: 'Card Shop', card, offerPrice: 419.98, confidence: 'B',
  predicted: { U: 0, '1': 0, '2': 0, '3': 0, '4': 0, '5': 0, '6': 0, '7': 0, '8': 0, '9': 0.5, '10': 0.5 },
  median: '9', stressShift: 2, stressedMedian: '7', outcome, returnedGrade: '6', withinOne: false, belowStressed: true,
}

const kept: AdminDealOutcomeRow = {
  ...graded, listingId: 'l2', card: { ...card, name: 'Leafeon VMAX' },
  outcome: { ...outcome, id: 'd2', listingId: 'l2', kind: 'KEPT', gradeOutcomeId: null, grader: null, certNumber: null, grade: null, gradeLabel: null, certStatus: null, lookupUrl: null, note: null, earlier: 0 },
  returnedGrade: null, withinOne: null, belowStressed: null,
}

const calibration: AdminCalibration = {
  all: { graded: 1, withinOne: 0, belowStressed: 1 },
  verified: { graded: 1, withinOne: 0, belowStressed: 1 },
  resold: 0,
  kept: 1,
  rows: [graded, kept],
}

describe('AdminDealOutcomesPage', () => {
  it('compares each returned grade with the prediction, against the targets', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(respond(200, calibration))))
    render(
      <MemoryRouter>
        <AdminDealOutcomesPage />
      </MemoryRouter>,
    )

    const row = await screen.findByRole('region', { name: 'Umbreon VMAX' })
    expect(within(row).getByText(/Predicted median/)).toHaveTextContent('Predicted median 9, stressed median 7 (shifted 2 down)')
    expect(within(row).getByText(/9: 50% · 10: 50%/)).toBeInTheDocument()
    expect(within(row).getByRole('link', { name: '12345678' })).toHaveAttribute('href', 'https://www.psacard.com/cert/12345678')
    expect(within(row).getByText('more than one off')).toHaveClass('warn')
    expect(within(row).getByText('below the stressed median')).toBeInTheDocument()
    expect(within(row).getByText(/corrects 1/)).toBeInTheDocument()

    const keptRow = screen.getByRole('region', { name: 'Leafeon VMAX' })
    expect(within(keptRow).getByText('Kept')).toBeInTheDocument()
    expect(within(keptRow).queryByText(/Counts as/)).not.toBeInTheDocument()

    const all = screen.getByRole('row', { name: /^All/ })
    expect(within(all).getByText('0 (0%)')).toHaveClass('warn')
    expect(within(all).getByText('1 (100%)')).toHaveClass('warn')
    expect(screen.getByText('Also 0 resold raw and 1 kept.')).toBeInTheDocument()
  })
})

import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { CardLine, RunDetail } from '../api'
import { respond } from '../test/auth0'
import { ReportsList } from './ReportsPanel'
import { StartRun } from './ValuationPage'
import { ValuationRunPage } from './ValuationRunPage'

const inputs = (id: string, name: string | null): CardLine['inputs'] => ({
  cardItemId: id,
  label: `${id} label`,
  lot: false,
  quantity: 1,
  identity: name ? { catalogCardId: 'cc', name, setName: 'Base Set', number: '4', language: 'en' } : null,
  assessment: null,
  graded: null,
  prices: null,
  copiesHeld: 1,
  provenance: { authFlagUnresolved: true, authState: 'AWAITING_VERIFICATION', custodyVerified: false, custodyState: 'LAPSED' },
  unvaluedReason: null,
})

const line = (id: string, over: Partial<CardLine>): CardLine => ({
  cardItemId: id,
  position: 0,
  status: 'VALUED',
  unvaluedReason: null,
  graded: false,
  inputs: inputs(id, 'Charizard'),
  fmv: 400,
  rv: 470,
  qsv: 300,
  stressedFmv: 200,
  flv: 120,
  confidence: 'B',
  eligible: true,
  exclusionReasons: [],
  contribution: 100,
  insuredRv: 470,
  insurerNote: null,
  flvGraded: null,
  flvRawForLift: null,
  gradingCost: null,
  lift: null,
  gradeFirstRank: null,
  trace: [{ subject: 'FMV', rule: 'sum p x net price', value: '400.00', detail: '' }],
  ...over,
})

const detail: RunDetail = {
  run: {
    id: 'run1',
    orgId: 'org1',
    collectionId: 'col1',
    collectionName: 'Pledge binder',
    asOf: '2026-10-18',
    parameterSetVersion: '1.1.0',
    gradingCompany: 'PSA',
    gradingCost: { fee: 25, shipping: 5, insurance: 2, timeToCashRate: 0.02 },
    inputsSha256: 'f'.repeat(64),
    cardCount: 3,
    valuedCount: 2,
    eligibleCount: 1,
    eligibleFlv: 120,
    collateralBase: 100,
    rawShare: 1,
    gradedShare: 0,
    alphaLow: 0.5,
    alphaHigh: 0.7,
    advanceLow: 50,
    advanceHigh: 70,
    capsApplied: false,
    
    totalRv: 940,
    note: null,
    createdAt: '2026-10-18T15:00:00Z',
  },
  cards: [
    line('a', { gradeFirstRank: 1, lift: 2.5, gradingCost: 40, flvGraded: 300, flvRawForLift: 160 }),
    line('b', { eligible: false, exclusionReasons: ['unresolved authenticity flag', 'custody not verified'], contribution: 0 }),
    line('c', {
      status: 'UNVALUED',
      unvaluedReason: "photographed lot: lots aren't valued",
      fmv: null,
      rv: null,
      qsv: null,
      flv: null,
      stressedFmv: null,
      confidence: null,
      eligible: false,
      insuredRv: 0,
      trace: [],
      inputs: inputs('c', null),
    }),
  ],
  portfolioTrace: [],
  confidenceMix: { B: 2 },
}

describe('ValuationRunPage', () => {
  it('shows the base, the suggested range, every exclusion with its reason, and the grade-first list', async () => {
    const fetch = vi.fn((url: string) => Promise.resolve(respond(200, url.endsWith('/reports') ? [] : detail)))
    vi.stubGlobal('fetch', fetch)
    render(
      <MemoryRouter initialEntries={['/admin/valuation-runs/run1']}>
        <Routes>
          <Route path="/admin/valuation-runs/:runId" element={<ValuationRunPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('Valuation of Pledge binder')).toBeInTheDocument()
    expect(screen.getByText('$50.00 – $70.00')).toBeInTheDocument()
    expect(screen.getByText(/the lender sets alpha/)).toBeInTheDocument()
    expect(screen.getByText(/No card needed capping/)).toBeInTheDocument()
    expect(
      screen.getByText('excluded: unresolved authenticity flag (awaiting verification); custody not verified (lapsed)'),
    ).toBeInTheDocument()
    expect(screen.getByText("not valued: photographed lot: lots aren't valued")).toBeInTheDocument()
    expect(screen.getByText('2.50')).toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Charizard (a label)' }))
    expect(screen.getByText(/sum p x net price/)).toBeInTheDocument()
  })

  it("sends the lender's alpha as a share, and only on a lender report", async () => {
    const user = userEvent.setup()
    const posts: unknown[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (init?.method === 'POST') posts.push(JSON.parse(String(init.body)))
        return Promise.resolve(respond(200, url.endsWith('/reports') ? [] : detail))
      }),
    )
    render(
      <MemoryRouter initialEntries={['/admin/valuation-runs/run1']}>
        <Routes>
          <Route path="/admin/valuation-runs/:runId" element={<ValuationRunPage />} />
        </Routes>
      </MemoryRouter>,
    )
    await user.type(await screen.findByLabelText("Lender's alpha (%)"), '60')
    await user.click(screen.getByRole('button', { name: 'Issue lender report' }))
    await user.click(screen.getByRole('button', { name: 'Issue insurer report' }))

    await waitFor(() =>
      expect(posts).toEqual([
        { type: 'LENDER', lenderAlpha: 0.6 },
        { type: 'INSURER', lenderAlpha: null },
      ]),
    )
  })
})

describe('StartRun', () => {
  it('starts a run with the grading cost the admin set', async () => {
    const user = userEvent.setup()
    const fetch = vi.fn(() => Promise.resolve(respond(201, { id: 'run9' })))
    vi.stubGlobal('fetch', fetch)
    const onStarted = vi.fn()
    render(
      <StartRun
        defaults={{
          parameterSet: '1.1.0',
          gradingCompany: 'PSA',
          gradingCost: { fee: 25, shipping: 5, insurance: 2, timeToCashRate: 0.02 },
        }}
        path="/api/admin/orgs/o/collections/c/valuation-runs"
        onStarted={onStarted}
      />,
    )
    const fee = screen.getByLabelText('Grading fee ($)')
    await user.clear(fee)
    await user.type(fee, '19')
    await user.click(screen.getByRole('button', { name: 'Run valuation' }))

    await waitFor(() => expect(onStarted).toHaveBeenCalledWith('run9'))
    const [, init] = fetch.mock.calls[0] as unknown as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toEqual({
      gradingCost: { fee: 19, shipping: 5, insurance: 2, timeToCashRate: 0.02 },
      note: null,
    })
  })
})

describe('ReportsList', () => {
  const report = {
    id: 'r1',
    runId: 'run1',
    collectionId: 'c',
    type: 'LENDER' as const,
    lenderAlpha: 0.6,
    templateVersion: '1',
    sha256: 'a'.repeat(64),
    sizeBytes: 1000,
    issuedAt: '2026-10-18T16:00:00Z',
  }

  it('opens the signed link in a new tab, and refuses one that is not a web address', async () => {
    const user = userEvent.setup()
    const tab = { location: { assign: vi.fn() }, close: vi.fn() }
    vi.stubGlobal(
      'open',
      vi.fn(() => tab),
    )
    const fetch = vi.fn(() => Promise.resolve(respond(200, { url: 'https://storage.example/report.pdf?sig=1' })))
    vi.stubGlobal('fetch', fetch)
    render(<ReportsList reports={[report]} downloadPath={(id) => `/api/orgs/o/reports/${id}/download`} />)

    expect(screen.getByText(/at the lender's alpha of 60%/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Lender collateral report' }))
    await waitFor(() => expect(tab.location.assign).toHaveBeenCalledWith('https://storage.example/report.pdf?sig=1'))

    fetch.mockImplementation(() => Promise.resolve(respond(200, { url: 'javascript:alert(1)' })))
    await user.click(screen.getByRole('button', { name: 'Lender collateral report' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('not a web address')
    expect(tab.close).toHaveBeenCalled()
  })
})

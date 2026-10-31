import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { CardCondition, CatalogHit, IdentifiedCard, OfferPreview, Prediction, ProvenanceView, ProviderCard } from '../api'
import { respond } from '../test/auth0'
import { searchFromLabel } from '../searchFromLabel'
import { AssessmentPanel, OfferPreviewPanel, Predictions, ReviewCardPage } from './ReviewCardPage'

const card: CardCondition = {
  cardId: 'card1',
  orgId: 'org1',
  label: 'Charizard',
  enabledProviders: [],
  photos: [],
  identification: null,
  requests: [],
  predictions: [],
  assessments: [],
}

describe('AssessmentPanel', () => {
  it('only allows saving a distribution that totals 100%', async () => {
    const user = userEvent.setup()
    render(<AssessmentPanel data={card} current={null} base="/api/admin/orgs/org1/cards/card1" onChange={() => {}} />)
    await user.click(screen.getByText('Adjust (saves a new version)'))
    const save = screen.getByRole('button', { name: 'Save version' })
    expect(save).toBeDisabled()

    const nine = screen.getByLabelText('Grade 9, percent')
    await user.clear(nine)
    await user.type(nine, '60')
    expect(save).toBeDisabled()

    const eight = screen.getByLabelText('Grade 8, percent')
    await user.clear(eight)
    await user.type(eight, '40')
    expect(save).toBeEnabled()

    await user.type(eight, '.5')
    expect(save).toBeDisabled()
  })
})

describe('Predictions', () => {
  const prediction = (details: Record<string, unknown>): Prediction => ({
    id: 'pr1', provider: 'XIMILAR', modelVersion: null, succeeded: true, overallGrade: 2.5, psaGrade: null,
    centering: 8, corners: 9, edges: 8, surface: 6, details, error: null, latencyMs: 1, createdAt: '2026-10-28T22:17:19Z',
  })

  it("shows the back's grades and a damage flag, which explain a low overall", () => {
    render(
      <Predictions
        data={{ ...card, predictions: [prediction({ backGrades: { final: 2, centering: 9, corners: 3, edges: 2.5, surface: 2 }, damaged: { name: 'Crease', prob: 0.91 } })] }}
        base="/b"
        onChange={() => {}}
      />,
    )
    const back = screen.getByText('XIMILAR · back').closest('tr')!
    expect(back).toHaveTextContent('2–32.52')
    expect(screen.getByText(/Damage check: Crease \(91% sure\)/)).toHaveTextContent('caps the overall grade')
  })

  it("doesn't show Ximilar's centering", () => {
    render(
      <Predictions
        data={{ ...card, predictions: [prediction({ frontCentering: { leftRight: '42/58', topBottom: '54/46' } })] }}
        base="/b"
        onChange={() => {}}
      />,
    )
    expect(screen.queryByText(/42\/58/)).not.toBeInTheDocument()
    expect(screen.getByText('XIMILAR').closest('tr')).toHaveTextContent('2.5––986')
  })

  it('shows measured centering per side and the grade it allows, or why a side was not measured', () => {
    const ours: Prediction = {
      ...prediction({
        frontCentering: { leftRight: '49.1/50.9', topBottom: '48.4/51.6' },
        backCentering: { unmeasured: 'no clear inner edge of the left border' },
        psaCenteringCap: 10,
      }),
      provider: 'RAWMARK_CENTERING', overallGrade: null, centering: 10, corners: null, edges: null, surface: null,
    }
    render(<Predictions data={{ ...card, predictions: [ours] }} base="/b" onChange={() => {}} />)
    expect(screen.getByText(/Centering: front L\/R 49.1\/50.9, T\/B 48.4\/51.6/)).toHaveTextContent(
      'back not measured (no clear inner edge of the left border) — allows PSA 10 at best',
    )
  })

  it('shows an OK damage check without a warning, and no back row when there are no back grades', () => {
    render(<Predictions data={{ ...card, predictions: [prediction({ damaged: { name: 'OK', prob: 0.88 } })] }} base="/b" onChange={() => {}} />)
    expect(screen.queryByText('XIMILAR · back')).not.toBeInTheDocument()
    expect(screen.getByText(/Damage check: OK/)).not.toHaveTextContent('caps')
  })
})

describe('AssessmentPanel close-up warning', () => {
  const holo: IdentifiedCard = {
    cardItemId: 'card1', catalogCardId: 'cat1', language: 'en', setName: 'Vivid Voltage', seriesName: null, setReleaseDate: '2020-11-13',
    number: '188', name: 'Pikachu VMAX', rarity: 'Rare Rainbow', edition: 'UNLIMITED', variant: 'HOLO', stamp: null, promo: false,
    authenticityRisk: 'NONE', imageUrl: null, identifiedSince: '2026-10-21T10:00:00Z',
  }

  async function pickA(data: CardCondition) {
    const user = userEvent.setup()
    render(<AssessmentPanel data={data} current={null} base="/b" onChange={() => {}} />)
    await user.click(screen.getByText('Adjust (saves a new version)'))
    await user.selectOptions(screen.getByLabelText('Confidence'), 'A')
  }

  it('warns that confidence A on a modern holo needs a close-up', async () => {
    await pickA({ ...card, identification: holo })
    expect(screen.getByRole('note')).toHaveTextContent('No passing close-up of this modern holo')
  })

  it("doesn't warn once there's a close-up", async () => {
    await pickA({ ...card, identification: holo, photos: [{ id: 'p', side: 'DETAIL', url: 'https://x/p', cropUrl: null, capturedAt: null }] })
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })

  it("doesn't warn for a WotC-era holo", async () => {
    await pickA({ ...card, identification: { ...holo, setReleaseDate: '1999-01-09' } })
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })
})

describe('searchFromLabel', () => {
  it("starts the search from the collector's own words", () => {
    expect(searchFromLabel('Charizard Base Set Unlimited 4/102')).toEqual({ name: 'Charizard', number: '4/102' })
    expect(searchFromLabel('Umbreon VMAX 215 / 203')).toEqual({ name: 'Umbreon', number: '215/203' })
    expect(searchFromLabel('Pikachu TG05/TG30')).toEqual({ name: 'Pikachu', number: 'TG05/TG30' })
    expect(searchFromLabel('Mew')).toEqual({ name: 'Mew', number: '' })
  })
})

describe('OfferPreviewPanel', () => {
  function stub(preview: OfferPreview) {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(respond(200, preview))))
  }

  it('shows the offer the seller would get, and how its confidence was set', async () => {
    stub({
      offered: true, refusal: null, price: 100.54, fmv: 160.68, confidence: 'B', buyerMargin: 0.13,
      why: [{ subject: 'confidence_base', rule: 'worse of photo and comps', value: 'B', detail: 'photo A, comps B' }],
    })
    render(<OfferPreviewPanel cardId="card1" />)
    expect(await screen.findByText(/Offer \$100.54 at confidence B, leaving the buyer 13%/)).toBeInTheDocument()
    expect(screen.getByText('worse of photo and comps · photo A, comps B')).toBeInTheDocument()
  })

  it('shows why a card gets no offer, with the steps that caused it', async () => {
    stub({
      offered: false, refusal: "There isn't enough sales data to make a firm offer on this card yet.", price: null, fmv: 300, confidence: 'D', buyerMargin: null,
      why: [
        { subject: 'confidence_penalty', rule: 'fallback sigma, drop 1', value: 'C', detail: '' },
        { subject: 'confidence', rule: 'derived price in play, drop one level', value: 'D', detail: 'derived grades [5, 6, 7]' },
      ],
    })
    render(<OfferPreviewPanel cardId="card1" />)
    expect(await screen.findByText(/No offer: There isn't enough sales data.*\(confidence D\)/)).toBeInTheDocument()
    expect(screen.getByText(/fallback sigma, drop 1/)).toBeInTheDocument()
    expect(screen.getByText(/derived grades \[5, 6, 7\]/)).toBeInTheDocument()
  })
})

describe('ReviewCardPage', () => {
  const identified: IdentifiedCard = {
    cardItemId: 'card1', catalogCardId: 'cat1', language: 'en', setName: 'Base Set', seriesName: 'Base', setReleaseDate: '1999-01-09',
    number: '4', name: 'Charizard', rarity: 'Rare Holo', edition: 'UNLIMITED', variant: 'HOLO', stamp: null, promo: false,
    authenticityRisk: 'VINTAGE_HOLO', imageUrl: null, identifiedSince: '2026-10-21T10:00:00Z',
  }
  const provenance = (authState: ProvenanceView['authState']): ProvenanceView => ({
    cardId: 'card1', authFlagUnresolved: authState !== 'NOT_FLAGGED', authState, authenticityRisk: null, verifyMethods: [], verifications: [],
    custodyVerified: false, custodyState: 'NOT_RECORDED', custodyVerifiedUntil: null, custody: null, custodyHistory: [],
  })
  const hits: CatalogHit[] = [
    { externalId: 'base1-4', number: '4', name: 'Charizard', setId: 'base1', imageUrl: null, setName: 'Base Set', setTotal: 102 },
    { externalId: 'base4-4', number: '4', name: 'Charizard', setId: 'base4', imageUrl: null, setName: 'Base Set 2', setTotal: 130 },
  ]
  const picked: ProviderCard = {
    externalId: 'base1-4', language: 'en', setId: 'base1', setName: 'Base Set', seriesName: 'Base', setReleaseDate: '1999-01-09',
    number: '4', name: 'Charizard', rarity: 'Rare Holo', editions: ['UNLIMITED'], variants: ['HOLO'], stamps: [], promo: false, imageUrl: null,
  }

  it('keeps one Authenticity panel when the identity is confirmed, and searches from the collector\'s words', async () => {
    const user = userEvent.setup()
    let confirmed = false
    const urls: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        urls.push(url)
        if (init?.method === 'POST') {
          confirmed = true
          return Promise.resolve(respond(201, {}))
        }
        if (url.endsWith('/condition')) {
          return Promise.resolve(respond(200, { ...card, label: 'Charizard Base Set 4/102', identification: confirmed ? identified : null }))
        }
        if (url.endsWith('/provenance')) return Promise.resolve(respond(200, provenance(confirmed ? 'AWAITING_VERIFICATION' : 'NOT_IDENTIFIED')))
        if (url.includes('/catalog/search')) return Promise.resolve(respond(200, hits))
        if (url.includes('/catalog/cards/')) return Promise.resolve(respond(200, picked))
        if (url.endsWith('/prices')) return Promise.resolve(respond(200, { catalogCardId: 'cat1', comps: [], tables: [], skipped: [] }))
        if (url.endsWith('/offer-preview')) return Promise.resolve(respond(200, { offered: false, refusal: "We haven't identified this card yet.", price: null, fmv: null, confidence: null, buyerMargin: null, why: [] }))
        return Promise.resolve(respond(200, { cardId: 'card1', current: null, recognition: false, reading: null, suggestions: [] }))
      }),
    )
    render(
      <MemoryRouter initialEntries={['/admin/review/org1/card1']}>
        <Routes>
          <Route path="/admin/review/:orgId/:cardId" element={<ReviewCardPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('Not identified yet: no offer until it is.')).toBeInTheDocument()
    expect(screen.getByLabelText('Name as printed')).toHaveValue('Charizard')
    expect(screen.getByLabelText('Number')).toHaveValue('4/102')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(urls.some((u) => u.includes('number=4%2F102'))).toBe(true)
    // Set names and sizes, so Base Set and Base Set 2 can't be mixed up.
    await user.click(await screen.findByRole('button', { name: /Charizard · Base Set · 4\/102/ }))
    await user.click(await screen.findByRole('button', { name: 'Confirm identity' }))

    expect(await screen.findByText(/High-risk card, not yet verified/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByRole('heading', { name: 'Authenticity' })).toHaveLength(1))
    expect(screen.queryByText('Not identified yet: no offer until it is.')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Offer preview' })).toBeInTheDocument()
  })
})

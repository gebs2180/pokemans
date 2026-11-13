import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CenteringCard, CenteringQueueItem, GraderStandard, StraightenedCard } from '../api'
import { respond } from '../test/auth0'
import { CenteringLabelCardPage, CenteringLabelsPage } from './CenteringLabelsPage'

const label = {
  id: 'l1', face: 'FRONT', photoId: 'f1', left: 2.5, right: 2.45, top: null, bottom: 3, hardCard: true, labelledBy: 'u1',
  createdAt: '2026-11-01T10:00:00Z', revealedAt: null,
} as const

const queueItem: CenteringQueueItem = {
  cardId: 'c1', orgId: 'o1', label: 'Giratina', orgName: 'Zach', identifiedAs: 'Giratina VSTAR · Crown Zenith GG69',
  front: { label: null, refused: true, photos: 2 },
  back: { label, refused: false, photos: 1 },
}

const card: CenteringCard = {
  cardId: 'c1', orgId: 'o1', label: 'Giratina', identifiedAs: null, front: null, back: null,
  photos: [
    { id: 'f1', face: 'FRONT', uploadedAt: '2026-11-01T10:00:00Z' },
    { id: 'f2', face: 'FRONT', uploadedAt: '2026-11-01T11:00:00Z' },
    { id: 'b1', face: 'BACK', uploadedAt: '2026-11-01T10:00:00Z' },
  ],
}

const system = { left: 2.0, right: 3.0, top: 2.5, bottom: 2.5, refused: null }

const standards: GraderStandard[] = [
  {
    grader: 'PSA', source: 'psa', lowest: '1',
    allowances: [
      { grade: '10', maxFrontShare: 55, maxBackShare: 75, unsure: null },
      { grade: '9', maxFrontShare: 60, maxBackShare: 90, unsure: null },
    ],
  },
  {
    grader: 'TAG', source: 'tag', lowest: '1',
    allowances: [{ grade: '10', maxFrontShare: 51, maxBackShare: 52, unsure: null }, { grade: '9', maxFrontShare: 60, maxBackShare: 75, unsure: 'back not stated' }],
  },
]

const straightened: StraightenedCard = {
  photoId: 'f2', pxPerMm: 20, width: 1260, height: 1760, image: 'data:image/jpeg;base64,AAAA', contrast: 'data:image/jpeg;base64,BBBB', refused: null,
}

function stubApi(routes: Record<string, unknown>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (init?.method === 'POST') {
      const body = JSON.parse(String(init.body))
      return respond(201, { label: { ...label, ...body, id: 'l2', revealedAt: '2026-11-13T10:00:00Z' }, system: routes.system ?? system })
    }
    const body = Object.entries(routes).find(([path]) => url.endsWith(path))?.[1]
    return body === undefined ? respond(404, { detail: 'Not found' }) : respond(200, body)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function posted(fetchMock: ReturnType<typeof stubApi>) {
  const call = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
  return JSON.parse(String(call?.[1]?.body))
}

function renderCard() {
  render(
    <MemoryRouter initialEntries={['/admin/centering/o1/c1']}>
      <Routes>
        <Route path="/admin/centering" element={<p>The queue</p>} />
        <Route path="/admin/centering/:orgId/:cardId" element={<CenteringLabelCardPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

const cardRoutes: Record<string, unknown> = {
  '/api/admin/centering-standards': standards,
  '/orgs/o1/cards/c1/centering-labels': card,
  '/photos/f2/straightened': straightened,
  '/photos/f1/straightened': { ...straightened, photoId: 'f1' },
  '/photos/b1/straightened': { ...straightened, photoId: 'b1' },
}

describe('CenteringLabelsPage', () => {
  beforeEach(() => vi.unstubAllGlobals())

  it('lists cards with what is labelled and what the measurer refused, never its numbers', async () => {
    stubApi({ '/api/admin/centering-labels': [queueItem] })
    render(
      <MemoryRouter>
        <CenteringLabelsPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText('Giratina VSTAR · Crown Zenith GG69')).toBeInTheDocument()
    expect(screen.getByText('Not labelled')).toBeInTheDocument()
    expect(screen.getByText(/2 photos · measurer refused/)).toBeInTheDocument()
    expect(screen.getByText('2.50 / 2.45 / – / 3.00')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Label' })).toHaveAttribute('href', '/admin/centering/o1/c1')
  })
})

describe('CenteringLabelCardPage', () => {
  beforeEach(() => vi.unstubAllGlobals())

  it('starts the lines at a fixed place on the newest photo and saves widths in mm', async () => {
    const user = userEvent.setup()
    const fetchMock = stubApi(cardRoutes)
    renderCard()

    await screen.findByRole('img', { name: /Straightened card/ })
    expect(screen.getAllByText('2.50 mm')).toHaveLength(4)
    await user.click(screen.getByRole('button', { name: 'Move right in' }))
    await user.click(screen.getByRole('button', { name: 'Done' }))

    await screen.findByRole('region', { name: "Your label and the measurer's" })
    expect(posted(fetchMock)).toEqual({ face: 'FRONT', photoId: 'f2', left: 2.5, right: 2.55, top: 2.5, bottom: 2.5, hardCard: false })
  })

  it("sends null for a side marked can't see it, and the hard-card tick", async () => {
    const user = userEvent.setup()
    const fetchMock = stubApi(cardRoutes)
    renderCard()

    await screen.findByRole('img', { name: /Straightened card/ })
    await user.click(screen.getAllByLabelText("Can't see it")[3])
    await user.click(screen.getByLabelText(/Hard card/))
    await user.click(screen.getByRole('button', { name: 'Done' }))

    await screen.findByRole('region', { name: "Your label and the measurer's" })
    expect(posted(fetchMock)).toMatchObject({ bottom: null, top: 2.5, hardCard: true })
  })

  it('drags the nearest line to the pointer and nudges it with the arrow keys', async () => {
    stubApi(cardRoutes)
    renderCard()

    const image = await screen.findByRole('img', { name: /Straightened card/ })
    image.getBoundingClientRect = () => ({ left: 0, top: 0, width: 630, height: 880 }) as DOMRect
    // A click 40 screen px in and halfway down is 80 card px in: nearest the left line, at 4 mm.
    fireEvent.pointerDown(image, { clientX: 40, clientY: 440, pointerId: 1 })
    fireEvent.pointerUp(image, { pointerId: 1 })
    expect(screen.getByText('4.00 mm')).toBeInTheDocument()

    fireEvent.keyDown(image, { key: 'ArrowRight' })
    expect(screen.getByText('4.05 mm')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Close-up of the left line' })).toBeInTheDocument()
  })

  it('shows contrast on request and labels the back with its own photo', async () => {
    const user = userEvent.setup()
    const fetchMock = stubApi(cardRoutes)
    renderCard()

    const image = await screen.findByRole('img', { name: /Straightened card/ })
    await user.click(screen.getByLabelText('Raise contrast'))
    expect(image.querySelector('image')).toHaveAttribute('href', straightened.contrast)

    await user.click(screen.getByRole('button', { name: 'Back' }))
    await waitFor(() => expect(screen.queryByLabelText('Photo')).not.toBeInTheDocument())
    await screen.findByRole('img', { name: /Straightened card/ })
    await user.click(screen.getByRole('button', { name: 'Done' }))
    await screen.findByRole('region', { name: "Your label and the measurer's" })
    expect(posted(fetchMock)).toMatchObject({ face: 'BACK', photoId: 'b1' })
  })

  it('says why a photo has no straightened card, and lets another be picked', async () => {
    const user = userEvent.setup()
    stubApi({ ...cardRoutes, '/photos/f2/straightened': { ...straightened, image: null, contrast: null, refused: "the card's left edge isn't straight" } })
    renderCard()

    expect(await screen.findByText(/can't be straightened: the card's left edge/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Done' })).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Photo'), 'f1')
    expect(await screen.findByRole('button', { name: 'Done' })).toBeInTheDocument()
  })

  it('starts from the saved label when there is one', async () => {
    stubApi({ ...cardRoutes, '/orgs/o1/cards/c1/centering-labels': { ...card, front: label } })
    renderCard()

    await screen.findByRole('img', { name: /Straightened card/ })
    expect(screen.getByText('2.45 mm')).toBeInTheDocument()
    expect(screen.getAllByLabelText("Can't see it")[2]).toBeChecked()
    expect(screen.getByLabelText(/Hard card/)).toBeChecked()
  })

  it('hides ratios and grades until Done, then shows the label beside the measurer and what each grader allows', async () => {
    const user = userEvent.setup()
    stubApi(cardRoutes)
    renderCard()

    await screen.findByRole('img', { name: /Straightened card/ })
    await user.click(screen.getByRole('button', { name: 'Move left in' }))
    expect(screen.queryByText(/\d+\.\d\/\d+\.\d/)).not.toBeInTheDocument()
    expect(screen.queryByText('PSA')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Done' }))
    const reveal = await screen.findByRole('region', { name: "Your label and the measurer's" })
    // Yours: 2.55 / 2.50 left/right; the measurer's 2.00 / 3.00.
    expect(within(reveal).getByText('50.5/49.5')).toBeInTheDocument()
    expect(within(reveal).getByText('40.0/60.0')).toBeInTheDocument()
    const psa = within(reveal).getByText('PSA').closest('tr')!
    expect(within(psa).getAllByRole('cell').map((c) => c.textContent)).toEqual(['PSA', '10', '9'])
    expect(within(reveal).getByText(/to confirm/)).toBeInTheDocument()

    await user.click(within(reveal).getByRole('button', { name: 'Label the back' }))
    expect(await screen.findByRole('button', { name: 'Done' })).toBeInTheDocument()
  })

  it("says when the measurer gave no reading, and shows only the reviewer's", async () => {
    const user = userEvent.setup()
    stubApi({ ...cardRoutes, system: { left: null, right: null, top: null, bottom: null, refused: 'no frame found' } })
    renderCard()

    await screen.findByRole('img', { name: /Straightened card/ })
    await user.click(screen.getByRole('button', { name: 'Done' }))
    const reveal = await screen.findByRole('region', { name: "Your label and the measurer's" })
    expect(within(reveal).getByText(/no reading of this photo: no frame found/)).toBeInTheDocument()
    expect(within(reveal).queryByText('Measurer')).not.toBeInTheDocument()
    expect(within(reveal).getByRole('link', { name: 'Back to the queue' })).toHaveAttribute('href', '/admin/centering')
  })
})

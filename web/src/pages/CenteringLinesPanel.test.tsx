import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GraderStandard, ReviewPhoto, StraightenedCard } from '../api'
import { respond } from '../test/auth0'
import { CenteringLinesPanel } from './CenteringLinesPanel'

const standards: GraderStandard[] = [
  {
    grader: 'PSA', source: 'psa', lowest: '1',
    allowances: [
      { grade: '10', maxFrontShare: 55, maxBackShare: 75, unsure: null },
      { grade: '9', maxFrontShare: 60, maxBackShare: 90, unsure: null },
    ],
  },
]

const straightened: StraightenedCard = {
  photoId: 'p1', pxPerMm: 20, width: 1260, height: 1760, image: 'data:image/jpeg;base64,AAAA', contrast: 'data:image/jpeg;base64,BBBB', refused: null,
}

const photos: ReviewPhoto[] = [
  { id: 'p1', side: 'FRONT', url: 'https://img.example/f.jpg', cropUrl: null, capturedAt: null },
  { id: 'p2', side: 'BACK', url: 'https://img.example/b.jpg', cropUrl: null, capturedAt: null },
]

function stubApi() {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    void _init
    const url = String(input)
    if (url.endsWith('/api/admin/centering-standards')) return respond(200, standards)
    const photo = /photos\/(\w+)\/straightened$/.exec(url)?.[1]
    return photo ? respond(200, { ...straightened, photoId: photo }) : respond(404, { detail: 'Not found' })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function renderPanel(list = photos) {
  render(
    <MemoryRouter>
      <CenteringLinesPanel orgId="o1" cardId="c1" photos={list} />
    </MemoryRouter>,
  )
}

describe('CenteringLinesPanel', () => {
  beforeEach(() => vi.unstubAllGlobals())

  it('shows no grade until the lines move, then what each grader allows per face', async () => {
    const user = userEvent.setup()
    const fetchMock = stubApi()
    renderPanel()

    await screen.findByRole('img', { name: /Straightened card/ })
    expect(await screen.findByText(/Move the lines to see/)).toBeInTheDocument()

    // 10 nudges of 1 px: 3.00 mm against 2.50, a 54.5 share, a PSA 10 on the front.
    for (let i = 0; i < 10; i++) await user.click(screen.getByRole('button', { name: 'Move left in' }))
    expect(screen.getByText('3.00 mm')).toBeInTheDocument()
    let psa = screen.getByText('PSA').closest('tr')!
    expect(within(psa).getAllByRole('cell').map((c) => c.textContent)).toEqual(['PSA', '10', '10'])

    // The back has its own lines and photo; a 60/40 back still fits 10 on PSA's 75.
    await user.click(screen.getByRole('button', { name: 'Back' }))
    await screen.findByRole('img', { name: /Straightened card/ })
    expect(screen.getAllByText('2.50 mm')).toHaveLength(4)
    for (let i = 0; i < 25; i++) await user.click(screen.getByRole('button', { name: 'Move top in' }))
    psa = screen.getByText('PSA').closest('tr')!
    expect(within(psa).getAllByRole('cell').map((c) => c.textContent)).toEqual(['PSA', '10', '10', '10'])

    expect(fetchMock.mock.calls.map(([u]) => String(u))).toContain('/api/admin/orgs/o1/cards/c1/photos/p2/straightened')
    expect(fetchMock.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
  })

  it('says why a photo has no straightened card', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
      String(input).endsWith('centering-standards') ? respond(200, standards) : respond(200, { ...straightened, image: null, contrast: null, refused: 'no card found' })))
    renderPanel()
    expect(await screen.findByText(/can't be straightened: no card found/)).toBeInTheDocument()
  })

  it('shows nothing without front or back photos', () => {
    stubApi()
    renderPanel([])
    expect(screen.queryByText('Centering lines')).not.toBeInTheDocument()
  })
})

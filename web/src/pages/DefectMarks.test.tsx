import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DefectMarkView, ReviewPhoto } from '../api'
import { respond } from '../test/auth0'
import { MarkedSurfacePanel } from './DefectMarks'

const photos: ReviewPhoto[] = [
  {
    id: 'p1',
    side: 'FRONT',
    url: 'https://img.example/f.jpg',
    cropUrl: null,
    capturedAt: null,
    wideUrl: 'https://img.example/f-wide.jpg',
    surfaceUrl: 'https://img.example/f-surface.jpg',
    detailUrl: 'https://img.example/f-detail.jpg',
  },
  { id: 'p2', side: 'BACK', url: 'https://img.example/b.jpg', cropUrl: null, capturedAt: null },
]

const scratch: DefectMarkView = {
  id: 'm1', photoId: 'p1', side: 'FRONT', x: 0.25, y: 0.5, type: 'SCRATCH', severity: 'MINOR', note: 'long line', createdAt: '2026-11-18T10:00:00Z',
}

let marks: DefectMarkView[]
let calls: { method: string; url: string; body?: unknown }[]

beforeEach(() => {
  marks = [scratch]
  calls = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).replace(/^.*\/api/, '/api')
      const method = init?.method ?? 'GET'
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      if (method === 'POST') {
        const made = { ...scratch, id: 'm2', type: 'WHITENING', severity: 'MAJOR', note: null }
        marks = [...marks, made as DefectMarkView]
        return respond(201, made)
      }
      if (method === 'DELETE') {
        marks = []
        return respond(204)
      }
      return respond(200, marks)
    }),
  )
})

function renderPanel(audience: 'reviewer' | 'collector') {
  return render(
    <MemoryRouter>
      <MarkedSurfacePanel orgId="o1" cardId="c1" photos={photos} audience={audience} />
    </MemoryRouter>,
  )
}

describe('MarkedSurfacePanel', () => {
  it('shows a collector the marks in plain words, read-only, and reads them from the collector path', async () => {
    const user = userEvent.setup()
    renderPanel('collector')
    await user.click(screen.getByText('Take a closer look'))
    expect(await screen.findByText(/Scratch, minor: long line/)).toBeInTheDocument()
    expect(screen.getByText(/don't change your offer/, { selector: 'p.muted' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark a defect' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove mark/ })).not.toBeInTheDocument()
    expect(calls[0]).toMatchObject({ method: 'GET', url: '/api/orgs/o1/cards/c1/defect-marks' })
  })

  it('offers the Detail image, and the hi-res crop for the other modes', async () => {
    const user = userEvent.setup()
    renderPanel('reviewer')
    await user.click(screen.getByText('Surface view'))
    const front = () => screen.getByRole('img', { name: 'Front crop, surface view' })
    expect(front()).toHaveAttribute('src', 'https://img.example/f-surface.jpg')

    await user.click(screen.getByRole('button', { name: 'Detail' }))
    expect(front()).toHaveAttribute('src', 'https://img.example/f-detail.jpg')
    expect(front().dataset.mode).toBe('detail')
    // No browser filter over the server's image, so no strength to set.
    expect(screen.getByLabelText('Filter strength')).toBeDisabled()
    // The back has no detail image yet: it keeps its photo.
    expect(screen.getByRole('img', { name: 'Back crop, surface view' })).toHaveAttribute('src', 'https://img.example/b.jpg')

    await user.click(screen.getByRole('button', { name: 'Photo' }))
    expect(front()).toHaveAttribute('src', 'https://img.example/f-surface.jpg')
  })

  it('has no Detail button when no photo has the server image', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <MarkedSurfacePanel orgId="o1" cardId="c1" photos={[photos[1]]} />
      </MemoryRouter>,
    )
    await user.click(screen.getByText('Surface view'))
    expect(screen.queryByRole('button', { name: 'Detail' })).not.toBeInTheDocument()
  })

  it('lets the admin tap a spot, say what it is, and save a mark', async () => {
    const user = userEvent.setup()
    renderPanel('reviewer')
    await user.click(screen.getByText('Surface view'))
    await screen.findByText(/Scratch, minor: long line/)

    await user.click(screen.getByRole('button', { name: 'Mark a defect' }))
    const crop = screen.getByRole('button', { name: /Mark a defect on Front crop/ })
    crop.getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 400 }) as DOMRect
    fireEvent.click(crop, { clientX: 50, clientY: 100, detail: 1 })

    await user.selectOptions(screen.getByLabelText('Kind'), 'WHITENING')
    await user.selectOptions(screen.getByLabelText('How bad'), 'MAJOR')
    await user.click(screen.getByRole('button', { name: 'Add mark' }))

    await waitFor(() => expect(screen.getByText(/Whitening, major/)).toBeInTheDocument())
    expect(calls.find((c) => c.method === 'POST')).toEqual({
      method: 'POST',
      url: '/api/admin/orgs/o1/cards/c1/defect-marks',
      body: { photoId: 'p1', x: 0.25, y: 0.25, type: 'WHITENING', severity: 'MAJOR', note: null },
    })
  })

  it('does not zoom while marking, and removes a mark', async () => {
    const user = userEvent.setup()
    renderPanel('reviewer')
    await user.click(screen.getByText('Surface view'))
    await screen.findByText(/Scratch, minor: long line/)

    await user.click(screen.getByRole('button', { name: 'Mark a defect' }))
    expect(screen.queryByRole('button', { name: /Zoom in on/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Done marking' }))
    expect(screen.getAllByRole('button', { name: /Zoom in on/ })).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: 'Remove mark 1' }))
    await waitFor(() => expect(screen.queryByText(/Scratch, minor/)).not.toBeInTheDocument())
    expect(calls.find((c) => c.method === 'DELETE')?.url).toBe('/api/admin/orgs/o1/cards/c1/defect-marks/m1')
  })

  it('still shows the surface view when the marks fail to load', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn(async () => respond(500, { detail: 'boom' })))
    renderPanel('collector')
    await user.click(screen.getByText('Take a closer look'))
    expect(screen.getAllByRole('img')).toHaveLength(2)
  })
})

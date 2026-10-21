import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { CardStatus, CardView, ContainerDetail, PhotoView } from '../api'
import { respond } from '../test/auth0'
import { CardPage } from './CardPage'
import { ContainerPage } from './ContainerPage'

const photo = (over: Partial<PhotoView>): PhotoView => ({
  id: 'p1',
  side: 'FRONT',
  status: 'PASSED',
  retakeReasons: [],
  width: 1536,
  height: 2048,
  blurScore: null,
  glareFraction: null,
  capturedAt: null,
  uploadedAt: '2026-10-21T10:00:00Z',
  current: true,
  url: 'https://photos.example/front.jpg',
  ...over,
})

const card = (over: Partial<CardView> = {}): CardView => ({
  id: 'card1',
  label: 'Pikachu VMAX',
  lot: false,
  quantity: 1,
  captureComplete: true,
  missingSides: [],
  photos: [
    photo({}),
    photo({ id: 'p2', side: 'BACK', url: 'https://photos.example/back.jpg' }),
    photo({
      id: 'p3',
      side: 'FRONT',
      status: 'RETAKE',
      current: false,
      retakeReasons: [{ code: 'DUPLICATE', message: 'This exact image was already uploaded for another card.' }],
    }),
  ],
  photosFrozen: null,
  ...over,
})

const status: CardStatus = {
  cardId: 'card1',
  stage: 'IDENTIFYING',
  identity: null,
  nextStep: 'We identify each card by hand from its photos, usually within a couple of days.',
  canOffer: false,
}

function renderCard(view: CardView, cardStatus: CardStatus = status) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url === '/api/me') {
        return Promise.resolve(
          respond(200, { subject: 's', issuer: null, roles: [], claimedRoles: [], mfa: false, memberships: [{ orgId: 'org1', orgName: 'Zach', orgType: 'COLLECTOR', role: 'COLLECTOR' }] }),
        )
      }
      if (url.endsWith('/status')) return Promise.resolve(respond(200, cardStatus))
      if (url.endsWith('/sale')) return Promise.resolve(respond(200, { offer: null, listing: null }))
      if (url.endsWith('/ship-address')) return Promise.resolve(new Response(null, { status: 200 }))
      if (url.endsWith('/provenance')) return Promise.resolve(respond(404, { detail: 'x' }))
      return Promise.resolve(respond(200, view))
    }),
  )
  render(
    <MemoryRouter initialEntries={['/orgs/org1/cards/card1']}>
      <Routes>
        <Route path="/orgs/:orgId/cards/:cardId" element={<CardPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('CardPage', () => {
  it('shows the photo in use for each side, and a failed retake without hiding it', async () => {
    renderCard(card())
    expect(await screen.findByRole('img', { name: 'Front photo you took' })).toHaveAttribute('src', 'https://photos.example/front.jpg')
    expect(screen.getByRole('img', { name: 'Back photo you took' })).toBeInTheDocument()
    expect(screen.getByText(/Your last try needs a retake \(the photo above is still the one used\)/)).toBeInTheDocument()
    expect(screen.getByText('The blue Poké Ball side.')).toBeInTheDocument()
    expect(screen.getByLabelText('Retake front photo')).toHaveAttribute('capture', 'environment')
    // A photo already taken can be picked too: the library input has no capture, so Android offers the gallery.
    expect(screen.getByLabelText('Choose a front photo')).not.toHaveAttribute('capture')
  })

  it('says what happens next, and who does it', async () => {
    renderCard(card(), { ...status, stage: 'ASSESSING', identity: 'Pikachu VMAX · Vivid Voltage 188 · EN · holo', nextStep: "We're reviewing its condition from the photos." })
    expect(await screen.findByText('Pikachu VMAX · Vivid Voltage 188 · EN · holo')).toBeInTheDocument()
    expect(screen.getByText('Waiting for condition review')).toBeInTheDocument()
    expect(screen.getAllByText("We're reviewing its condition from the photos.").length).toBeGreaterThan(0)
  })

  it('hides Retake once the card is posted or sold', async () => {
    renderCard(card({ photosFrozen: 'This card is sold, so its photos stay as the buyer saw them.' }))
    expect(await screen.findByText('This card is sold, so its photos stay as the buyer saw them.')).toBeInTheDocument()
    expect(screen.queryByText('Retake')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Retake/)).not.toBeInTheDocument()
  })
})

describe('ContainerPage', () => {
  const detail: ContainerDetail = {
    id: 'c1',
    name: 'Binder',
    createdAt: '2026-10-21T10:00:00Z',
    cards: [card(), { ...card(), id: 'card2', label: 'Umbreon', captureComplete: false, missingSides: ['BACK'] }],
  }

  function renderCollection(posts: unknown[] = []) {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (init?.method === 'POST') {
          posts.push(JSON.parse(String(init.body)))
          return Promise.resolve(respond(201, { ...card(), id: 'new1', label: 'Mew 151/165' }))
        }
        if (url.endsWith('/status')) {
          return Promise.resolve(respond(200, [{ ...status, stage: 'SOLD', identity: 'Pikachu VMAX · Vivid Voltage 188 · EN' }]))
        }
        return Promise.resolve(respond(200, detail))
      }),
    )
    render(
      <MemoryRouter initialEntries={['/orgs/org1/collections/c1']}>
        <Routes>
          <Route path="/orgs/:orgId/:kind/:containerId" element={<ContainerPage />} />
          <Route path="/orgs/:orgId/cards/:cardId" element={<p>Card page</p>} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it("shows each card's stage and identity, not just its photos", async () => {
    renderCollection()
    expect(await screen.findByText('Sold')).toBeInTheDocument()
    expect(screen.getByText('Pikachu VMAX · Vivid Voltage 188 · EN')).toBeInTheDocument()
    expect(screen.getByText('Needs back')).toBeInTheDocument()
  })

  it('goes straight to a new card to photograph it', async () => {
    const user = userEvent.setup()
    const posts: unknown[] = []
    renderCollection(posts)
    await user.type(await screen.findByLabelText('Card name and number'), 'Mew 151/165')
    expect(screen.getByLabelText('Lot of cheaper cards')).not.toBeChecked()
    await user.click(screen.getByRole('button', { name: 'Add card' }))
    expect(await screen.findByText('Card page')).toBeInTheDocument()
    expect(posts).toEqual([{ label: 'Mew 151/165', lot: false, quantity: 1 }])
  })
})

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { CardStatus, CardView, ContainerDetail, PhotoView } from '../api'
import { respond } from '../test/auth0'
import { CardPage, LikelyGrade } from './CardPage'
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
  condition: null,
  deadline: null,
}

function renderCard(view: CardView, cardStatus: CardStatus = status, orgType = 'COLLECTOR') {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url === '/api/me') {
        return Promise.resolve(
          respond(200, { subject: 's', issuer: null, roles: [], claimedRoles: [], mfa: false, memberships: [{ orgId: 'org1', orgName: 'Zach', orgType, role: orgType }] }),
        )
      }
      if (url.endsWith('/status')) return Promise.resolve(respond(200, cardStatus))
      if (url.endsWith('/sale')) return Promise.resolve(respond(200, { offer: null, listing: null }))
      if (url.endsWith('/ship-address')) return Promise.resolve(new Response(null, { status: 200 }))
      if (url.endsWith('/provenance')) return Promise.resolve(respond(404, { detail: 'x' }))
      if (url.endsWith('/outcomes')) return Promise.resolve(respond(200, []))
      if (url.endsWith('/photos')) return Promise.resolve(respond(400, { detail: 'Photos must be JPEG or PNG' }))
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

  it('shows our likely grade and what holds it back, as an estimate', async () => {
    renderCard(card(), {
      ...status,
      stage: 'READY',
      condition: { low: 7, high: 9, mostLikely: 8, mayBeUngradable: false, holdingBack: ['surface', 'centering'], centering: null },
    })
    expect(await screen.findByText('PSA 7–9, most likely 8')).toBeInTheDocument()
    expect(screen.getByText('surface and centering', { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/not a grade/)).toBeInTheDocument()
    expect(screen.queryByText(/may not be gradable/)).not.toBeInTheDocument()
    expect(screen.queryByText(/measured from your photos/)).not.toBeInTheDocument()
  })

  it('shows our measured centering per side, leaving out a side that was not measured', async () => {
    const front = { leftRight: '49/51', topBottom: '48/52' }
    renderCard(card(), {
      ...status,
      stage: 'READY',
      condition: { low: 7, high: 9, mostLikely: 8, mayBeUngradable: false, holdingBack: [], centering: { front, back: null } },
    })
    const line = (await screen.findByText(/measured from your photos/)).closest('p')!
    expect(line).toHaveTextContent('front 49/51 left-right, 48/52 top-bottom')
    expect(line).not.toHaveTextContent('back')
  })

  it('shows both sides of a measurement before there is a likely grade', async () => {
    const side = { leftRight: '52/48', topBottom: '46/54' }
    renderCard(card(), {
      ...status,
      stage: 'ASSESSING',
      condition: { low: 0, high: 0, mostLikely: 0, mayBeUngradable: false, holdingBack: [], centering: { front: side, back: side } },
    })
    const line = (await screen.findByText(/measured from your photos/)).closest('p')!
    expect(line).toHaveTextContent('front 52/48 left-right, 46/54 top-bottom · back 52/48 left-right, 46/54 top-bottom')
    expect(screen.queryByText(/Likely grade/)).not.toBeInTheDocument()
    expect(screen.queryByText(/not a grade/)).not.toBeInTheDocument()
  })

  it('shows a failed upload in the panel of the photo it was for', async () => {
    const user = userEvent.setup({ applyAccept: false })
    renderCard(card())
    const input = await screen.findByLabelText('Choose a back photo')
    await user.upload(input, new File(['x'], 'back.heic', { type: 'image/heic' }))

    const back = screen.getByRole('region', { name: 'Back photo' })
    expect(await within(back).findByRole('alert')).toHaveTextContent('Photos must be JPEG or PNG')
  })

  it("doesn't count attempts when no photo has passed", async () => {
    renderCard(card({ captureComplete: false, missingSides: ['FRONT'], photos: [photo({ status: 'RETAKE', current: false }), photo({ id: 'p2', status: 'RETAKE', current: false })] }))
    expect(await screen.findByText('Retake needed:')).toBeInTheDocument()
    expect(screen.queryByText(/attempts, all kept/)).not.toBeInTheDocument()
  })

  it('shows a rejected photo beside its reason, and says how to get a good one before the first try', async () => {
    renderCard(card({ captureComplete: false, missingSides: ['FRONT', 'BACK'], photos: [] }))
    expect(await screen.findByText(/For a good first shot/)).toBeInTheDocument()
    expect(screen.getAllByText('No photo yet')).toHaveLength(3)
  })

  it('shows the photo that failed where no photo passed', async () => {
    renderCard(card({ captureComplete: false, missingSides: ['FRONT', 'BACK'], photos: [photo({ status: 'RETAKE', current: false })] }))
    expect(await screen.findByRole('img', { name: "Front photo that didn't pass" })).toHaveAttribute('src', 'https://photos.example/front.jpg')
    expect(screen.queryByRole('img', { name: 'Front photo you took' })).not.toBeInTheDocument()
    expect(screen.queryByText(/For a good first shot/)).not.toBeInTheDocument()
  })

  it('shows the failed try beside its reason while an earlier photo stays in use', async () => {
    renderCard(card())
    const front = await screen.findByRole('region', { name: 'Front photo' })
    expect(await within(front).findByRole('img', { name: 'Front photo you took' })).toBeInTheDocument()
    expect(within(front).getByRole('img', { name: "Front photo that didn't pass" })).toBeInTheDocument()
  })

  it('says it when a failed photo cannot be shown', async () => {
    renderCard(card({ captureComplete: false, missingSides: ['FRONT', 'BACK'], photos: [photo({ status: 'RETAKE', current: false, url: null })] }))
    expect(await screen.findByText("Can't show this photo")).toBeInTheDocument()
  })

  it('shows the deadline as a date', async () => {
    renderCard(card(), { ...status, stage: 'SOLD', deadline: '2026-11-05T12:00:00Z' })
    expect(await screen.findByText(/^Ship by Nov 5/)).toBeInTheDocument()
  })

  it('offers a way to say something is wrong', async () => {
    renderCard(card())
    expect(await screen.findByRole('link', { name: 'Tell us' })).toHaveAttribute('href', '/how-it-works#contact')
  })

  it('says the next step once, in the offer panel', async () => {
    renderCard(card(), { ...status, stage: 'PRICING', nextStep: "We don't have enough sold prices for this card yet." })
    expect(await screen.findByText('Waiting for prices')).toBeInTheDocument()
    expect(await screen.findAllByText("We don't have enough sold prices for this card yet.")).toHaveLength(1)
  })

  it('asks contributors for raw photos first, and keeps selling out of the way', async () => {
    renderCard(card({ captureComplete: false, missingSides: ['FRONT', 'BACK'], photos: [] }), status, 'CONTRIBUTOR')
    expect(await screen.findByText(/from before it was graded: the raw card, out of the slab/)).toBeInTheDocument()
    expect(screen.getByText('Selling this card instead?')).toBeInTheDocument()
    expect(screen.queryByText('Waiting for us to identify it')).not.toBeInTheDocument()
    // Choosing an old photo is their main button; a seller's is the camera.
    expect(screen.getByLabelText('Choose a front photo').closest('label')).not.toHaveClass('secondary')
    expect(screen.getByLabelText('Take front photo').closest('label')).toHaveClass('secondary')
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

  const charizard = { externalId: 'base1-4', name: 'Charizard', setName: 'Base Set', number: '4/102', year: 1999, language: 'en', imageUrl: 'https://img/base1/4' }
  const blastoise = { ...charizard, externalId: 'base1-2', name: 'Blastoise', number: '2/102', imageUrl: null }
  const charizardPrints = { ...charizard, editions: ['UNLIMITED', 'FIRST_EDITION', 'SHADOWLESS'], variants: ['HOLO'], stamps: [], promo: false }
  const searches: string[] = []

  function renderCollection(posts: unknown[] = [], statusFails = false, underMinimum = false) {
    searches.length = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (init?.method === 'POST') {
          posts.push({ url: url.replace(/^.*\/api/, '/api'), ...JSON.parse(String(init.body)) })
          return Promise.resolve(respond(201, { ...card(), id: 'new1', label: 'Mew 151/165' }))
        }
        if (url.includes('/api/catalog/search')) {
          searches.push(new URL(url, 'http://x').searchParams.get('q') ?? '')
          return Promise.resolve(respond(200, [charizard, blastoise]))
        }
        if (url.includes('/api/catalog/cards/base1-4')) return Promise.resolve(respond(200, charizardPrints))
        if (url.includes('/api/catalog/price-hint')) return Promise.resolve(respond(200, { underMinimum, minFmv: 25 }))
        if (url.endsWith('/status')) {
          if (statusFails) return Promise.resolve(respond(500, { detail: 'Server error' }))
          return Promise.resolve(respond(200, [{ ...status, stage: 'SOLD', identity: 'Pikachu VMAX · Vivid Voltage 188 · EN', deadline: '2026-11-05T12:00:00Z' }]))
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
    expect(screen.getByText(/^Ship by Nov 5/)).toBeInTheDocument()
  })

  it("says so when it can't load where each card is, instead of dropping the stages", async () => {
    // Review finding 48: a failed status call used to leave every card without its stage, silently.
    renderCollection([], true)
    expect(await screen.findByText(/Couldn't load where each card is/)).toBeInTheDocument()
  })

  it('picks a card from the catalog by keyboard and stores the pick as a proposal', async () => {
    const user = userEvent.setup()
    const posts: unknown[] = []
    renderCollection(posts)
    const box = await screen.findByRole('combobox', { name: 'Find your card' })
    // Lots never get an offer, so a seller isn't asked about them.
    expect(screen.queryByLabelText('Lot of cheaper cards')).not.toBeInTheDocument()
    await user.type(box, 'charizard 4/102')
    const list = await screen.findByRole('listbox', { name: 'Matching cards' })
    const options = within(list).getAllByRole('option')
    expect(options).toHaveLength(2)
    expect(options[0]).toHaveTextContent('CharizardBase Set · 4/102 · 1999 · English')
    expect(searches.at(-1)).toBe('charizard 4/102')

    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowUp}')
    expect(box).toHaveAttribute('aria-activedescendant', options[0].id)
    expect(options[0]).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{Enter}')

    // Three editions: asked, with nothing chosen for them. One finish: taken, not asked.
    const edition = await screen.findByRole('group', { name: 'Edition' })
    expect(within(edition).getByRole('radio', { name: 'Not sure' })).toBeChecked()
    expect(screen.queryByRole('group', { name: 'Finish' })).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Stamp' })).not.toBeInTheDocument()
    await user.click(within(edition).getByRole('radio', { name: 'Shadowless' }))

    await user.click(screen.getByRole('button', { name: 'Add card' }))
    expect(await screen.findByText('Card page')).toBeInTheDocument()
    expect(posts).toEqual([
      { url: '/api/orgs/org1/collections/c1/cards', label: 'Charizard · Base Set 4/102 · Shadowless · Holo', lot: false, quantity: 1 },
      { url: '/api/orgs/org1/cards/new1/proposed-identity', externalId: 'base1-4', language: 'en', edition: 'SHADOWLESS', variant: 'HOLO', stamp: null, stampUnsure: false },
    ])
  })

  it('leaves an edition the collector is unsure of for review, and asks for a pick before adding', async () => {
    const user = userEvent.setup()
    const posts: unknown[] = []
    renderCollection(posts)
    await user.click(await screen.findByRole('button', { name: 'Add card' }))
    expect(await screen.findByText(/Choose your card from the list/)).toBeInTheDocument()
    expect(posts).toEqual([])

    await user.type(screen.getByRole('combobox', { name: 'Find your card' }), 'charizard')
    await user.click(await screen.findByRole('option', { name: /Charizard/ }))
    expect(await screen.findByText(/Not sure is fine/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add card' }))
    await screen.findByText('Card page')
    expect(posts[1]).toMatchObject({ externalId: 'base1-4', edition: null, variant: 'HOLO' })
  })

  it('says early when a picked card is clearly under the minimum, without refusing it', async () => {
    const user = userEvent.setup()
    renderCollection([], false, true)
    await user.type(await screen.findByRole('combobox', { name: 'Find your card' }), 'charizard')
    await user.click(await screen.findByRole('option', { name: /Charizard/ }))
    // No hint until the print is known.
    await screen.findByRole('group', { name: 'Edition' })
    expect(screen.queryByText(/whatever its condition/)).not.toBeInTheDocument()
    await user.click(within(screen.getByRole('group', { name: 'Edition' })).getByRole('radio', { name: 'Unlimited' }))
    expect(await screen.findByText(/usually sells for under our \$25 minimum, whatever its condition/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add card' })).toBeEnabled()
  })

  it("lets a collector describe a card they can't find, as before", async () => {
    const user = userEvent.setup()
    const posts: unknown[] = []
    renderCollection(posts)
    await user.type(await screen.findByRole('combobox', { name: 'Find your card' }), 'Mew')
    await user.click(screen.getByRole('button', { name: "Can't find it? Describe it" }))
    // What they typed carries over.
    expect(screen.getByLabelText('Describe your card')).toHaveValue('Mew')
    await user.type(screen.getByLabelText('Describe your card'), ' 151/165')
    await user.click(screen.getByRole('button', { name: 'Add card' }))
    expect(await screen.findByText('Card page')).toBeInTheDocument()
    expect(posts).toEqual([{ url: '/api/orgs/org1/collections/c1/cards', label: 'Mew 151/165', lot: false, quantity: 1 }])
  })
})

describe('LikelyGrade', () => {
  it('still warns about a card with no grade but a chance of being ungradable', () => {
    render(<LikelyGrade condition={{ low: 0, high: 0, mostLikely: 0, mayBeUngradable: true, holdingBack: [], centering: null }} />)
    expect(screen.getByText(/may not be gradable/)).toBeInTheDocument()
  })

  it('shows nothing with no grade, no measurement and no doubt', () => {
    const { container } = render(<LikelyGrade condition={{ low: 0, high: 0, mostLikely: 0, mayBeUngradable: false, holdingBack: [], centering: null }} />)
    expect(container).toBeEmptyDOMElement()
  })
})

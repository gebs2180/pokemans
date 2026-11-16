import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { IdentifyView, IdentityProposal, SuggestedCard } from '../api'
import { respond } from '../test/auth0'
import { IdentifyPanel } from './IdentifyPanel'

const charizard = { externalId: 'base1-4', name: 'Charizard', setName: 'Base Set', number: '4/102', year: 1999, language: 'en', imageUrl: 'https://img/base1/4' }
const charizardPrints = { ...charizard, editions: ['UNLIMITED', 'FIRST_EDITION', 'SHADOWLESS'] as const, variants: ['HOLO'] as const, stamps: [], promo: false }
const blastoise = { ...charizard, externalId: 'base1-2', name: 'Blastoise', number: '2/102', imageUrl: null }

const suggestion = (over: Partial<SuggestedCard> = {}): SuggestedCard => ({
  suggestionId: 's1',
  card: { ...charizardPrints, editions: [...charizardPrints.editions], variants: [...charizardPrints.variants] },
  edition: 'SHADOWLESS',
  variant: 'HOLO',
  confidence: 0.92,
  ...over,
})

const view = (over: Partial<IdentifyView>): IdentifyView => ({
  step: 'PHOTOS',
  why: null,
  readingId: null,
  readQuery: null,
  readLanguage: null,
  suggestions: [],
  proposal: null,
  ...over,
})

const proposal = (over: Partial<IdentityProposal> = {}): IdentityProposal => ({
  id: 'p1',
  source: 'SUGGESTION',
  provider: 'TCGDEX',
  externalId: 'base1-4',
  language: 'en',
  name: 'Charizard',
  setName: 'Base Set',
  number: '4/102',
  setReleaseDate: '1999-01-09',
  imageUrl: null,
  edition: 'SHADOWLESS',
  variant: 'HOLO',
  stamp: null,
  stampUnsure: false,
  description: null,
  readingId: 'r1',
  suggestionId: 's1',
  proposedBy: 'u1',
  createdAt: '2026-11-15T10:00:00Z',
  ...over,
})

/**
 * Serves the identify step from {@code steps} in turn (the last one repeats), stores each answer, and
 * moves to PROPOSED once one is stored, as the server does.
 */
function renderPanel(steps: IdentifyView[], { underMinimum = false } = {}) {
  const posts: unknown[] = []
  const identifies: string[] = []
  const searches: string[] = []
  let answered: IdentityProposal | null = null
  const onAnswer = vi.fn()
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        const body = JSON.parse(String(init.body)) as Record<string, unknown>
        posts.push({ url: url.replace(/^.*\/api/, '/api'), ...body })
        answered =
          body.source === 'DESCRIBED'
            ? proposal({ source: 'DESCRIBED', provider: null, externalId: null, language: null, name: null, setName: null, number: null, edition: null, variant: null, description: String(body.description), suggestionId: null })
            : proposal({ source: body.source as IdentityProposal['source'], edition: (body.edition ?? null) as IdentityProposal['edition'] })
        return Promise.resolve(respond(201, answered))
      }
      if (url.includes('/identify?')) {
        identifies.push(url.replace(/^.*\/api/, '/api'))
        if (answered && url.endsWith('again=false')) return Promise.resolve(respond(200, view({ step: 'PROPOSED', proposal: answered })))
        const next = steps.length > 1 ? steps.shift()! : steps[0]
        return Promise.resolve(respond(200, next))
      }
      if (url.includes('/api/catalog/search')) {
        searches.push(new URL(url, 'http://x').searchParams.get('q') ?? '')
        return Promise.resolve(respond(200, [charizard, blastoise]))
      }
      if (url.includes('/api/catalog/cards/base1-4')) return Promise.resolve(respond(200, charizardPrints))
      if (url.includes('/api/catalog/price-hint')) return Promise.resolve(respond(200, { underMinimum, minFmv: 25 }))
      return Promise.resolve(respond(404, { detail: 'x' }))
    }),
  )
  render(<IdentifyPanel orgId="org1" cardId="card1" photoVersion={0} onAnswer={onAnswer} />)
  return { posts, identifies, searches, onAnswer }
}

describe('IdentifyPanel', () => {
  it('asks for the photos first, waits for the reading, then confirms the suggestion with one tap', async () => {
    const user = userEvent.setup()
    const { posts, onAnswer } = renderPanel([
      view({ step: 'READING', readingId: 'r1' }),
      view({ step: 'SUGGEST', readingId: 'r1', readQuery: 'Charizard 4/102', suggestions: [suggestion(), suggestion({ suggestionId: 's2', card: { ...blastoise, editions: ['UNLIMITED'], variants: ['HOLO'], stamps: [], promo: false }, edition: null })] }),
    ])
    expect(await screen.findByText('Reading your card from the photos…')).toBeInTheDocument()
    // Polled until the reading is done.
    expect(await screen.findByText('Is this your card?', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Charizard, from the catalog' })).toHaveAttribute('src', 'https://img/base1/4/low.webp')
    // Three editions in the catalog: asked, starting from what the reader saw. One finish: not asked.
    const edition = screen.getByRole('group', { name: 'Edition' })
    expect(within(edition).getByRole('radio', { name: 'Shadowless' })).toBeChecked()
    expect(screen.queryByRole('group', { name: 'Finish' })).not.toBeInTheDocument()
    expect(screen.getByText('Or is it one of these?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Blastoise/ })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Yes, this is my card' }))
    expect(await screen.findByText(/You said it's/)).toBeInTheDocument()
    expect(posts).toEqual([
      { url: '/api/orgs/org1/cards/card1/proposed-identity', source: 'SUGGESTION', suggestionId: 's1', edition: 'SHADOWLESS', variant: 'HOLO', stamp: null, stampUnsure: false },
    ])
    expect(onAnswer).toHaveBeenCalledOnce()
    expect(screen.getByText(/We'll check it against your photos/)).toBeInTheDocument()
  }, 10000)

  it('says what the photos are for before they are taken', async () => {
    renderPanel([view({ step: 'PHOTOS' })])
    expect(await screen.findByText(/we'll work out which card it is from them/)).toBeInTheDocument()
  })

  it('shows nothing once the card is identified', async () => {
    const { identifies } = renderPanel([view({ step: 'IDENTIFIED' })])
    await vi.waitFor(() => expect(identifies).toHaveLength(1))
    expect(screen.queryByRole('region', { name: 'Your card' })).not.toBeInTheDocument()
  })

  it('"Not my card" goes to the search, started from what the reader saw', async () => {
    const user = userEvent.setup()
    const { posts, searches } = renderPanel([view({ step: 'SUGGEST', readingId: 'r1', readQuery: 'Charizard 4/102', readLanguage: 'en', suggestions: [suggestion({ card: { ...blastoise, editions: ['UNLIMITED'], variants: ['HOLO'], stamps: [], promo: false } })] })])
    await user.click(await screen.findByRole('button', { name: 'Not my card' }))
    const box = screen.getByRole('combobox', { name: 'Find your card' })
    expect(box).toHaveValue('Charizard 4/102')
    await vi.waitFor(() => expect(searches.at(-1)).toBe('Charizard 4/102'))
    await user.click(await screen.findByRole('option', { name: /Charizard/ }))
    await user.click(within(await screen.findByRole('group', { name: 'Edition' })).getByRole('radio', { name: 'Unlimited' }))
    await user.click(screen.getByRole('button', { name: 'This is my card' }))
    expect(await screen.findByText(/You said it's/)).toBeInTheDocument()
    expect(posts).toEqual([
      { url: '/api/orgs/org1/cards/card1/proposed-identity', source: 'SEARCH', externalId: 'base1-4', language: 'en', edition: 'UNLIMITED', variant: 'HOLO', stamp: null, stampUnsure: false },
    ])
  })

  it('searches right after the photos when the reader is off, and warns early about the minimum', async () => {
    const user = userEvent.setup()
    const { posts } = renderPanel([view({ step: 'PICK', why: 'READER_OFF' })], { underMinimum: true })
    expect(await screen.findByText('Find your card in the catalog.')).toBeInTheDocument()
    const box = screen.getByRole('combobox', { name: 'Find your card' })
    expect(box).toHaveValue('')
    await user.type(box, 'charizard')
    await user.click(await screen.findByRole('option', { name: /Charizard/ }))
    await user.click(within(await screen.findByRole('group', { name: 'Edition' })).getByRole('radio', { name: 'Shadowless' }))
    await user.click(screen.getByRole('button', { name: 'This is my card' }))
    // The minimum is only a hint, shown once the card is said.
    expect(await screen.findByText(/usually sells for under our \$25 minimum, whatever its condition/)).toBeInTheDocument()
    expect(posts).toMatchObject([{ source: 'SEARCH', externalId: 'base1-4', edition: 'SHADOWLESS' }])
  })

  it("searches when the reading fails, and takes a description when the card isn't in the catalog", async () => {
    const user = userEvent.setup()
    const { posts } = renderPanel([view({ step: 'PICK', why: 'READING_FAILED', readingId: 'r1' })])
    expect(await screen.findByText(/We couldn't read your card from the photos/)).toBeInTheDocument()
    await user.type(screen.getByRole('combobox', { name: 'Find your card' }), 'Mew')
    await user.click(screen.getByRole('button', { name: "Can't find it? Describe it" }))
    // What they typed carries over.
    expect(screen.getByLabelText('Describe your card')).toHaveValue('Mew')
    await user.type(screen.getByLabelText('Describe your card'), ' 151/165')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Mew 151/165')).toBeInTheDocument()
    expect(posts).toEqual([{ url: '/api/orgs/org1/cards/card1/proposed-identity', source: 'DESCRIBED', description: 'Mew 151/165' }])
  })

  it('lets the collector change their answer', async () => {
    const user = userEvent.setup()
    const { identifies } = renderPanel([view({ step: 'PROPOSED', proposal: proposal() }), view({ step: 'PICK', why: 'READER_OFF' })])
    await user.click(await screen.findByRole('button', { name: 'Change' }))
    expect(await screen.findByRole('combobox', { name: 'Find your card' })).toBeInTheDocument()
    expect(identifies.at(-1)).toBe('/api/orgs/org1/cards/card1/identify?again=true')
  })
})

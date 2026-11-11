import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { PokeTracePullGaps, PokeTraceQueue, PokeTraceQueueItem } from '../api'
import { respond } from '../test/auth0'
import { PokeTraceQueuePage } from './PokeTraceQueuePage'

const base: PokeTraceQueueItem = {
  catalogCardId: 'c1', orgId: 'o1', cardItemId: 'i1', name: 'Victini', setName: 'Paradox Rift', number: '171', edition: 'UNLIMITED',
  variant: 'HOLO', language: 'en', searchedAt: '2026-11-07T10:00:00Z', suggestedId: 'pt-v', error: null,
  candidates: [{ poketraceId: 'pt-v', name: 'Victini', cardNumber: '171/182', variant: 'Holofoil', setName: 'Paradox Rift', disagreements: [] }],
}
const queue: PokeTraceQueue = {
  enabled: true,
  searching: false,
  items: [
    base,
    {
      ...base, catalogCardId: 'c2', cardItemId: 'i2', name: 'Charizard', number: '4', edition: 'FIRST_EDITION', suggestedId: null,
      candidates: [{ ...base.candidates[0], poketraceId: 'pt-z', disagreements: ['edition: ours is 1st Edition'] }],
    },
    { ...base, catalogCardId: 'c3', cardItemId: 'i3', name: 'Mew', searchedAt: null, suggestedId: null, candidates: [] },
  ],
}

const noGaps: PokeTracePullGaps = { enabled: true, gapDays: 2, gaps: [] }

function page() {
  render(
    <MemoryRouter>
      <PokeTraceQueuePage />
    </MemoryRouter>,
  )
}

describe('PokeTraceQueuePage', () => {
  it('approves only clean matches, and links the rest to their card page', async () => {
    const user = userEvent.setup()
    const fetch = vi.fn(async (url: string) => {
      if (url.endsWith('/approve')) return respond(200, { mapped: ['c1'], failures: [] })
      if (url.endsWith('/pull-gaps')) return respond(200, noGaps)
      return respond(200, queue)
    })
    vi.stubGlobal('fetch', fetch)
    page()

    expect(await screen.findByText(/no clean match \(1 candidate\)/)).toBeInTheDocument()
    expect(screen.getByText('not searched yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Charizard #4' })).toHaveAttribute('href', '/admin/review/o1/i2')
    expect(screen.getAllByRole('checkbox')).toHaveLength(1)
    const approve = screen.getByRole('button', { name: 'Approve selected (0)' })
    expect(approve).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: 'Approve Victini #171' }))
    await user.click(screen.getByRole('button', { name: 'Approve selected (1)' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Mapped 1 card.')
    const post = fetch.mock.calls.find(([u]) => u.endsWith('/approve')) as unknown as [string, RequestInit]
    expect(JSON.parse(post[1].body as string)).toEqual({ catalogCardIds: ['c1'] })
  })

  it('starts a search and says to come back', async () => {
    const user = userEvent.setup()
    const fetch = vi.fn(async (url: string) =>
      url.endsWith('/search')
        ? respond(200, { started: 3, searching: true })
        : respond(200, url.endsWith('/pull-gaps') ? noGaps : queue),
    )
    vi.stubGlobal('fetch', fetch)
    page()
    await user.click(await screen.findByRole('button', { name: 'Find suggestions' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Searching 3 cards on PokeTrace'))
  })

  it('says when PokeTrace is off', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        respond(200, url.endsWith('/pull-gaps') ? { enabled: false, gapDays: 2, gaps: [] } : { enabled: false, searching: false, items: [] }),
      ),
    )
    page()
    expect(await screen.findByText("PokeTrace isn't enabled on this server.")).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Find suggestions' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Pulls stopped' })).not.toBeInTheDocument()
  })

  it('lists mapped cards whose pulls have stopped, with the last error', async () => {
    const gaps: PokeTracePullGaps = {
      enabled: true,
      gapDays: 2,
      gaps: [
        {
          catalogCardId: 'c9', orgId: 'o1', cardItemId: 'i9', name: 'Lugia', number: '9', setName: 'Neo Genesis', poketraceId: 'pt-l',
          mappedAt: '2026-11-01T00:00:00Z', lastPulledAt: '2026-11-05T12:00:00Z', lastError: 'PokeTrace timed out', daysSince: 5,
        },
        {
          catalogCardId: 'c8', orgId: 'o1', cardItemId: 'i8', name: 'Ho-Oh', number: null, setName: null, poketraceId: 'pt-h',
          mappedAt: '2026-11-07T00:00:00Z', lastPulledAt: null, lastError: null, daysSince: 3,
        },
      ],
    }
    vi.stubGlobal('fetch', vi.fn(async (url: string) => respond(200, url.endsWith('/pull-gaps') ? gaps : queue)))
    page()
    expect(await screen.findByRole('heading', { name: 'Pulls stopped' })).toBeInTheDocument()
    expect(screen.getByText(/no successful PokeTrace pull for 2\+ days/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Lugia #9' })).toHaveAttribute('href', '/admin/review/o1/i9')
    expect(screen.getByText('PokeTrace timed out')).toBeInTheDocument()
    expect(screen.getByText(/never since mapped \(3 days\)/)).toBeInTheDocument()
  })

  it('says when every mapped card has been pulled', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => respond(200, url.endsWith('/pull-gaps') ? noGaps : queue)))
    page()
    expect(await screen.findByText('Every mapped card has been pulled in the last 2 days.')).toBeInTheDocument()
  })
})

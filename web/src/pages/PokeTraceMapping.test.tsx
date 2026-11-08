import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { respond } from '../test/auth0'
import { PokeTraceMapping } from './PokeTraceMapping'

const unmapped = { enabled: true, poketraceId: null, poketraceCard: null, overrideReason: null, mappedBy: null, mappedAt: null }
const candidates = [
  { poketraceId: 'pt-a', name: 'Charizard', cardNumber: '4/102', variant: 'Holofoil', setName: 'Base Set', disagreements: [] },
  {
    poketraceId: 'pt-b', name: 'Charizard', cardNumber: '4/102', variant: 'Holofoil', setName: 'Base Set',
    disagreements: ["edition: ours is 1st Edition, PokeTrace's variant is Holofoil"],
  },
]

describe('PokeTraceMapping', () => {
  it('is hidden when PokeTrace is off', async () => {
    const fetch = vi.fn(async () => respond(200, { ...unmapped, enabled: false }))
    vi.stubGlobal('fetch', fetch)
    const { container } = render(<PokeTraceMapping catalogCardId="c1" />)
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })

  it('maps a matching card, and a mismatching one only with a reason', async () => {
    const user = userEvent.setup()
    const fetch = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/candidates')) return respond(200, candidates)
      if (init?.method === 'PUT') return respond(200, { ...unmapped, poketraceId: 'pt-b' })
      return respond(200, unmapped)
    })
    vi.stubGlobal('fetch', fetch)
    const onChanged = vi.fn()
    render(<PokeTraceMapping catalogCardId="c1" onChanged={onChanged} />)

    await user.click(await screen.findByRole('button', { name: 'Find on PokeTrace' }))
    expect(await screen.findByText(/ours is 1st Edition/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Map to pt-a' })).toBeEnabled()
    const mapB = screen.getByRole('button', { name: 'Map to pt-b' })
    expect(mapB).toBeDisabled()

    await user.type(screen.getByLabelText('Reason to map pt-b anyway'), 'checked the slab')
    await user.click(mapB)

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1))
    const put = fetch.mock.calls.find(([, init]) => init?.method === 'PUT') as unknown as [string, RequestInit]
    expect(put[0]).toContain('/api/admin/catalog-cards/c1/poketrace')
    expect(JSON.parse(put[1].body as string)).toEqual({ poketraceId: 'pt-b', overrideReason: 'checked the slab' })
  })
})

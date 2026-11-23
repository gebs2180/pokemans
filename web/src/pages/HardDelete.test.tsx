import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DeletionPlan } from '../api'
import { respond } from '../test/auth0'
import { HardDelete } from './HardDelete'

const PLAN: DeletionPlan = {
  kind: 'CARD',
  id: 'c1',
  name: 'Junk Charizard',
  orgId: 'o1',
  orgName: 'Test org',
  rows: { card_item: 1, photo: 2, card_offer: 1 },
  photos: 2,
  cards: 1,
  storage: ['orgs/o1/cards/c1/'],
  holds: [],
}

afterEach(() => vi.unstubAllGlobals())

function stub(plan: DeletionPlan) {
  const fetch = vi.fn((_url: string, init?: RequestInit) => {
    if (init?.method === 'DELETE') return Promise.resolve(respond(200, { deletionId: 'd1', plan, objectsDeleted: 6, storageError: null }))
    return Promise.resolve(respond(200, plan))
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

function renderIt(onDeleted = vi.fn()) {
  render(<HardDelete what="card" previewPath="/api/admin/orgs/o1/cards/c1/deletion-preview" deletePath="/api/admin/orgs/o1/cards/c1" onDeleted={onDeleted} />)
  return onDeleted
}

describe('HardDelete', () => {
  it('shows what goes, and deletes only once the name is typed exactly', async () => {
    const fetch = stub(PLAN)
    const onDeleted = renderIt()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Delete card…' }))
    expect(await screen.findByText('2 photo records')).toBeInTheDocument()
    expect(screen.getByText('1 offer')).toBeInTheDocument()
    const submit = screen.getByRole('button', { name: 'Delete card for good' })
    expect(submit).toBeDisabled()

    await user.type(screen.getByLabelText("Type the card's name to confirm"), 'Junk Charizar')
    expect(submit).toBeDisabled()
    await user.type(screen.getByLabelText("Type the card's name to confirm"), 'd')
    expect(submit).toBeEnabled()
    await user.click(submit)

    const call = fetch.mock.calls.find(([, init]) => init?.method === 'DELETE')
    expect(call?.[0]).toBe('/api/admin/orgs/o1/cards/c1?confirm=Junk%20Charizard')
    expect(onDeleted).toHaveBeenCalledWith(expect.objectContaining({ objectsDeleted: 6 }))
  })

  it('offers no delete while a trade holds the card', async () => {
    const fetch = stub({ ...PLAN, rows: {}, holds: [{ cardItemId: 'c1', listingId: 'l1', reason: 'DEAL_IN_PROGRESS', message: "A buyer took it and the deal isn't closed." }] })
    renderIt()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Delete card…' }))
    expect(await screen.findByText("A buyer took it and the deal isn't closed.")).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete card for good' })).toBeNull()
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false)
  })
})

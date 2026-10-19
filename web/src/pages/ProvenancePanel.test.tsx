import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ProvenanceView } from '../api'
import { respond } from '../test/auth0'
import { CustodyForm, ProvenancePanel, VerificationForm } from './ProvenancePanel'

const base = '/api/admin/orgs/org1/cards/card1'

/** The URL and JSON body of the first request a stubbed fetch got. */
function sent(fetch: { mock: { calls: unknown[][] } }) {
  const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
  return { url, body: JSON.parse(init.body as string) }
}

describe('VerificationForm', () => {
  it("doesn't offer photo review to verify a card, only to doubt one", async () => {
    const user = userEvent.setup()
    render(<VerificationForm base={base} onSaved={() => {}} />)
    const methods = () => within(screen.getByLabelText('How it was checked')).getAllByRole('option').map((o) => o.textContent)

    expect(methods()).not.toContain('photo review')
    await user.selectOptions(screen.getByLabelText('Finding'), 'SUSPECTED_COUNTERFEIT')
    expect(methods()).toContain('photo review')
    await user.selectOptions(screen.getByLabelText('How it was checked'), 'PHOTO_REVIEW')

    // Back to authentic: the photo review choice can't stay selected.
    await user.selectOptions(screen.getByLabelText('Finding'), 'VERIFIED_AUTHENTIC')
    expect(screen.getByLabelText('How it was checked')).toHaveValue('IN_HAND_INSPECTION')
  })

  it('sends one POST however many times Record is clicked', async () => {
    const user = userEvent.setup()
    let finish!: (r: Response) => void
    const fetch = vi.fn(() => new Promise<Response>((r) => (finish = r)))
    vi.stubGlobal('fetch', fetch)
    const onSaved = vi.fn()
    render(<VerificationForm base={base} onSaved={onSaved} />)

    await user.selectOptions(screen.getByLabelText('How it was checked'), 'GRADER_SLAB')
    await user.type(screen.getByLabelText('Finding note'), 'PSA cert 12345678')
    const record = screen.getByRole('button', { name: 'Record finding' })
    await user.dblClick(record)

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    expect(record).toBeDisabled()
    expect(sent(fetch).url).toBe(`${base}/authenticity-verifications`)
    expect(sent(fetch).body).toEqual({ status: 'VERIFIED_AUTHENTIC', method: 'GRADER_SLAB', note: 'PSA cert 12345678' })
    finish(respond(201, {}))
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(record).toBeEnabled()
  })
})

describe('CustodyForm', () => {
  it('records custody unverified unless it was checked, and says how', async () => {
    const user = userEvent.setup()
    const fetch = vi.fn(async () => respond(201, {}))
    vi.stubGlobal('fetch', fetch)
    render(<CustodyForm base={base} onSaved={() => {}} />)

    await user.selectOptions(screen.getByLabelText('Holder type'), 'VAULT')
    await user.type(screen.getByLabelText('Holder'), 'Card Vault Co')
    await user.type(screen.getByLabelText('Location'), 'Unit 4')
    expect(screen.queryByLabelText('How custody was checked')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Record custody' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    expect(sent(fetch).body).toMatchObject({ holderType: 'VAULT', holderName: 'Card Vault Co', location: 'Unit 4', verificationMethod: null })

    fetch.mockClear()
    await user.click(screen.getByLabelText('Custody checked'))
    await user.selectOptions(screen.getByLabelText('How custody was checked'), 'VAULT_STATEMENT')
    await user.click(screen.getByRole('button', { name: 'Record custody' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    expect(sent(fetch).body).toMatchObject({ verificationMethod: 'VAULT_STATEMENT' })
  })
})

describe('ProvenancePanel', () => {
  const view: ProvenanceView = {
    cardId: 'card1',
    authFlagUnresolved: true,
    authState: 'AWAITING_VERIFICATION',
    authenticityRisk: 'VINTAGE_HOLO',
    verifications: [{
      id: 'v1', status: 'VERIFIED_AUTHENTIC', method: 'IN_HAND_INSPECTION', note: null, catalogCardId: 'old',
      forCurrentIdentity: false, verifiedBy: 'u1', createdAt: '2026-10-01T00:00:00Z',
    }],
    custodyVerified: false,
    custodyState: 'NOT_RECORDED',
    custody: null,
    custodyHistory: [],
  }

  it('says a flagged card has zero value and an old verification no longer counts', async () => {
    vi.stubGlobal('fetch', async () => respond(200, view))
    render(<ProvenancePanel orgId="org1" cardId="card1" />)

    expect(await screen.findByText('Flagged: zero collateral value until verified.')).toBeInTheDocument()
    expect(screen.getByText(/for an earlier identity/)).toBeInTheDocument()
    expect(screen.getByText('No custody recorded: no advance.')).toBeInTheDocument()
  })
})

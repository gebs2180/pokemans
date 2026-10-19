import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { AuthMethod, ProvenanceView } from '../api'
import { respond } from '../test/auth0'
import { CustodyForm, ProvenancePanel, VerificationForm } from './ProvenancePanel'

const base = '/api/admin/orgs/org1/cards/card1'
const HIGH_RISK_METHODS: AuthMethod[] = ['IN_HAND_INSPECTION', 'GRADER_SLAB', 'THIRD_PARTY_AUTHENTICATION']

/** The URL and JSON body of the first request a stubbed fetch got. */
function sent(fetch: { mock: { calls: unknown[][] } }) {
  const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
  return { url, body: JSON.parse(init.body as string) }
}

describe('VerificationForm', () => {
  it("doesn't offer photo review to verify a high-risk card, only to doubt one", async () => {
    const user = userEvent.setup()
    render(<VerificationForm base={base} verifyMethods={HIGH_RISK_METHODS} onSaved={() => {}} />)
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
    render(<VerificationForm base={base} verifyMethods={HIGH_RISK_METHODS} onSaved={onSaved} />)

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

describe('VerificationForm for a recent chase card', () => {
  it('offers photo review and says it needs a close-up', async () => {
    const user = userEvent.setup()
    render(<VerificationForm base={base} verifyMethods={[...HIGH_RISK_METHODS, 'PHOTO_REVIEW']} onSaved={() => {}} />)

    await user.selectOptions(screen.getByLabelText('How it was checked'), 'PHOTO_REVIEW')
    expect(screen.getByText('Needs a passing close-up photo of the card.')).toBeInTheDocument()
  })
})

describe('CustodyForm', () => {
  it('records custody unverified unless it was checked, and says how', async () => {
    const user = userEvent.setup()
    const fetch = vi.fn(async () => respond(201, {}))
    vi.stubGlobal('fetch', fetch)
    render(<CustodyForm base={base} canCheck onSaved={() => {}} />)

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

describe('CustodyForm for a member', () => {
  it("can't record custody as checked", async () => {
    const user = userEvent.setup()
    const fetch = vi.fn(async () => respond(201, {}))
    vi.stubGlobal('fetch', fetch)
    render(<CustodyForm base="/api/orgs/org1/cards/card1" canCheck={false} onSaved={() => {}} />)

    expect(screen.queryByLabelText('Custody checked')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Holder'), 'Sam')
    await user.type(screen.getByLabelText('Location'), 'Home safe')
    await user.click(screen.getByRole('button', { name: 'Record custody' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    expect(sent(fetch).url).toBe('/api/orgs/org1/cards/card1/custody-records')
    expect(sent(fetch).body).toMatchObject({ holderName: 'Sam', verificationMethod: null })
  })
})

describe('ProvenancePanel', () => {
  const view: ProvenanceView = {
    cardId: 'card1',
    authFlagUnresolved: true,
    authState: 'AWAITING_VERIFICATION',
    authenticityRisk: 'VINTAGE_HOLO',
    verifyMethods: HIGH_RISK_METHODS,
    verifications: [{
      id: 'v1', status: 'VERIFIED_AUTHENTIC', method: 'IN_HAND_INSPECTION', note: null, catalogCardId: 'old',
      forCurrentIdentity: false, verifiedBy: 'u1', createdAt: '2026-10-01T00:00:00Z',
    }],
    custodyVerified: false,
    custodyState: 'NOT_RECORDED',
    custodyVerifiedUntil: null,
    custody: null,
    custodyHistory: [],
  }

  it('says a flagged card has zero value and an old verification no longer counts', async () => {
    vi.stubGlobal('fetch', async () => respond(200, view))
    render(<ProvenancePanel orgId="org1" cardId="card1" admin />)

    expect(await screen.findByText('Flagged: zero collateral value until verified.')).toBeInTheDocument()
    expect(screen.getByText(/for an earlier identity/)).toBeInTheDocument()
    expect(screen.getByText('No custody recorded: no advance.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Record finding' })).toBeInTheDocument()
  })

  it('shows members the status and custody form, but no findings form', async () => {
    vi.stubGlobal('fetch', async () => respond(200, view))
    render(<ProvenancePanel orgId="org1" cardId="card1" admin={false} />)

    expect(await screen.findByText('Flagged: zero collateral value until verified.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record finding' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Record custody' })).toBeInTheDocument()
  })
})

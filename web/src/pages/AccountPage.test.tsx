import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { BuyerRequest, BuyingState, Me } from '../api'
import { respond } from '../test/auth0'
import { AccountPage } from './AccountPage'
import { AdminBuyersPage } from './AdminBuyersPage'

const me = (memberships: Me['memberships'] = [{ orgId: 'org1', orgName: 'Sam Test', orgType: 'COLLECTOR', role: 'COLLECTOR' }]): Me => ({
  subject: 'auth0|123',
  issuer: null,
  roles: [],
  claimedRoles: [],
  mfa: false,
  memberships,
})

const terms = { version: 'buyer-firm-2026-11-12', text: 'You pay what you accept.', sha256: 'abc' }
const state = (over: Partial<BuyingState> = {}): BuyingState => ({
  status: 'NOT_STARTED',
  orgId: null,
  orgName: null,
  appliedAt: null,
  decidedAt: null,
  note: null,
  terms,
  ...over,
})

type Call = { method: string; url: string; body: unknown }

function stub(routes: Record<string, unknown>, calls: Call[] = []) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      const match = Object.keys(routes).sort((a, b) => b.length - a.length).find((path) => url.endsWith(path))
      return match ? respond(200, routes[match]) : respond(404)
    }),
  )
}

function page(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/account" element={<AccountPage />} />
        <Route path="/admin/buyers" element={<AdminBuyersPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('Account', () => {
  it('shows the selling side and starts buying with the terms and a ship-to address', async () => {
    const calls: Call[] = []
    stub({ '/api/me': me(), '/api/me/buying': state() }, calls)
    page('/account')
    expect(await screen.findByRole('heading', { name: 'Selling' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sam Test' })).toHaveAttribute('href', '/')

    await userEvent.click(screen.getByRole('button', { name: 'Start buying' }))
    expect(screen.getByRole('region', { name: 'Buyer terms' })).toHaveTextContent('You pay what you accept.')
    expect(screen.getByLabelText("Buyer name")).toHaveValue('Sam Test')
    await userEvent.type(screen.getByLabelText('Ship to (name)'), 'Sam')
    await userEvent.type(screen.getByLabelText('Street address'), '2 Oak St')
    await userEvent.type(screen.getByLabelText('City'), 'Springfield')
    await userEvent.type(screen.getByLabelText('State'), 'il')
    await userEvent.type(screen.getByLabelText('ZIP code'), '62701')
    const ask = screen.getByRole('button', { name: 'Ask to buy' })
    expect(ask).toBeDisabled()
    await userEvent.click(screen.getByLabelText('I accept the buyer terms'))
    await userEvent.click(ask)

    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    const post = calls.find((c) => c.method === 'POST')!
    expect(post.url).toMatch(/\/api\/me\/buying$/)
    expect(post.body).toEqual({
      name: 'Sam Test',
      version: 'buyer-firm-2026-11-12',
      shipTo: { name: 'Sam', street1: '2 Oak St', street2: null, city: 'Springfield', state: 'IL', zip: '62701', phone: null, company: null },
    })
  })

  it('starts the ship-to from the address they ship cards from, and says so', async () => {
    const found = { name: 'Sam Seller', street1: '9 Elm St', street2: null, city: 'Peoria', state: 'IL', zip: '61602', phone: '555-0100', company: null, paymentHandle: 'venmo' }
    stub({ '/api/me': me(), '/api/me/buying': state(), '/api/orgs/org1/ship-address': found })
    page('/account')
    await userEvent.click(await screen.findByRole('button', { name: 'Start buying' }))
    await waitFor(() => expect(screen.getByLabelText('Street address')).toHaveValue('9 Elm St'))
    expect(screen.getByLabelText('Ship to (name)')).toHaveValue('Sam Seller')
    expect(screen.getByText(/Filled in from the address you ship your cards from/)).toBeInTheDocument()
    expect(screen.getByText(/How buying works/)).toBeInTheDocument()
  })

  it('says it is waiting for approval', async () => {
    stub({ '/api/me': me(), '/api/me/buying': state({ status: 'WAITING', orgId: 'b1', orgName: 'Sam buys', appliedAt: '2026-11-20T10:00:00Z' }) })
    page('/account')
    expect(await screen.findByText(/Waiting for approval/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Start buying' })).not.toBeInTheDocument()
  })

  it('shows why buying was declined and lets them ask again', async () => {
    stub({ '/api/me': me(), '/api/me/buying': state({ status: 'DECLINED', orgId: 'b1', orgName: 'Sam buys', note: 'Add a phone number.' }) })
    page('/account')
    expect(await screen.findByText('Add a phone number.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Ask again' }))
    expect(screen.getByLabelText("Buyer name")).toHaveValue('Sam buys')
  })

  it('links an approved buyer to their buyer account', async () => {
    stub({
      '/api/me': me([
        { orgId: 'org1', orgName: 'Sam Test', orgType: 'COLLECTOR', role: 'COLLECTOR' },
        { orgId: 'b1', orgName: 'Sam buys', orgType: 'BUYER', role: 'BUYER' },
      ]),
      '/api/me/buying': state({ status: 'APPROVED', orgId: 'b1', orgName: 'Sam buys' }),
    })
    page('/account')
    expect(await screen.findByRole('link', { name: 'Sam buys' })).toHaveAttribute('href', '/orgs/b1')
    // One seller org plus a buyer org: Home is the seller org, so it links to Home.
    expect(screen.getByRole('link', { name: 'Sam Test' })).toHaveAttribute('href', '/')
    expect(screen.getAllByRole('link', { name: 'Sam buys' })).toHaveLength(1)
  })
})

describe('Requests to buy', () => {
  const request = (over: Partial<BuyerRequest> = {}): BuyerRequest => ({
    id: 'r1',
    orgId: 'b1',
    orgName: 'Sam buys',
    userId: 'u1',
    email: 'sam@example.com',
    attempt: 1,
    appliedAt: '2026-11-20T10:00:00Z',
    sellerOrgs: ['Sam Test'],
    approved: null,
    decidedAt: null,
    decidedBy: null,
    note: null,
    ...over,
  })

  it('approves, and declines only with a reason', async () => {
    const calls: Call[] = []
    stub({ '/api/admin/buyers': [request(), request({ id: 'r2', orgName: 'Kim buys', sellerOrgs: [] })], '/approve': request({ approved: true }), '/decline': request({ approved: false }) }, calls)
    page('/admin/buyers')
    expect(await screen.findByText(/sells as Sam Test/)).toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[0])
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/api/admin/buyers/r1/approve'))).toBe(true))

    await userEvent.click(screen.getAllByRole('button', { name: 'Decline' })[1])
    const submit = screen.getAllByRole('button', { name: 'Decline' }).at(-1)!
    expect(submit).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Why'), 'Use a street address.')
    await userEvent.click(submit)
    await waitFor(() => expect(calls.find((c) => c.url.endsWith('/api/admin/buyers/r2/decline'))?.body).toEqual({ note: 'Use a street address.' }))
  })

  it('lists the answered ones with who answered', async () => {
    stub({ '/api/admin/buyers': [request({ approved: false, decidedAt: '2026-11-21T10:00:00Z', decidedBy: 'zach@example.com', note: 'No PO boxes.' })] })
    page('/admin/buyers')
    expect(await screen.findByText('Nobody is waiting.')).toBeInTheDocument()
    expect(screen.getByText('Declined')).toBeInTheDocument()
    expect(screen.getByText(/by zach@example.com/)).toBeInTheDocument()
    expect(screen.getByText('No PO boxes.')).toBeInTheDocument()
  })
})

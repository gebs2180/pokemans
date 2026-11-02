import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { Me, NeedsYouItem, OrgType } from '../api'
import { CONTACT } from '../contact'
import { auth0, respond } from '../test/auth0'
import { AdminPage } from './AdminPage'
import { HomePage } from './HomePage'
import { HowItWorksPage } from './HowItWorksPage'
import { InvitePage } from './InvitePage'
import { OrgPage } from './OrgPage'
import { TermsPage } from './TermsPage'

const me = (orgType: OrgType = 'COLLECTOR', over: Partial<Me> = {}): Me => ({
  subject: 'auth0|123',
  issuer: null,
  roles: ['COLLECTOR'],
  claimedRoles: [],
  mfa: false,
  memberships: [{ orgId: 'org1', orgName: 'Sam Test', orgType, role: 'COLLECTOR' }],
  ...over,
})

const consent = (acceptedAt: string | null = '2026-11-01T10:00:00Z') => ({ version: 'v1', text: 'You agree to all of it.', acceptedAt })
const binder = { id: 'c1', name: 'My cards', createdAt: '2026-11-01T10:00:00Z', cards: 0 }

function stub(routes: Record<string, unknown>, posted: string[] = []) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') posted.push(url)
      const match = Object.keys(routes).sort((a, b) => b.length - a.length).find((path) => url.endsWith(path))
      return match ? respond(200, routes[match]) : respond(404)
    }),
  )
}

function page(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <main>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="/how-it-works" element={<HowItWorksPage />} />
          <Route path="/invite/:token" element={<InvitePage />} />
          <Route path="/orgs/:orgId" element={<OrgPage />} />
          <Route path="/orgs/:orgId/terms" element={<TermsPage />} />
        </Routes>
      </main>
    </MemoryRouter>,
  )
}

const seller = (needs: NeedsYouItem[] = [], consentStatus = consent()) => ({
  '/api/me': me(),
  '/consent': consentStatus,
  '/collections': [binder],
  '/collections/c1': { ...binder, cards: [] },
  '/collections/c1/status': [],
  '/needs-you': needs,
})

describe('Home', () => {
  it('is the one org and its one binder: Add card, with no org in sight', async () => {
    stub(seller())
    page('/')
    expect(await screen.findByRole('heading', { level: 1, name: 'Your cards' })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Add card' })).toBeInTheDocument()
    expect(screen.queryByText('Sam Test')).not.toBeInTheDocument()
    expect(screen.queryByText('Collector')).not.toBeInTheDocument()
    expect(screen.queryByText(/Account details/)).not.toBeInTheDocument()
    expect(screen.queryByText(/How selling works/)).not.toBeInTheDocument()
    // Nothing waiting: no list, and no noise.
    expect(screen.queryByRole('heading', { name: 'Needs you' })).not.toBeInTheDocument()
  })

  it('lists what needs the seller, soonest first, each with its date', async () => {
    const offer: NeedsYouItem = { cardId: 'a', label: 'Mew', need: 'OFFER_READY', due: '2026-12-20T12:00:00Z', sides: [] }
    const ending: NeedsYouItem = { cardId: 'b', label: 'Eevee', need: 'OFFER_EXPIRING', due: '2099-12-03T12:00:00Z', sides: [] }
    const label: NeedsYouItem = { cardId: 'c', label: 'Pikachu', need: 'LABEL_READY', due: '2099-11-05T12:00:00Z', sides: [] }
    const retake: NeedsYouItem = { cardId: 'd', label: 'Snorlax', need: 'RETAKE', due: null, sides: ['FRONT', 'BACK'] }
    const ready: NeedsYouItem = { cardId: 'e', label: 'Lapras', need: 'GET_OFFER', due: null, sides: [] }
    stub(seller([retake, offer, ready, ending, label]))
    page('/')
    const needs = await screen.findByRole('region', { name: 'Needs you' })
    const rows = within(needs).getAllByRole('listitem').map((li) => li.textContent)
    expect(rows[0]).toMatch(/^Mew.*Good until Dec 20/)
    expect(rows[1]).toMatch(/^Pikachu.*Ship by Nov 5/)
    expect(rows[2]).toMatch(/^Eevee.*Offer ends Dec 3/)
    expect(rows.slice(3).join()).toMatch(/Snorlax.*Retake front and back/)
    expect(rows.slice(3).join()).toMatch(/Lapras.*Get your offer/)
    expect(within(needs).getByRole('link', { name: 'Pikachu' })).toHaveAttribute('href', '/orgs/org1/cards/c')
    expect(within(needs).getByText(/check back here/)).toBeInTheDocument()
  })

  it("doesn't call a passed ship-by date a date to ship by", async () => {
    stub(seller([{ cardId: 'c', label: 'Pikachu', need: 'LABEL_READY', due: '2026-01-05T12:00:00Z', sides: [] }]))
    page('/')
    expect(await screen.findByText('Was due Jan 5')).toBeInTheDocument()
    expect(screen.getByText(/ship-by date has passed/)).toBeInTheDocument()
  })

  it('says so when it can\'t load what needs the seller', async () => {
    const routes = seller()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/needs-you')) return respond(500, { detail: 'Down' })
      const match = Object.keys(routes).sort((a, b) => b.length - a.length).find((path) => url.endsWith(path))
      return respond(200, routes[match as keyof typeof routes])
    }))
    page('/')
    expect(await screen.findByRole('alert')).toHaveTextContent("We couldn't load what needs you: Down")
  })

  it('keeps the org list and account details for the admin', async () => {
    stub({ '/api/me': me('COLLECTOR', { roles: ['ADMIN'] }), '/needs-you': [] })
    page('/')
    expect(await screen.findByRole('link', { name: 'Sam Test' })).toBeInTheDocument()
    expect(screen.getByText('Account details')).toBeInTheDocument()
  })

  it('gives a stranger something to do', async () => {
    stub({ '/api/me': me('COLLECTOR', { roles: [], memberships: [] }) })
    page('/')
    expect(await screen.findByText("You're not set up yet.")).toBeInTheDocument()
  })

  it('sends someone with one org from its page to home', async () => {
    stub(seller())
    page('/orgs/org1')
    expect(await screen.findByRole('heading', { level: 1, name: 'Your cards' })).toBeInTheDocument()
  })

  it('offers a way home from a page that failed', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => respond(403)))
    page('/admin')
    expect(await screen.findByRole('alert')).toHaveTextContent("You don't have access to this page.")
    expect(screen.getByRole('link', { name: 'Go to your home page' })).toHaveAttribute('href', '/')
  })
})

describe('Joining', () => {
  it("invites a seller to sell, not to join an org with the admin's name for it", async () => {
    stub({ '/api/invites/tok': { orgName: 'Sam Test', orgType: 'COLLECTOR', role: 'COLLECTOR', expiresAt: '2026-11-08T00:00:00Z', usable: true } })
    page('/invite/tok')
    expect(await screen.findByRole('heading', { name: "You're invited to sell your cards on Rawmark" })).toBeInTheDocument()
    expect(screen.queryByText(/Sam Test/)).not.toBeInTheDocument()
  })

  it('shows how it works before the terms, then the terms, then the cards', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('scrollTo', vi.fn())
    const posted: string[] = []
    const routes: Record<string, unknown> = { ...seller([], consent(null)) }
    stub(routes, posted)
    page('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'How it works' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Selling a card' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Buying a card' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'I agree' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Continue to the terms' }))
    expect(screen.getByRole('heading', { level: 1, name: 'Before you sell' })).toHaveFocus()
    expect(screen.getByRole('region', { name: 'Terms' })).toHaveTextContent('You agree to all of it.')

    routes['/consent'] = consent()
    await user.click(screen.getByRole('button', { name: 'I agree' }))
    expect(await screen.findByRole('button', { name: 'Add card' })).toBeInTheDocument()
    expect(posted).toEqual(['/api/orgs/org1/consent'])
    expect(screen.getByRole('link', { name: 'Read them again' })).toHaveAttribute('href', '/orgs/org1/terms')
  })
})

describe('How it works', () => {
  it('is open to a stranger, and says who runs it, who pays and the limit', async () => {
    page('/how-it-works')
    expect(screen.getByRole('heading', { level: 1, name: 'How it works' })).toBeInTheDocument()
    expect(screen.getByText(/run by Zach/)).toBeInTheDocument()
    expect(screen.getByText('$5,000')).toBeInTheDocument()
    expect(screen.getAllByText(/pays you/).length).toBeGreaterThan(0)
    expect(screen.queryByText(/APP NAME/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /terms/i })).not.toBeInTheDocument()
  })

  it('has no contact detail until Zach sets one, and falls back to a true one', () => {
    expect(CONTACT).toBeNull()
    page('/how-it-works')
    expect(screen.getByText('To reach us, message Zach the same way your invite came.')).toBeInTheDocument()
  })

  it('links a member to their terms, to read again', async () => {
    auth0.isAuthenticated = true
    stub({ '/api/me': me(), '/consent': consent() })
    page('/how-it-works')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('link', { name: 'Read your seller terms' }))
    expect(await screen.findByText('You agree to all of it.')).toBeInTheDocument()
    expect(screen.getByText(/You accepted these on/)).toBeInTheDocument()
  })
})

describe('Admin', () => {
  it('counts what needs attention, with the way to each', async () => {
    stub({
      '/api/admin/orgs': [],
      '/api/admin/review-queue': [{ identified: true, priced: false }, { identified: true, priced: true }, { identified: false, priced: false }],
      '/api/admin/deals/attention': { returns: 2, overdueLabels: 1 },
    })
    page('/admin')
    expect(await screen.findByRole('heading', { name: 'Needs attention: 4' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '1 card with no comps' })).toHaveAttribute('href', '/admin/review')
    expect(screen.getByRole('link', { name: '2 cards sent back in the last 14 days' })).toHaveAttribute('href', '/admin/deals')
    expect(screen.getByRole('link', { name: '1 card past the ship-by date with nothing recorded' })).toHaveAttribute('href', '/admin/deals')
  })

  it("doesn't read as all clear when the count failed", async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => (url.endsWith('/orgs') ? respond(200, []) : respond(500, { detail: 'Down' }))))
    page('/admin')
    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't load what needs attention")
  })
})

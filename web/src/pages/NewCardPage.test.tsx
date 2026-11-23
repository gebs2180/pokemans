import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { respond } from '../test/auth0'
import { NewCardPage } from './NewCardPage'

function CardStub() {
  const location = useLocation()
  return <p>Card page {JSON.stringify(location.state)}</p>
}

function renderNew(calls: string[], cardFails = false) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const path = url.replace(/^.*\/api/, '/api')
      calls.push(`${init?.method ?? 'GET'} ${url.startsWith('https://storage') ? url : path}`)
      if (path.endsWith('/collections/c1/cards')) {
        return Promise.resolve(cardFails ? respond(500, { detail: 'Server error' }) : respond(201, { id: 'new1' }))
      }
      if (path.endsWith('/photos')) {
        const side = JSON.parse(String(init?.body)).side
        return Promise.resolve(respond(200, { photoId: `ph-${side}`, url: `https://storage/${side}`, method: 'PUT', headers: {}, expiresAt: '2099-01-01T00:00:00Z' }))
      }
      if (url.startsWith('https://storage')) return Promise.resolve(new Response(null, { status: 200 }))
      return Promise.resolve(respond(200, { id: 'x', status: 'PASSED' }))
    }),
  )
  render(
    <MemoryRouter initialEntries={['/orgs/org1/collections/c1/new']}>
      <Routes>
        <Route path="/orgs/:orgId/:kind/:containerId/new" element={<NewCardPage />} />
        <Route path="/orgs/:orgId/cards/:cardId" element={<CardStub />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('NewCardPage', () => {
  it('makes the card with its first photo, then goes to it with the back next', async () => {
    const user = userEvent.setup()
    const calls: string[] = []
    renderNew(calls)
    await user.upload(screen.getByLabelText('Take front photo'), new File(['f'], 'front.jpg', { type: 'image/jpeg' }))
    expect(await screen.findByText(/Card page/)).toHaveTextContent('{"focusBack":true}')
    expect(calls).toEqual([
      'POST /api/orgs/org1/collections/c1/cards',
      'POST /api/orgs/org1/cards/new1/photos',
      'PUT https://storage/FRONT',
      'POST /api/orgs/org1/photos/ph-FRONT/confirm',
    ])
  })

  it('takes two chosen photos as front and back', async () => {
    const user = userEvent.setup()
    const calls: string[] = []
    renderNew(calls)
    await user.upload(screen.getByLabelText('Choose front photo, or front and back'), [
      new File(['f'], 'front.jpg', { type: 'image/jpeg' }),
      new File(['b'], 'back.jpg', { type: 'image/jpeg' }),
    ])
    expect(calls).toEqual([])
    await user.click(screen.getByRole('button', { name: 'Use these' }))
    expect(await screen.findByText(/Card page/)).toHaveTextContent('{"focusBack":false}')
    expect(calls.filter((c) => c.startsWith('PUT'))).toEqual(['PUT https://storage/FRONT', 'PUT https://storage/BACK'])
  })

  it('says so, and stays, when the card cannot be made', async () => {
    const user = userEvent.setup()
    renderNew([], true)
    await user.upload(screen.getByLabelText('Take front photo'), new File(['f'], 'front.jpg', { type: 'image/jpeg' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByLabelText('Take front photo')).toBeInTheDocument()
  })
})

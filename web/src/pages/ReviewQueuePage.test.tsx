import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { QueueItem } from '../api'
import { respond } from '../test/auth0'
import { ReviewQueuePage } from './ReviewQueuePage'

const item = (over: Partial<QueueItem>): QueueItem => ({
  cardId: 'c1',
  orgId: 'o1',
  orgName: 'Sam',
  label: 'Pikachu VMAX',
  createdAt: '2026-10-21T10:00:00Z',
  identified: true,
  identifiedAs: 'Pikachu VMAX · Vivid Voltage 188/185',
  assessment: 'REVIEWER',
  confidence: 'B',
  openRequests: 0,
  priced: true,
  ...over,
})

describe('ReviewQueuePage', () => {
  it('keeps a reviewed card without prices to do, so its seller is not left waiting', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(respond(200, [item({}), item({ cardId: 'c2', label: 'Mew', priced: false })]))),
    )
    render(
      <MemoryRouter>
        <ReviewQueuePage />
      </MemoryRouter>,
    )

    expect(await screen.findByText(/1 to review \(1 need comps\), 1 done/)).toBeInTheDocument()
    expect(screen.getByText('Needs comps: no PSA prices yet')).toBeInTheDocument()
  })
})

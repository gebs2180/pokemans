import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PreGradeFailures } from '../api'
import { respond } from '../test/auth0'
import { AdminPage } from './AdminPage'

function stub(failures: PreGradeFailures) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.endsWith('/pregrade-failures')) return Promise.resolve(respond(200, failures))
      if (url.endsWith('/deals/overdue')) return Promise.resolve(respond(200, { labels: 0 }))
      if (url.endsWith('/poketrace/queue')) return Promise.resolve(respond(200, { enabled: true, searching: false, items: [] }))
      return Promise.resolve(respond(200, []))
    }),
  )
}

afterEach(() => vi.unstubAllGlobals())

function renderPage() {
  render(
    <MemoryRouter>
      <AdminPage />
    </MemoryRouter>,
  )
}

describe('AdminPage needs attention', () => {
  it('links the cards whose automatic condition check failed to the review queue', async () => {
    stub({ failedCards: 2, outOfCreditsCards: 0, outOfCredits: false, submitsResumeAt: null })
    renderPage()

    const link = await screen.findByRole('link', { name: /2 cards whose automatic condition check failed/ })
    expect(link).toHaveAttribute('href', '/admin/review')
    expect(screen.queryByText(/out of credits/)).toBeNull()
  })

  it('says CardGrade is out of credits in its own words, and when sending resumes', async () => {
    stub({ failedCards: 3, outOfCreditsCards: 3, outOfCredits: true, submitsResumeAt: '2026-11-13T10:00:00Z' })
    renderPage()

    const link = await screen.findByRole('link', { name: /3 cards waiting on CardGrade, which is out of credits; sending resumes/ })
    expect(link).toHaveAttribute('href', '/admin/review')
    expect(screen.queryByText(/automatic condition check failed/)).toBeNull()
  })

  it('warns when submits are paused with no card failed yet', async () => {
    stub({ failedCards: 0, outOfCreditsCards: 0, outOfCredits: true, submitsResumeAt: null })
    renderPage()

    expect(await screen.findByText(/CardGrade is out of credits\. Cards sent now will fail/)).toBeInTheDocument()
  })
})

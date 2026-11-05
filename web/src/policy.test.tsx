import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PolicyProvider } from './PolicyProvider'
import { testPolicy } from './test/policy'

// setup.ts stubs usePolicy for the pages; here the real provider loads it.
const { usePolicy, dollars, days } = await vi.importActual<typeof import('./policy')>('./policy')

function Shows() {
  const policy = usePolicy()
  return <p>Offers up to {dollars(policy.maxPrice)}, good for {days(policy.offerValidDays)}.</p>
}

const ok = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }))

describe('PolicyProvider', () => {
  it('loads the terms numbers from the server once, without a token', async () => {
    const fetch = vi.fn(() => ok({ ...testPolicy, maxPrice: 2500, offerValidDays: 5 }))
    vi.stubGlobal('fetch', fetch)
    render(<PolicyProvider><Shows /></PolicyProvider>)
    expect(await screen.findByText('Offers up to $2,500, good for 5 days.')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch).toHaveBeenCalledWith('/api/policy')
  })

  it("shows nothing that needs the numbers until it has them, and can try again", async () => {
    const fetch = vi.fn()
      .mockImplementationOnce(() => Promise.resolve(new Response('', { status: 503 })))
      .mockImplementationOnce(() => ok(testPolicy))
    vi.stubGlobal('fetch', fetch)
    render(<PolicyProvider><Shows /></PolicyProvider>)
    expect(await screen.findByRole('alert')).toHaveTextContent("couldn't reach its server")
    expect(screen.queryByText(/Offers up to/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Offers up to $5,000, good for 7 days.')).toBeInTheDocument()
  })
})

describe('days', () => {
  it('says one day in the singular', () => {
    expect(days(1)).toBe('1 day')
    expect(days(3)).toBe('3 days')
  })
})

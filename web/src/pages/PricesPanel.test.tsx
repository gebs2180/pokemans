import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { respond } from '../test/auth0'
import { CompForm } from './PricesPanel'

describe('CompForm', () => {
  it("defaults the sold date to the reviewer's local date, not UTC", () => {
    // 11pm in New York on 17 October is already 18 October in UTC.
    vi.stubEnv('TZ', 'America/New_York')
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-18T03:00:00Z'))

    render(<CompForm base="/api/admin/catalog-cards/c1" onAdded={() => {}} />)

    expect(screen.getByLabelText('Sold on')).toHaveValue('2026-10-17')
  })

  it('sends one POST however many times Add is clicked', async () => {
    const user = userEvent.setup()
    let finish!: (r: Response) => void
    const fetch = vi.fn(() => new Promise<Response>((r) => (finish = r)))
    vi.stubGlobal('fetch', fetch)
    const onAdded = vi.fn()
    render(<CompForm base="/api/admin/catalog-cards/c1" onAdded={onAdded} />)

    await user.type(screen.getByLabelText('Grade'), '9')
    await user.type(screen.getByLabelText('Sold price (USD)'), '120')
    const add = screen.getByRole('button', { name: 'Add sold comp' })
    await user.dblClick(add)
    await user.click(add)

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    expect(add).toBeDisabled()
    finish(respond(201, { id: 'comp1' }))
    await waitFor(() => expect(onAdded).toHaveBeenCalledTimes(1))
    expect(add).toBeEnabled()
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})

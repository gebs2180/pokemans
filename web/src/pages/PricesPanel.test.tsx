import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { respond } from '../test/auth0'
import { CompForm, PricesPanel } from './PricesPanel'

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

  it('sends a price typed with commas as a number', async () => {
    const user = userEvent.setup()
    const fetch = vi.fn(async () => respond(201, { id: 'comp1' }))
    vi.stubGlobal('fetch', fetch)
    render(<CompForm base="/api/admin/catalog-cards/c1" onAdded={() => {}} />)

    await user.type(screen.getByLabelText('Grade'), '10')
    await user.type(screen.getByLabelText('Sold price (USD)'), '28,750.00')
    await user.click(screen.getByRole('button', { name: 'Add sold comp' }))

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    const [, init] = fetch.mock.calls[0] as unknown as [string, RequestInit]
    expect(JSON.parse(init.body as string)).toMatchObject({ grade: 10, price: 28750 })
  })
})

describe('PricesPanel', () => {
  const grade = (g: string, confidence: string, pricedConfidence: string) => ({
    grade: g, price: 100, method: 'OBSERVED', confidence, pricedConfidence, n: 10, compIds: [], windowDays: 90, basis: [],
    stepDownRatio: null,
  })
  const view = (staleLevels: number, daysOld: number, confidence: string) => ({
    catalogCardId: 'c1',
    comps: [],
    skipped: [],
    tables: [{
      gradingCompany: 'PSA', snapshotSetId: 's1', asOf: '2026-09-08', parameterSet: '1.0.0', sigma: null, sigmaChanges: 0,
      salesPerMonth: 3, daysOld, staleLevels, grades: [grade('U', 'D', 'D'), grade('10', confidence, 'A')],
    }],
  })

  it('says a table is stale and shows the confidence it was priced at', async () => {
    vi.stubGlobal('fetch', async () => respond(200, view(2, 40, 'C')))
    render(<PricesPanel catalogCardId="c1" />)

    expect(await screen.findByRole('alert')).toHaveTextContent('priced 40 days ago')
    expect(screen.getByRole('alert')).toHaveTextContent('down 2 levels')
    expect(screen.getByText('(was A)')).toBeInTheDocument()
  })

  it('says nothing for a fresh table', async () => {
    vi.stubGlobal('fetch', async () => respond(200, view(0, 1, 'A')))
    render(<PricesPanel catalogCardId="c1" />)

    expect(await screen.findByText('PSA')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/\(was /)).not.toBeInTheDocument()
  })
})

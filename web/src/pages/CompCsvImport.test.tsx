import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { respond } from '../test/auth0'
import { compsFromCsv, parseCsv } from '../compsCsv'
import { CompCsvImport } from './CompCsvImport'

// The columns of Pictures\pokemans-test-cards\comps.csv, with rows for two cards.
const CSV = `card,set,number,grader,grade,price,sold_on,listing_type,marketplace,url
Charizard,Base Set (Unlimited),4/102,PSA,10,28750.0,2026-10-15,UNKNOWN,Heritage Auctions,https://www.ha.com/itm/-/-/-/a/7459-36047.s
Charizard,Base Set (Unlimited),4/102,PSA,9,3851.0,2026-10-18,UNKNOWN,eBay,https://www.ebay.com/itm/287638208701
Umbreon VMAX,Evolving Skies,215/203,PSA,10,3800.0,2026-10-20,UNKNOWN,eBay,https://www.ebay.com/itm/137858179071
Charizard,Base Set (Unlimited),4/102,PSA,8,1450.0,,UNKNOWN,eBay,https://www.ebay.com/itm/820241976236
Charizard,Base Set (Unlimited),4/102,,,900,2026-10-18,UNKNOWN,eBay,
"Charizard, shadowless",Base Set,004/102,PSA,8.5,"1,300.00",2026-10-17,AUCTION,eBay,https://www.ebay.com/itm/1
`

describe('parseCsv', () => {
  it('handles quoted commas, doubled quotes and CRLF', () => {
    expect(parseCsv('a,b\r\n"x, y","say ""hi"""\r\n')).toEqual([
      ['a', 'b'],
      ['x, y', 'say "hi"'],
    ])
  })
})

describe('compsFromCsv', () => {
  it("keeps this card's sold, graded rows and says why it leaves out the rest", () => {
    const rows = compsFromCsv(CSV, '4', '2026-10-21')
    expect(rows.filter((r) => r.comp).map((r) => [r.comp!.grade, r.comp!.price, r.comp!.marketplace])).toEqual([
      [10, 28750, 'Heritage Auctions'],
      [9, 3851, 'eBay'],
      [8.5, 1300, 'eBay'],
    ])
    expect(rows.find((r) => r.line === 2)!.comp).toMatchObject({
      gradingCompany: 'PSA',
      soldOn: '2026-10-15',
      listingType: 'UNKNOWN',
      url: 'https://www.ha.com/itm/-/-/-/a/7459-36047.s',
      rawCondition: null,
    })
    expect(rows.filter((r) => r.skipped).map((r) => [r.line, r.skipped])).toEqual([
      [4, 'for card 215/203, not 4'],
      [5, 'no sold date: sold listings only, never asking prices'],
      [6, 'not a graded sale: enter raw sales by hand, with their condition'],
    ])
  })

  it('refuses a sale dated in the future, and a file without the needed columns', () => {
    expect(compsFromCsv('grader,grade,price,sold_on\nPSA,9,10,2030-01-01\n', null, '2026-10-21')[0].skipped).toBe('sold date in the future')
    expect(compsFromCsv('card,price\nx,1\n', null, '2026-10-21')[0].skipped).toBe('The header has no grader, grade, sold_on column.')
  })
})

describe('CompCsvImport', () => {
  it('saves each row as a manual comp, counting ones already recorded', async () => {
    const user = userEvent.setup()
    const posts: unknown[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => {
        posts.push(JSON.parse(String(init?.body)))
        return Promise.resolve(posts.length === 2 ? respond(409, { detail: 'This sale is already recorded' }) : respond(201, {}))
      }),
    )
    const onAdded = vi.fn()
    render(<CompCsvImport base="/api/admin/catalog-cards/cat1" number="4" onAdded={onAdded} />)

    await user.click(screen.getByText('Import sold comps from a CSV'))
    await user.upload(screen.getByLabelText('Comps CSV file'), new File([CSV], 'comps.csv', { type: 'text/csv' }))
    expect(await screen.findByText('3 sold comps to import, 3 left out.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Import 3 sold comps' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Saved 2, 1 already recorded. Refresh prices to use them.')
    await waitFor(() => expect(onAdded).toHaveBeenCalled())
    expect(posts).toHaveLength(3)
  })
})

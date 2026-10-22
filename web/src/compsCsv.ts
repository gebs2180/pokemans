import type { CompRequest, GradingCompany, ListingType } from './api'

const COMPANIES: GradingCompany[] = ['PSA', 'CGC', 'BGS', 'SGC', 'TAG', 'ACE']
const LISTING_TYPES: ListingType[] = ['AUCTION', 'FIXED_PRICE', 'BEST_OFFER_ACCEPTED', 'PRIVATE_SALE', 'UNKNOWN']

/** One CSV row: the comp it becomes, or why it's left out. */
export type CsvRow = { line: number; comp: CompRequest | null; skipped: string | null; label: string }

/** Splits CSV text into rows of fields; quoted fields may hold commas, quotes ("") and line breaks. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        field += '"'
        i++
      } else if (c === '"') {
        quoted = false
      } else {
        field += c
      }
    } else if (c === '"') {
      quoted = true
    } else if (c === ',') {
      row.push(field)
      field = ''
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++
      row.push(field)
      rows.push(row)
      row = []
      field = ''
    } else {
      field += c
    }
  }
  if (field !== '' || row.length > 0) {
    row.push(field)
    rows.push(row)
  }
  return rows.filter((r) => r.some((f) => f.trim() !== ''))
}

/** The collector number before the slash, without leading zeros: "004/102" and "4" are both "4". */
function numberKey(n: string) {
  return n.split('/')[0].trim().replace(/^0+(?=.)/, '').toLowerCase()
}

/**
 * Comps from a CSV with the columns of comps.csv: card, set, number, grader, grade, price, sold_on,
 * listing_type, marketplace, url. Rows for another card number, without a sold date, or that aren't a
 * graded sale are left out with the reason: only sold listings, never asking prices.
 *
 * @param number the identified card's number, to leave out rows for other cards
 * @param today  YYYY-MM-DD: a sale can't be in the future
 */
export function compsFromCsv(text: string, number: string | null, today: string): CsvRow[] {
  const [header, ...rows] = parseCsv(text)
  if (!header) return []
  const col = Object.fromEntries(header.map((h, i) => [h.trim().toLowerCase(), i]))
  const missing = ['grader', 'grade', 'price', 'sold_on'].filter((c) => col[c] === undefined)
  if (missing.length > 0) {
    return [{ line: 1, comp: null, skipped: `The header has no ${missing.join(', ')} column.`, label: header.join(',') }]
  }
  const get = (r: string[], name: string) => (col[name] === undefined ? '' : (r[col[name]] ?? '').trim())
  return rows.map((r, i) => {
    const line = i + 2
    const grader = get(r, 'grader').toUpperCase() as GradingCompany
    const grade = Number(get(r, 'grade'))
    const price = Number(get(r, 'price').replace(/[$,]/g, ''))
    const soldOn = get(r, 'sold_on')
    const label = [get(r, 'card'), get(r, 'number'), `${grader} ${get(r, 'grade')}`, get(r, 'price'), soldOn].filter(Boolean).join(' · ')
    const skip = (why: string): CsvRow => ({ line, comp: null, skipped: why, label })
    if (number && get(r, 'number') && numberKey(get(r, 'number')) !== numberKey(number)) return skip(`for card ${get(r, 'number')}, not ${number}`)
    if (!/^\d{4}-\d{2}-\d{2}$/.test(soldOn)) return skip('no sold date: sold listings only, never asking prices')
    if (soldOn > today) return skip('sold date in the future')
    if (!COMPANIES.includes(grader)) return skip('not a graded sale: enter raw sales by hand, with their condition')
    if (!(grade >= 1 && grade <= 10 && Number.isInteger(grade * 2))) return skip('grade must be 1 to 10, in halves')
    if (!(price > 0)) return skip('no price')
    const type = get(r, 'listing_type').toUpperCase() as ListingType
    const url = get(r, 'url')
    return {
      line,
      label,
      skipped: null,
      comp: {
        gradingCompany: grader,
        grade,
        rawCondition: null,
        price: Math.round(price * 100) / 100,
        soldOn,
        listingType: LISTING_TYPES.includes(type) ? type : 'UNKNOWN',
        marketplace: get(r, 'marketplace') || 'Unknown',
        url: /^https?:\/\//i.test(url) ? url : null,
        note: 'Imported from CSV',
      },
    }
  })
}

import { useState, type FormEvent } from 'react'
import {
  apiPath,
  useApi,
  type CompRequest,
  type CompView,
  type GradeRow,
  type GradingCompany,
  type ListingType,
  type PricesView,
  type RawCondition,
} from '../api'
import { safeHref } from '../safeHref'
import { useLoad } from '../useLoad'

const COMPANIES: GradingCompany[] = ['PSA', 'CGC', 'BGS', 'SGC', 'TAG', 'ACE']
const RAW_CONDITIONS: RawCondition[] = ['NEAR_MINT', 'LIGHTLY_PLAYED', 'MODERATELY_PLAYED', 'HEAVILY_PLAYED', 'DAMAGED', 'ALTERED']
const LISTING_TYPES: ListingType[] = ['AUCTION', 'FIXED_PRICE', 'BEST_OFFER_ACCEPTED', 'PRIVATE_SALE', 'UNKNOWN']
const METHOD_LABEL = {
  OBSERVED: 'sold comps',
  INTERPOLATED: 'interpolated',
  STEP_DOWN: 'era step-down',
  CARD_STEP_DOWN: "card's own step-down",
  CARRIED_DOWN: 'lower grade, never stepped up',
  NO_SALES: 'no sales, $0',
} as const

function message(err: unknown) {
  return err instanceof Error ? err.message : String(err)
}

function label(value: string) {
  return value.replaceAll('_', ' ').toLowerCase()
}

function money(value: number) {
  return `$${value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

/** Today as YYYY-MM-DD in the reviewer's own time zone. */
function today() {
  return new Date().toLocaleDateString('en-CA')
}

/** Admin: sold comps for the identified catalog card, entered by hand, and its price table per grader. */
export function PricesPanel({ catalogCardId }: { catalogCardId: string }) {
  const api = useApi()
  const base = apiPath`/api/admin/catalog-cards/${catalogCardId}`
  const { data, error, reload } = useLoad(() => api<PricesView>('GET', `${base}/prices`), [api, base])
  const [skipped, setSkipped] = useState<string[] | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function refresh() {
    setErr(null)
    setBusy(true)
    try {
      const result = await api<PricesView>('POST', `${base}/prices/refresh`)
      setSkipped(result.skipped)
      reload()
    } catch (e) {
      setErr(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="panel">
      <h2>Sold comps and prices</h2>
      <p className="muted small">
        Enter sold listings only, never asking prices. Prices are gross, before grading costs, and each grader is priced
        on its own.
      </p>
      {error && <p className="error" role="alert">{error}</p>}
      <CompForm base={base} onAdded={reload} />
      {data && <CompList comps={data.comps} />}
      <p>
        <button onClick={refresh} disabled={busy}>
          {busy ? 'Pricing…' : 'Refresh prices'}
        </button>{' '}
        <span className="muted small">Re-prices every card from the stored comps.</span>
      </p>
      {skipped && skipped.length > 0 && (
        <ul className="warn small">
          {skipped.map((s) => (
            <li key={s}>Not priced: {s}</li>
          ))}
        </ul>
      )}
      {data && data.tables.length === 0 && <p className="muted">No price table yet. Add comps, then refresh.</p>}
      {data?.tables.map((t) => (
        <div key={t.snapshotSetId}>
          <h3>
            {t.gradingCompany} <span className="muted small">as of {t.asOf}</span>
          </h3>
          {t.staleLevels > 0 && (
            <p className="error" role="alert">
              Stale: priced {t.daysOld} days ago and not replaced by a newer refresh, so confidence is down{' '}
              {t.staleLevels} level{t.staleLevels > 1 ? 's' : ''}.
            </p>
          )}
          <PriceTableView grades={t.grades} />
          <p className="muted small">
            σ {t.sigma === null ? `not enough history (${t.sigmaChanges} monthly changes)` : `${(t.sigma * 100).toFixed(1)}%`} ·{' '}
            {t.salesPerMonth} sales/month (all sales of the card) · parameters {t.parameterSet}
          </p>
        </div>
      ))}
      {err && <p className="error" role="alert">{err}</p>}
    </section>
  )
}

export function CompForm({ base, onAdded }: { base: string; onAdded: () => void }) {
  const api = useApi()
  const [kind, setKind] = useState<'graded' | 'raw'>('graded')
  const [company, setCompany] = useState<GradingCompany>('PSA')
  const [grade, setGrade] = useState('')
  const [raw, setRaw] = useState<RawCondition>('NEAR_MINT')
  const [price, setPrice] = useState('')
  const [soldOn, setSoldOn] = useState(today())
  const [listingType, setListingType] = useState<ListingType>('AUCTION')
  const [marketplace, setMarketplace] = useState('eBay')
  const [url, setUrl] = useState('')
  const [note, setNote] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [saved, setSaved] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setErr(null)
    setSaved(null)
    const body: CompRequest = {
      gradingCompany: kind === 'graded' ? company : null,
      grade: kind === 'graded' ? Number(grade) : null,
      rawCondition: kind === 'raw' ? raw : null,
      price: Number(price),
      soldOn,
      listingType,
      marketplace: marketplace.trim(),
      url: url.trim() || null,
      note: note.trim() || null,
    }
    setBusy(true)
    try {
      await api('POST', `${base}/comps`, body)
      setSaved(`Saved ${kind === 'graded' ? `${company} ${grade}` : label(raw)} at ${money(body.price)}.`)
      setPrice('')
      setUrl('')
      setNote('')
      onAdded()
    } catch (e) {
      setErr(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="stack">
      <div className="row">
        <select value={kind} onChange={(e) => setKind(e.target.value as 'graded' | 'raw')} aria-label="Sale type">
          <option value="graded">Graded sale</option>
          <option value="raw">Raw sale</option>
        </select>
        {kind === 'graded' ? (
          <>
            <select value={company} onChange={(e) => setCompany(e.target.value as GradingCompany)} aria-label="Grading company">
              {COMPANIES.map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
            <input
              type="number"
              min={1}
              max={10}
              step={0.5}
              value={grade}
              onChange={(e) => setGrade(e.target.value)}
              placeholder="Grade, e.g. 9 or 8.5"
              aria-label="Grade"
              required
            />
          </>
        ) : (
          <select value={raw} onChange={(e) => setRaw(e.target.value as RawCondition)} aria-label="Raw condition">
            {RAW_CONDITIONS.map((c) => (
              <option key={c} value={c}>
                {label(c)}
              </option>
            ))}
          </select>
        )}
      </div>
      <div className="row">
        <input
          type="number"
          min={0.01}
          step={0.01}
          value={price}
          onChange={(e) => setPrice(e.target.value)}
          placeholder="Sold price (USD)"
          aria-label="Sold price (USD)"
          required
        />
        <input type="date" value={soldOn} max={today()} onChange={(e) => setSoldOn(e.target.value)} aria-label="Sold on" required />
        <select value={listingType} onChange={(e) => setListingType(e.target.value as ListingType)} aria-label="Listing type">
          {LISTING_TYPES.map((t) => (
            <option key={t} value={t}>
              {label(t)}
            </option>
          ))}
        </select>
      </div>
      <div className="row">
        <input value={marketplace} onChange={(e) => setMarketplace(e.target.value)} placeholder="Marketplace" aria-label="Marketplace" required />
        <input type="url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="Sold listing URL" aria-label="Sold listing URL" />
      </div>
      <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Note (optional)" aria-label="Note" />
      <div>
        <button type="submit" disabled={busy}>
          Add sold comp
        </button>
      </div>
      {saved && <p className="ok small">{saved}</p>}
      {err && <p className="error" role="alert">{err}</p>}
    </form>
  )
}

function CompList({ comps }: { comps: CompView[] }) {
  if (comps.length === 0) return <p className="muted">No comps yet.</p>
  return (
    <table className="table">
      <thead>
        <tr>
          <th>Sold</th>
          <th>Grade</th>
          <th>Price</th>
          <th>Where</th>
          <th>Source</th>
        </tr>
      </thead>
      <tbody>
        {comps.map((c) => {
          const href = safeHref(c.url)
          return (
            <tr key={c.id}>
              <td>{c.soldOn}</td>
              <td>{c.gradingCompany ? `${c.gradingCompany} ${c.grade}` : `raw, ${label(c.rawCondition ?? '')}`}</td>
              <td>{money(c.price)}</td>
              <td>
                {href ? (
                  <a href={href} target="_blank" rel="noreferrer">
                    {String(c.details.marketplace ?? 'listing')}
                  </a>
                ) : (
                  String(c.details.marketplace ?? '–')
                )}{' '}
                <span className="muted small">{label(c.listingType)}</span>
              </td>
              <td className="muted small">{c.source.toLowerCase()}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function PriceTableView({ grades }: { grades: GradeRow[] }) {
  return (
    <table className="table">
      <thead>
        <tr>
          <th>Grade</th>
          <th>Price</th>
          <th>How</th>
          <th>Conf.</th>
          <th>Sales</th>
        </tr>
      </thead>
      <tbody>
        {[...grades].reverse().map((g) => (
          <tr key={g.grade}>
            <td>{g.grade}</td>
            <td>{money(g.price)}</td>
            <td className="small">
              {METHOD_LABEL[g.method]}
              {g.basis.length > 0 && <span className="muted"> from {g.basis.join(' & ')}</span>}
              {g.stepDownRatio !== null && <span className="muted"> × {g.stepDownRatio}</span>}
            </td>
            <td>
              {g.confidence}
              {g.confidence !== g.pricedConfidence && <span className="muted small"> (was {g.pricedConfidence})</span>}
            </td>
            <td className="muted small">{g.method === 'OBSERVED' ? `${g.n} in ${g.windowDays} days` : '–'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { apiPath, money, useApi, type GradingCost, type RunSummary, type ValuationDefaults } from '../api'
import { useLoad } from '../useLoad'

/** Admin: value a collection (one run per click, kept forever) and list its runs. */
export function ValuationPage() {
  const { orgId = '', collectionId = '' } = useParams()
  const api = useApi()
  const navigate = useNavigate()
  const runsPath = apiPath`/api/admin/orgs/${orgId}/collections/${collectionId}/valuation-runs`
  const { data: runs, error } = useLoad(() => api<RunSummary[]>('GET', runsPath), [api, runsPath])
  const { data: defaults } = useLoad(() => api<ValuationDefaults>('GET', '/api/admin/valuation/defaults'), [api])

  if (error) return <p className="error" role="alert">{error}</p>
  return (
    <>
      <p>
        <Link to={`/orgs/${orgId}/collections/${collectionId}`}>← Collection</Link>
      </p>
      <h1>Valuation runs</h1>
      <p className="muted">
        A run values every card in the collection with today's assessments, price tables and provenance, and stores all of it, so
        its reports can be reproduced later.
      </p>
      {defaults && <StartRun defaults={defaults} onStarted={(id) => navigate(`/admin/valuation-runs/${id}`)} path={runsPath} />}
      <h2>Runs</h2>
      {!runs && <p>Loading…</p>}
      {runs?.length === 0 && <p className="muted">No runs yet.</p>}
      <ul className="list">
        {runs?.map((r) => (
          <li key={r.id}>
            <Link to={`/admin/valuation-runs/${r.id}`}>{new Date(r.createdAt).toLocaleString()}</Link>{' '}
            <span className="muted">
              base {money(r.collateralBase)}, {r.eligibleCount} of {r.cardCount} cards eligible
              {r.note && ` · ${r.note}`}
            </span>
          </li>
        ))}
      </ul>
    </>
  )
}

export function StartRun({
  defaults,
  path,
  onStarted,
}: {
  defaults: ValuationDefaults
  path: string
  onStarted: (id: string) => void
}) {
  const api = useApi()
  const [cost, setCost] = useState<GradingCost>(defaults.gradingCost)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function start(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const run = await api<RunSummary>('POST', path, { gradingCost: cost, note: note || null })
      onStarted(run.id)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const field = (key: keyof GradingCost, label: string, step: string) => (
    <label>
      {label}{' '}
      <input
        type="number"
        min={0}
        step={step}
        value={cost[key]}
        onChange={(e) => setCost({ ...cost, [key]: Number(e.target.value) })}
        className="narrow"
        aria-label={label}
        required
      />
    </label>
  )

  return (
    <form onSubmit={start} className="stack panel">
      <p className="muted">
        Parameter set {defaults.parameterSet}. Raw cards are valued on {defaults.gradingCompany} price tables, net of the grading
        cost of the day, per card:
      </p>
      <div className="row">
        {field('fee', 'Grading fee ($)', '0.01')}
        {field('shipping', 'Shipping ($)', '0.01')}
        {field('insurance', 'Insurance ($)', '0.01')}
        {field('timeToCashRate', 'Time-to-cash rate', '0.001')}
      </div>
      <input
        value={note}
        onChange={(e) => setNote(e.target.value)}
        placeholder="Note (optional)"
        aria-label="Note"
        maxLength={1000}
      />
      <button type="submit" disabled={busy}>
        {busy ? 'Valuing…' : 'Run valuation'}
      </button>
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  )
}

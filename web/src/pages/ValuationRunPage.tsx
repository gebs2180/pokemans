import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { apiPath, money, percent, useApi, type CardLine, type ReportType, type ReportView, type RunDetail } from '../api'
import { useLoad } from '../useLoad'
import { ReportsList } from './ReportsPanel'

const LABEL = (s: string) => s.replace(/_/g, ' ').toLowerCase()

/** Admin: one valuation run with every card's numbers and trace, the grade-first list, and report issuing. */
export function ValuationRunPage() {
  const { runId = '' } = useParams()
  const api = useApi()
  const { data, error } = useLoad(() => api<RunDetail>('GET', apiPath`/api/admin/valuation-runs/${runId}`), [api, runId])

  if (error) return <p className="error" role="alert">{error}</p>
  if (!data) return <p>Loading…</p>
  const { run } = data
  const gradeFirst = data.cards.filter((c) => c.gradeFirstRank != null).sort((a, b) => a.gradeFirstRank! - b.gradeFirstRank!)
  const adjustments = data.portfolioTrace.filter((s) => s.subject.startsWith('capped[') || s.subject.startsWith('correlation['))
  const caps = data.portfolioTrace.find((s) => s.subject === 'concentration_caps')

  return (
    <>
      <p>
        <Link to={`/admin/valuation/${run.orgId}/${run.collectionId}`}>← Runs for {run.collectionName}</Link>
      </p>
      <h1>Valuation of {run.collectionName}</h1>
      <p className="muted">
        As of {run.asOf}, parameter set {run.parameterSetVersion}, {run.gradingCompany} tables, grading cost{' '}
        {money(run.gradingCost.fee)} + {money(run.gradingCost.shipping)} + {money(run.gradingCost.insurance)} per card and{' '}
        {percent(run.gradingCost.timeToCashRate)} time-to-cash. Inputs SHA-256 <code>{run.inputsSha256.slice(0, 16)}…</code>
        {run.note && ` · ${run.note}`}
      </p>

      <div className="row tiles">
        <Tile
          label="Collateral base"
          value={money(run.collateralBase)}
          note={`eligible FLV before caps ${money(run.eligibleFlv)}`}
        />
        <Tile
          label="Suggested advance"
          value={`${money(run.advanceLow)} – ${money(run.advanceHigh)}`}
          note={`alpha ${percent(run.alphaLow)} to ${percent(run.alphaHigh)}; the lender sets alpha`}
        />
        <Tile label="Eligible" value={`${run.eligibleCount} of ${run.cardCount}`} note={`${run.valuedCount} valued`} />
        <Tile
          label="Raw / graded"
          value={`${percent(run.rawShare)} / ${percent(run.gradedShare)}`}
          note={Object.entries(data.confidenceMix)
            .map(([k, v]) => `${k}: ${v}`)
            .join(', ')}
        />
        <Tile label="Insured (RV)" value={money(run.totalRv)} note="insurer report total" />
      </div>

      <h2>Caps and haircuts</h2>
      <p>
        {run.capsApplied
          ? `Every card capped at ${caps ? money(Number(caps.value)) : '–'}: no card above 20% of the final base, top five at most 50%.`
          : `Caps not applied: ${run.eligibleCount} eligible cards, fewer than the ${run.capsMinCards} the caps need.`}
      </p>
      {adjustments.length > 0 && (
        <ul className="list small">
          {adjustments.map((s) => (
            <li key={s.subject}>
              {nameIn(s.subject, data.cards)}: {s.rule} → {s.value} <span className="muted">{s.detail}</span>
            </li>
          ))}
        </ul>
      )}

      <h2>Cards</h2>
      <div className="scroll">
        <table className="table">
          <thead>
            <tr>
              <th>Card</th>
              <th>Conf.</th>
              <th>FMV</th>
              <th>RV</th>
              <th>QSV</th>
              <th>FLV</th>
              <th>In base</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {data.cards.map((c) => (
              <CardRow key={c.cardItemId} card={c} />
            ))}
          </tbody>
        </table>
      </div>

      <h2>Grade first</h2>
      {gradeFirst.length === 0 && <p className="muted">No raw card has a lift above 1.0.</p>}
      {gradeFirst.length > 0 && (
        <div className="scroll">
          <table className="table">
            <thead>
              <tr>
                <th>#</th>
                <th>Card</th>
                <th>Cost C</th>
                <th>FLV raw</th>
                <th>FLV graded</th>
                <th>Lift</th>
              </tr>
            </thead>
            <tbody>
              {gradeFirst.map((c) => (
                <tr key={c.cardItemId}>
                  <td>{c.gradeFirstRank}</td>
                  <td>{name(c)}</td>
                  <td>{money(c.gradingCost)}</td>
                  <td>{money(c.flvRawForLift)}</td>
                  <td>{money(c.flvGraded)}</td>
                  <td>{c.lift?.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Reports runId={run.id} />
    </>
  )
}

function Tile({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="panel">
      <div className="muted small">{label}</div>
      <strong>{value}</strong>
      {note && <div className="muted small">{note}</div>}
    </div>
  )
}

function name(c: CardLine) {
  return c.inputs.identity ? `${c.inputs.identity.name} (${c.inputs.label})` : c.inputs.label
}

/** Replaces a card id in a trace subject with the card's name. */
function nameIn(subject: string, cards: CardLine[]) {
  const id = /\[([0-9a-f-]{36})]/.exec(subject)?.[1]
  const card = id && cards.find((c) => c.cardItemId === id)
  return card ? name(card) : subject
}

function CardRow({ card: c }: { card: CardLine }) {
  const [open, setOpen] = useState(false)
  const status =
    c.status === 'UNVALUED'
      ? `not valued: ${c.unvaluedReason}`
      : c.eligible
        ? 'eligible'
        : `excluded: ${c.exclusionReasons
            .map((r) =>
              r === 'unresolved authenticity flag'
                ? `${r} (${LABEL(c.inputs.provenance.authState)})`
                : r === 'custody not verified'
                  ? `${r} (${LABEL(c.inputs.provenance.custodyState)})`
                  : r,
            )
            .join('; ')}`
  return (
    <>
      <tr>
        <td>
          <button className="link" onClick={() => setOpen(!open)} aria-expanded={open}>
            {name(c)}
          </button>
          {c.graded && c.inputs.graded && (
            <span className="muted">
              {' '}
              {c.inputs.graded.gradingCompany} {c.inputs.graded.grade}
            </span>
          )}
        </td>
        <td>{c.confidence ?? '–'}</td>
        <td>{money(c.fmv)}</td>
        <td>{money(c.insuredRv)}</td>
        <td>{money(c.qsv)}</td>
        <td>{money(c.flv)}</td>
        <td>{money(c.contribution)}</td>
        <td className={c.eligible ? 'ok' : 'warn'}>{status}</td>
      </tr>
      {open && (
        <tr>
          <td colSpan={8}>
            {c.insurerNote && <p className="muted">Insurer: {c.insurerNote}</p>}
            {c.trace.length === 0 ? (
              <p className="muted">Nothing was valued for this card.</p>
            ) : (
              <ol className="small">
                {c.trace.map((s, i) => (
                  <li key={i}>
                    <code>{s.subject}</code> = {s.value}{' '}
                    <span className="muted">
                      [{s.rule}] {s.detail}
                    </span>
                  </li>
                ))}
              </ol>
            )}
          </td>
        </tr>
      )}
    </>
  )
}

function Reports({ runId }: { runId: string }) {
  const api = useApi()
  const path = apiPath`/api/admin/valuation-runs/${runId}/reports`
  const { data, error, reload } = useLoad(() => api<ReportView[]>('GET', path), [api, path])
  const [alpha, setAlpha] = useState('')
  const [busy, setBusy] = useState(false)
  const [issueError, setIssueError] = useState<string | null>(null)

  async function issue(type: ReportType) {
    setIssueError(null)
    setBusy(true)
    try {
      const lenderAlpha = type === 'LENDER' && alpha !== '' ? Number(alpha) / 100 : null
      await api('POST', path, { type, lenderAlpha })
      reload()
    } catch (e) {
      setIssueError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <h2>Reports</h2>
      <div className="row">
        <label>
          Lender's alpha, if they gave one (%){' '}
          <input
            type="number"
            min={0}
            max={100}
            step="0.1"
            value={alpha}
            onChange={(e) => setAlpha(e.target.value)}
            className="narrow"
            aria-label="Lender's alpha (%)"
          />
        </label>
        <button onClick={() => issue('LENDER')} disabled={busy}>
          Issue lender report
        </button>
        <button onClick={() => issue('INSURER')} disabled={busy}>
          Issue insurer report
        </button>
      </div>
      {issueError && <p className="error" role="alert">{issueError}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {data && <ReportsList reports={data} downloadPath={(id) => apiPath`/api/admin/reports/${id}/download`} />}
    </>
  )
}

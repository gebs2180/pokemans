import { Link } from 'react-router'
import { money, OUTCOMES, useApi, type AdminCalibration, type AdminDealOutcomeRow, type CalibrationSummary } from '../api'
import { safeHref } from '../safeHref'
import { useLoad } from '../useLoad'
import { EmptyState, Loading, PageError } from '../ui'

const CERT_STATUS = { UNVERIFIED: 'cert not checked', VERIFIED: 'cert checked', MISMATCH: "cert didn't match" } as const

function percent(n: number, of: number) {
  return of === 0 ? '–' : `${Math.round((100 * n) / of)}%`
}

/** One summary line against the validation targets: within ±1 at least 80%, below the stressed median under 5%. */
function Targets({ title, s }: { title: string; s: CalibrationSummary }) {
  return (
    <tr>
      <th scope="row">{title}</th>
      <td>{s.graded}</td>
      <td className={s.graded > 0 && s.withinOne / s.graded < 0.8 ? 'warn' : undefined}>
        {s.withinOne} ({percent(s.withinOne, s.graded)})
      </td>
      <td className={s.graded > 0 && s.belowStressed / s.graded >= 0.05 ? 'warn' : undefined}>
        {s.belowStressed} ({percent(s.belowStressed, s.graded)})
      </td>
    </tr>
  )
}

function Returned({ row }: { row: AdminDealOutcomeRow }) {
  const o = row.outcome
  if (o.kind === 'RESOLD_RAW') return <>Resold raw for {money(o.resalePrice)}</>
  if (o.kind === 'KEPT') return <>Kept</>
  return (
    <>
      {o.grader} {o.grade ?? o.gradeLabel}
      {o.grade != null && o.gradeLabel && ` (${o.gradeLabel})`} · cert{' '}
      {o.lookupUrl ? (
        <a href={safeHref(o.lookupUrl)} target="_blank" rel="noreferrer">
          {o.certNumber}
        </a>
      ) : (
        o.certNumber
      )}{' '}
      · {o.certStatus && CERT_STATUS[o.certStatus]}
    </>
  )
}

function Distribution({ predicted }: { predicted: Record<string, number> }) {
  return (
    <span className="small muted">
      {OUTCOMES.filter((g) => (predicted[g] ?? 0) > 0)
        .map((g) => `${g}: ${Math.round((predicted[g] ?? 0) * 100)}%`)
        .join(' · ')}
    </span>
  )
}

/**
 * Admin: what buyers said happened to the cards they bought, against the condition each offer predicted.
 * Only the buyer records an outcome; a cert counts as checked once it's verified on Graded results.
 */
export function AdminDealOutcomesPage() {
  const api = useApi()
  const { data, error } = useLoad(() => api<AdminCalibration>('GET', '/api/admin/deal-outcomes'), [api])

  if (error) return <PageError message={error} />
  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link>
      </p>
      <h1>Deal outcomes</h1>
      <p className="muted">
        What buyers said happened to the cards they bought, newest first, against the offer's predicted condition. Targets: the grade
        within one of the predicted median at least 80% of the time, below the stressed median under 5%. Certs that didn't match are
        left out of the counts; check new certs on <Link to="/admin/outcomes">Graded results</Link>.
      </p>
      {!data && <Loading />}
      {data && (
        <>
          <table>
            <thead>
              <tr>
                <th scope="col"></th>
                <th scope="col">Graded</th>
                <th scope="col">Within ±1</th>
                <th scope="col">Below stressed median</th>
              </tr>
            </thead>
            <tbody>
              <Targets title="All" s={data.all} />
              <Targets title="Cert checked" s={data.verified} />
            </tbody>
          </table>
          <p className="small">
            Also {data.resold} resold raw and {data.kept} kept.
          </p>
          {data.rows.length === 0 && (
            <EmptyState title="No outcomes yet.">
              <p>Buyers record them on their deals once a card has arrived.</p>
            </EmptyState>
          )}
          {data.rows.map((row) => (
            <section key={row.listingId} className="panel" aria-label={row.card.name}>
              <div className="listing-top">
                <div>
                  <strong>{row.card.name}</strong>
                  <span className="muted small">
                    {' '}
                    · {row.card.setName} {row.card.number}
                  </span>
                </div>
                <p>{money(row.offerPrice)}</p>
              </div>
              <p className="small">
                Bought by {row.buyer ?? 'a buyer'} · confidence {row.confidence ?? '–'} · recorded{' '}
                {new Date(row.outcome.recordedAt).toLocaleDateString()}
                {row.outcome.earlier > 0 && ` (corrects ${row.outcome.earlier})`}
              </p>
              {row.median ? (
                <p className="small">
                  Predicted median <strong>{row.median}</strong>, stressed median <strong>{row.stressedMedian}</strong> (shifted{' '}
                  {row.stressShift} down) · {row.predicted && <Distribution predicted={row.predicted} />}
                </p>
              ) : (
                <p className="small muted">Offered as already graded: no condition was predicted.</p>
              )}
              <p>
                <Returned row={row} />
              </p>
              {row.returnedGrade && (
                <p className="small">
                  Counts as {row.returnedGrade}:{' '}
                  <span className={row.withinOne ? 'ok' : 'warn'}>{row.withinOne ? 'within one' : 'more than one off'}</span>
                  {row.belowStressed && (
                    <>
                      {' '}
                      · <span className="warn">below the stressed median</span>
                    </>
                  )}
                </p>
              )}
              {row.outcome.note && <p className="small">Buyer's note: "{row.outcome.note}"</p>}
            </section>
          ))}
        </>
      )}
    </>
  )
}

import { Link } from 'react-router'
import { useApi, type QueueItem } from '../api'
import { useLoad } from '../useLoad'

const ASSESSMENT_LABEL: Record<QueueItem['assessment'], string> = {
  NONE: 'No distribution yet',
  SYSTEM: 'System distribution, not reviewed',
  REVIEWER: 'Reviewed',
}

/** Admin: cards whose photos are all in, and what each still needs. */
export function ReviewQueuePage() {
  const api = useApi()
  const { data: items, error, reload } = useLoad(() => api<QueueItem[]>('GET', '/api/admin/review-queue'), [api])

  if (error) return <p className="error" role="alert">{error}</p>
  if (!items) return <p>Loading…</p>

  // A card isn't done until it can be priced: without comps its seller is left waiting.
  const finished = (i: QueueItem) => i.identified && i.assessment === 'REVIEWER' && i.priced
  const todo = items.filter((i) => !finished(i))
  const done = items.filter(finished)
  const needComps = todo.filter((i) => i.identified && !i.priced).length
  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link>
      </p>
      <h1>Review queue</h1>
      <p className="muted">
        Cards with a passing front and back. {todo.length} to review{needComps > 0 && ` (${needComps} need comps)`}, {done.length} done.{' '}
        <button className="link" onClick={reload}>
          Refresh
        </button>
      </p>
      <QueueTable items={todo} />
      {done.length > 0 && (
        <details>
          <summary>Reviewed ({done.length})</summary>
          <QueueTable items={done} />
        </details>
      )}
    </>
  )
}

function QueueTable({ items }: { items: QueueItem[] }) {
  if (items.length === 0) return <p className="muted">Nothing here.</p>
  return (
    <table className="table">
      <thead>
        <tr>
          <th>Card</th>
          <th>Identity</th>
          <th>Condition</th>
        </tr>
      </thead>
      <tbody>
        {items.map((i) => (
          <tr key={i.cardId}>
            <td>
              <Link to={`/admin/review/${i.orgId}/${i.cardId}`}>{i.label}</Link>
              <div className="muted small">
                {i.orgName ?? 'Unknown org'} · {new Date(i.createdAt).toLocaleDateString()}
              </div>
            </td>
            <td className={i.identified ? '' : 'warn'}>
              {i.identifiedAs ?? 'Not identified'}
              {i.identified && !i.priced && <div className="warn small">Needs comps: no PSA prices yet</div>}
            </td>
            <td className={i.assessment === 'REVIEWER' ? 'ok' : 'warn'}>
              {ASSESSMENT_LABEL[i.assessment]}
              {i.confidence && ` · ${i.confidence}`}
              {i.openRequests > 0 && <div className="muted small">Waiting on {i.openRequests} provider answer(s)</div>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

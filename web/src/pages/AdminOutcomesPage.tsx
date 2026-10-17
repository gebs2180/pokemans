import { useState } from 'react'
import { Link } from 'react-router'
import { useApi, type OutcomeView } from '../api'
import { useLoad } from '../useLoad'

type Filter = 'UNVERIFIED' | 'VERIFIED' | 'MISMATCH' | 'ALL'

/** Admin: check contributors' certs against the grader's public lookup, by hand. */
export function AdminOutcomesPage() {
  const api = useApi()
  const [filter, setFilter] = useState<Filter>('UNVERIFIED')
  const { data, error, reload } = useLoad(() => api<OutcomeView[]>('GET', `/api/admin/outcomes?status=${filter}`), [api, filter])

  if (error) return <p className="error">{error}</p>
  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link>
      </p>
      <h1>Graded results</h1>
      <p className="muted">
        Open the cert on the grader's site and check it shows this card at this grade. A result counts for calibration only
        once it is checked.
      </p>
      <div className="row">
        {(['UNVERIFIED', 'VERIFIED', 'MISMATCH', 'ALL'] as Filter[]).map((f) => (
          <button key={f} className={f === filter ? '' : 'link'} onClick={() => setFilter(f)}>
            {f.toLowerCase()}
          </button>
        ))}
      </div>
      {!data && <p>Loading…</p>}
      {data && data.length === 0 && <p className="muted">Nothing here.</p>}
      {data?.map((o) => <OutcomeRow key={o.id} outcome={o} onChange={reload} />)}
    </>
  )
}

function OutcomeRow({ outcome: o, onChange }: { outcome: OutcomeView; onChange: () => void }) {
  const api = useApi()
  const [note, setNote] = useState('')
  const [err, setErr] = useState<string | null>(null)

  async function verify(status: 'VERIFIED' | 'MISMATCH') {
    setErr(null)
    try {
      await api('POST', `/api/admin/outcomes/${o.id}/verifications`, { status, note })
      onChange()
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <section className="panel">
      <p>
        <strong>
          {o.grader} {o.grade ?? ''} {o.gradeLabel ?? ''}
        </strong>{' '}
        · cert{' '}
        <a href={o.lookupUrl} target="_blank" rel="noreferrer">
          {o.certNumber}
        </a>{' '}
        · <Link to={`/admin/review/${o.orgId}/${o.cardId}`}>{o.cardLabel}</Link>
      </p>
      <p className="muted small">
        {o.status.toLowerCase()}
        {o.verificationNote && ` · ${o.verificationNote}`} · shared {new Date(o.createdAt).toLocaleDateString()}
      </p>
      <div className="row">
        <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="What the cert page shows" />
        <button onClick={() => verify('VERIFIED')}>Matches</button>
        <button className="link" onClick={() => verify('MISMATCH')}>
          Doesn't match
        </button>
      </div>
      {err && <p className="error">{err}</p>}
    </section>
  )
}

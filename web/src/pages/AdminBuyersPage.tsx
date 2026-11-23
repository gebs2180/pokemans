import { useState } from 'react'
import { Link } from 'react-router'
import { apiPath, useApi, type BuyerRequest } from '../api'
import { message } from '../format'
import { EmptyState, Field, Loading, PageError } from '../ui'
import { useLoad } from '../useLoad'

/**
 * Requests to buy from people who already sell here (Zach, 2026-11-22). Buying waits on this approval until shipping
 * labels are paid at accept. A decline says why, and the person sees it on their account page.
 */
export function AdminBuyersPage() {
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<BuyerRequest[]>('GET', '/api/admin/buyers'), [api])
  if (error) return <PageError message={error} />
  if (!data) return <Loading />

  const waiting = data.filter((r) => r.approved === null)
  const answered = data.filter((r) => r.approved !== null)
  return (
    <>
      <p>
        <Link to="/admin">← Admin</Link>
      </p>
      <h1>Requests to buy</h1>
      {waiting.length === 0 ? (
        <EmptyState title="Nobody is waiting.">New requests show up here.</EmptyState>
      ) : (
        <ul className="list">
          {waiting.map((r) => (
            <Waiting key={r.id} request={r} onDecided={reload} />
          ))}
        </ul>
      )}
      {answered.length > 0 && (
        <>
          <h2>Answered lately</h2>
          <ul className="list">
            {answered.map((r) => (
              <li key={r.id}>
                <Who request={r} /> <strong>{r.approved ? 'Approved' : 'Declined'}</strong>{' '}
                <span className="muted">
                  {r.decidedAt && new Date(r.decidedAt).toLocaleDateString()} by {r.decidedBy ?? 'someone since removed'}
                </span>
                {r.note && <p className="muted small">{r.note}</p>}
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  )
}

function Who({ request: r }: { request: BuyerRequest }) {
  return (
    <>
      <Link to={`/orgs/${r.orgId}`}>{r.orgName}</Link> <span className="muted">{r.email}</span>
      {r.sellerOrgs.length > 0 && <span className="muted"> · sells as {r.sellerOrgs.join(', ')}</span>}
      {r.attempt > 1 && <span className="muted"> · asking again ({r.attempt})</span>}
    </>
  )
}

function Waiting({ request: r, onDecided }: { request: BuyerRequest; onDecided: () => void }) {
  const api = useApi()
  const [declining, setDeclining] = useState(false)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function decide(approve: boolean) {
    setError(null)
    setBusy(true)
    try {
      if (approve) await api('POST', apiPath`/api/admin/buyers/${r.id}/approve`)
      else await api('POST', apiPath`/api/admin/buyers/${r.id}/decline`, { note })
      onDecided()
    } catch (err) {
      setError(message(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <li>
      <Who request={r} /> <span className="muted">asked {new Date(r.appliedAt).toLocaleDateString()}</span>
      <div className="actions">
        <button onClick={() => decide(true)} disabled={busy}>
          Approve
        </button>
        <button className="secondary" aria-expanded={declining} onClick={() => setDeclining(!declining)} disabled={busy}>
          Decline
        </button>
      </div>
      {declining && (
        <form
          className="form"
          onSubmit={(e) => {
            e.preventDefault()
            void decide(false)
          }}
        >
          <Field label="Why" hint="They see this on their account page.">
            {(control) => <textarea {...control} value={note} onChange={(e) => setNote(e.target.value)} required maxLength={2000} />}
          </Field>
          <div className="actions">
            <button type="submit" disabled={busy || !note.trim()}>
              Decline
            </button>
          </div>
        </form>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </li>
  )
}

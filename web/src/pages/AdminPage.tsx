import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { apiPath, ORG_TYPE_LABEL, useApi, type CreatedInvite, type DealAttention, type Org, type OrgType, type OverdueDeals, type PhotoLocationRun, type PokeTraceQueue, type PreGradeFailures, type QueueItem } from '../api'
import { useLoad } from '../useLoad'
import { Loading, PageError } from '../ui'
import { OrgPeople } from './OrgPeople'
import { message } from '../format'

/** How much is waiting on the admin, with the way to each. A failed count says so: zero would read as all clear. */
function NeedsAttention({ deals, dealsError }: { deals: DealAttention[] | null; dealsError: string | null }) {
  const api = useApi()
  const queue = useLoad(() => api<QueueItem[]>('GET', '/api/admin/review-queue'), [api])
  const overdue = useLoad(() => api<OverdueDeals>('GET', '/api/admin/deals/overdue'), [api])
  const poketrace = useLoad(() => api<PokeTraceQueue>('GET', '/api/admin/poketrace/queue'), [api])
  const failures = useLoad(() => api<PreGradeFailures>('GET', '/api/admin/pregrade-failures'), [api])
  const error = queue.error ?? overdue.error ?? poketrace.error ?? failures.error ?? dealsError
  if (error) return <p className="error" role="alert">Couldn't load what needs attention: {error}</p>
  if (!queue.data || !overdue.data || !poketrace.data || !failures.data || !deals) return null

  const f = failures.data
  const resumes = f.submitsResumeAt ? `; sending resumes ${new Date(f.submitsResumeAt).toLocaleString()}` : ''
  const lines = [
    { count: f.failedCards - f.outOfCreditsCards, text: 'whose automatic condition check failed: Ask again, or assess by hand', to: '/admin/review' },
    { count: f.outOfCreditsCards, text: `waiting on CardGrade, which is out of credits${resumes}: Ask again once it has some`, to: '/admin/review' },
    { count: queue.data.filter((i) => i.identified && !i.priced).length, text: 'with no comps', to: '/admin/review' },
    { count: deals.length, text: 'with a return or payment to sort out', to: '/admin/deals' },
    { count: overdue.data.labels, text: "past the ship-by date, not marked shipped", to: '/admin/deals' },
    { count: poketrace.data.items.length, text: 'to map on PokeTrace', to: '/admin/poketrace' },
  ].filter((l) => l.count > 0)
  return (
    <section className="panel">
      <h2>Needs attention: {lines.reduce((total, l) => total + l.count, 0)}</h2>
      {lines.length > 0 && (
        <ul>
          {lines.map((l) => (
            <li key={l.text}>
              <Link to={l.to}>
                {l.count} card{l.count === 1 ? '' : 's'} {l.text}
              </Link>
            </li>
          ))}
        </ul>
      )}
      {f.outOfCredits && f.outOfCreditsCards === 0 && <p className="muted">CardGrade is out of credits{resumes}. Cards sent now will fail.</p>}
    </section>
  )
}

/**
 * The one-off removal of location and device metadata from photos uploaded before it happened at upload. Each click
 * checks up to 200; click again until none remain.
 */
function PhotoLocation() {
  const api = useApi()
  const [run, setRun] = useState<PhotoLocationRun | null>(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function start() {
    setError(null)
    setRunning(true)
    try {
      setRun(await api<PhotoLocationRun>('POST', '/api/admin/photos/remove-location'))
    } catch (err) {
      setError(message(err))
    } finally {
      setRunning(false)
    }
  }

  return (
    <>
      <h2>Photo location</h2>
      <p className="muted">Removes GPS and device details from photos uploaded before this happened at upload. Pixels and when it was taken stay.</p>
      <button onClick={start} disabled={running || run?.remaining === 0}>
        {running ? 'Checking…' : run ? 'Check the next 200' : 'Check 200 photos'}
      </button>
      {error && <p className="error" role="alert">{error}</p>}
      {run && (
        <p role="status">
          Checked {run.checked}: {run.rewritten} rewritten, {run.clean} with nothing to remove
          {run.missing > 0 && `, ${run.missing} missing from storage`}
          {run.failed > 0 && `, ${run.failed} failed (see the logs)`}. {run.remaining === 0 ? 'None left.' : `${run.remaining} left.`}
        </p>
      )}
    </>
  )
}

export function AdminPage() {
  const api = useApi()
  const { data: orgs, error, reload } = useLoad(() => api<Org[]>('GET', '/api/admin/orgs'), [api])
  const { data: attention, error: attentionError } = useLoad(() => api<DealAttention[]>('GET', '/api/admin/deals/attention'), [api])
  const [name, setName] = useState('')
  // The offers beta needs collector and buyer orgs; partner orgs are for the paused collateral side.
  const [type, setType] = useState<OrgType>('COLLECTOR')
  const [invites, setInvites] = useState<Record<string, CreatedInvite>>({})
  const [people, setPeople] = useState<string | null>(null)
  const [invitesMade, setInvitesMade] = useState(0)
  const [actionError, setActionError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  async function createOrg(e: FormEvent) {
    e.preventDefault()
    setActionError(null)
    setCreating(true)
    try {
      await api('POST', '/api/admin/orgs', { name, type })
      setName('')
      reload()
    } catch (err) {
      setActionError(message(err))
    } finally {
      setCreating(false)
    }
  }

  async function createInvite(orgId: string) {
    setActionError(null)
    try {
      const invite = await api<CreatedInvite>('POST', apiPath`/api/admin/orgs/${orgId}/invites`, {})
      setInvites((current) => ({ ...current, [orgId]: invite }))
      setInvitesMade((made) => made + 1)
    } catch (err) {
      setActionError(message(err))
    }
  }

  function copy(link: string) {
    navigator.clipboard.writeText(link).catch(() => setActionError('Could not copy; select the link and copy it by hand.'))
  }

  if (error) return <PageError message={error} />

  return (
    <>
      <p>
        <Link to="/admin/review">Review queue →</Link> · <Link to="/admin/outcomes">Graded results to check →</Link> ·{' '}
        <Link to="/admin/deals">Offers and deals{attention && attention.length > 0 && ` (${attention.length} need you)`} →</Link> · <Link to="/admin/offers">Offer track record →</Link> ·{' '}
        <Link to="/admin/deal-outcomes">Deal outcomes →</Link> ·{' '}
        <Link to="/admin/labels">Labels to buy →</Link> ·{' '}
        <Link to="/admin/shipping">Postage to bill →</Link> · <Link to="/admin/centering">Centering labels →</Link> ·{' '}
        <Link to="/admin/poketrace">PokeTrace mapping →</Link>
      </p>
      <NeedsAttention deals={attention ?? null} dealsError={attentionError ?? null} />
      <h1>Orgs</h1>
      <form onSubmit={createOrg} className="row">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Org name" aria-label="Org name" required maxLength={200} />
        <select value={type} onChange={(e) => setType(e.target.value as OrgType)} aria-label="Org type">
          <option value="COLLECTOR">Collector (sells cards)</option>
          <option value="PARTNER">Partner</option>
          <option value="CONTRIBUTOR">Contributor (shares graded cards)</option>
          <option value="BUYER">Buyer (takes up instant offers)</option>
        </select>
        <button type="submit" disabled={creating}>
          Create
        </button>
      </form>
      {actionError && <p className="error" role="alert">{actionError}</p>}

      {!orgs && <Loading />}
      <ul className="list">
        {orgs?.map((org) => {
          const invite = invites[org.id]
          const link = invite ? `${window.location.origin}${invite.acceptPath}` : null
          return (
            <li key={org.id}>
              <Link to={`/orgs/${org.id}`}>{org.name}</Link>{' '}
              <span className="muted">
                {ORG_TYPE_LABEL[org.type]} · {org.members} member{org.members === 1 ? '' : 's'}
              </span>{' '}
              <button className="link" onClick={() => createInvite(org.id)}>
                New invite link
              </button>{' '}
              <button className="link" aria-expanded={people === org.id} onClick={() => setPeople(people === org.id ? null : org.id)}>
                People and links
              </button>
              {link && (
                <div className="invite">
                  <input readOnly value={link} onFocus={(e) => e.target.select()} aria-label="Invite link" />
                  <button onClick={() => copy(link)}>Copy</button>
                  <span className="muted">
                    Single use, expires {new Date(invite.expiresAt).toLocaleDateString()}. It is shown only once.
                  </span>
                </div>
              )}
              {people === org.id && <OrgPeople orgId={org.id} invitesMade={invitesMade} />}
            </li>
          )
        })}
      </ul>
      <PhotoLocation />
    </>
  )
}

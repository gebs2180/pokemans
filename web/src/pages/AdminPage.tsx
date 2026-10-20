import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { apiPath, ORG_TYPE_LABEL, useApi, type CreatedInvite, type Org, type OrgType } from '../api'
import { useLoad } from '../useLoad'

export function AdminPage() {
  const api = useApi()
  const { data: orgs, error, reload } = useLoad(() => api<Org[]>('GET', '/api/admin/orgs'), [api])
  const [name, setName] = useState('')
  const [type, setType] = useState<OrgType>('PARTNER')
  const [invites, setInvites] = useState<Record<string, CreatedInvite>>({})
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
      setActionError(err instanceof Error ? err.message : String(err))
    } finally {
      setCreating(false)
    }
  }

  async function createInvite(orgId: string) {
    setActionError(null)
    try {
      const invite = await api<CreatedInvite>('POST', apiPath`/api/admin/orgs/${orgId}/invites`, {})
      setInvites((current) => ({ ...current, [orgId]: invite }))
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err))
    }
  }

  function copy(link: string) {
    navigator.clipboard.writeText(link).catch(() => setActionError('Could not copy; select the link and copy it by hand.'))
  }

  if (error) return <p className="error" role="alert">{error}</p>

  return (
    <>
      <p>
        <Link to="/admin/review">Review queue →</Link> · <Link to="/admin/outcomes">Graded results to check →</Link> ·{' '}
        <Link to="/admin/offers">Offer track record →</Link>
      </p>
      <h1>Orgs</h1>
      <form onSubmit={createOrg} className="row">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Org name" aria-label="Org name" required maxLength={200} />
        <select value={type} onChange={(e) => setType(e.target.value as OrgType)} aria-label="Org type">
          <option value="PARTNER">Partner</option>
          <option value="COLLECTOR">Collector</option>
          <option value="CONTRIBUTOR">Contributor (shares graded cards)</option>
          <option value="BUYER">Buyer (takes up instant offers)</option>
        </select>
        <button type="submit" disabled={creating}>
          Create
        </button>
      </form>
      {actionError && <p className="error" role="alert">{actionError}</p>}

      {!orgs && <p>Loading…</p>}
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
            </li>
          )
        })}
      </ul>
    </>
  )
}

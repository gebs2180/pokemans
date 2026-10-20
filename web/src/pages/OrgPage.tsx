import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { apiPath, useApi, type ConsentStatus, type ContainerSummary } from '../api'
import { useLoad } from '../useLoad'
import { containerKind, useOrg } from '../useOrg'
import { AddressPanel } from './AddressForm'

export function OrgPage() {
  const { orgId = '' } = useParams()
  const { data: org, error: orgError } = useOrg(orgId)
  if (orgError) return <p className="error" role="alert">{orgError}</p>
  if (!org) return <p>Loading…</p>
  if (org.type === 'BUYER') {
    return (
      <ConsentGate orgId={orgId} title="Before you buy" intro="Please read what accepting an offer commits you to." accepted="buyer terms">
        <h1>{org.name}</h1>
        <ul className="list">
          <li>
            <Link to={`/orgs/${orgId}/market`}>Cards for sale</Link> <span className="muted">raw cards at one firm price each</span>
          </li>
          <li>
            <Link to={`/orgs/${orgId}/deals`}>Your deals</Link> <span className="muted">cards you bought, labels, and arrivals to record</span>
          </li>
        </ul>
        <AddressPanel orgId={orgId} title="Where cards ship to" />
      </ConsentGate>
    )
  }
  const containers = <Containers orgId={orgId} orgName={org.name} kind={containerKind(org.type)} />
  return org.type === 'CONTRIBUTOR' ? <ConsentGate orgId={orgId}>{containers}</ConsentGate> : containers
}

/** Contributors and buyers read and accept their terms before taking part. */
function ConsentGate({
  orgId,
  children,
  title = 'Before you share',
  intro = 'Please read how we use the photos and grades you share.',
  accepted = 'data-use terms',
}: {
  orgId: string
  children: ReactNode
  title?: string
  intro?: string
  accepted?: string
}) {
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<ConsentStatus>('GET', apiPath`/api/orgs/${orgId}/consent`), [api, orgId])
  const [acceptError, setAcceptError] = useState<string | null>(null)

  async function accept() {
    setAcceptError(null)
    try {
      await api('POST', apiPath`/api/orgs/${orgId}/consent`, { version: data?.version })
      reload()
    } catch (err) {
      setAcceptError(err instanceof Error ? err.message : String(err))
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  if (!data) return <p>Loading…</p>
  if (data.acceptedAt) {
    return (
      <>
        {children}
        <p className="muted small">
          You accepted the {accepted} on {new Date(data.acceptedAt).toLocaleDateString()}.
        </p>
      </>
    )
  }
  return (
    <>
      <h1>{title}</h1>
      <p>{intro}</p>
      <pre className="terms">{data.text}</pre>
      <button onClick={accept}>I agree</button>
      {acceptError && <p className="error" role="alert">{acceptError}</p>}
    </>
  )
}

function Containers({ orgId, orgName, kind }: { orgId: string; orgName: string; kind: ReturnType<typeof containerKind> }) {
  const api = useApi()
  const { data, error, reload } = useLoad(
    () => api<ContainerSummary[]>('GET', apiPath`/api/orgs/${orgId}/${kind.path}`),
    [api, orgId, kind.path],
  )
  const [name, setName] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  async function create(e: FormEvent) {
    e.preventDefault()
    setCreateError(null)
    setCreating(true)
    try {
      await api('POST', apiPath`/api/orgs/${orgId}/${kind.path}`, { name })
      setName('')
      reload()
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : String(err))
    } finally {
      setCreating(false)
    }
  }

  return (
    <>
      <h1>{orgName}</h1>
      <h2>{kind.plural}</h2>
      <form onSubmit={create} className="row">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder={`New ${kind.singular} name`} aria-label={`New ${kind.singular} name`} required maxLength={200} />
        <button type="submit" disabled={creating}>
          Create
        </button>
      </form>
      {createError && <p className="error" role="alert">{createError}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {data && data.length === 0 && <p className="muted">No {kind.plural.toLowerCase()} yet.</p>}
      <ul className="list">
        {data?.map((c) => (
          <li key={c.id}>
            <Link to={`/orgs/${orgId}/${kind.path}/${c.id}`}>{c.name}</Link>{' '}
            <span className="muted">
              {c.cards} card{c.cards === 1 ? '' : 's'} · {new Date(c.createdAt).toLocaleDateString()}
            </span>
          </li>
        ))}
      </ul>
    </>
  )
}

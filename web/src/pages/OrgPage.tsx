import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { apiPath, useApi, type ConsentStatus, type ContainerSummary, type Me } from '../api'
import { EmptyState, Loading, PageError } from '../ui'
import { useFormError } from '../useFormError'
import { useLoad } from '../useLoad'
import { containerKind, soleOrg, useOrg } from '../useOrg'
import { focusHeading } from '../useRouteFocus'
import { AddressPanel } from './AddressForm'
import { ContainerView } from './ContainerPage'
import { HowItWorksGuide, type GuideFor } from './HowItWorksPage'

export function OrgPage() {
  const { orgId = '' } = useParams()
  const api = useApi()
  const { data: me, error } = useLoad(() => api<Me>('GET', '/api/me'), [api])
  if (error) return <PageError message={error} />
  if (!me) return <Loading />
  // Someone with one org has it as their home page.
  if (soleOrg(me)?.orgId === orgId) return <Navigate to="/" replace />
  return <OrgHome orgId={orgId} />
}

/**
 * What a member does in their org, behind its terms.
 * @param title the heading, in place of the org's name
 * @param lead  shown under the heading, once the terms are accepted
 */
export function OrgHome({ orgId, title, lead }: { orgId: string; title?: string; lead?: ReactNode }) {
  const { data: org, error: orgError } = useOrg(orgId)
  if (orgError) return <PageError message={orgError} />
  if (!org) return <Loading />
  if (org.type === 'BUYER') {
    return (
      <ConsentGate orgId={orgId} title="Before you buy" intro="Please read what accepting an offer commits you to." accepted="buyer terms" guide="buyer">
        <h1>{org.name}</h1>
        <ul className="tile-list">
          <li className="tile">
            <Link to={`/orgs/${orgId}/market`}>Cards for sale</Link> <span className="muted">raw cards at one firm price each</span>
          </li>
          <li className="tile">
            <Link to={`/orgs/${orgId}/deals`}>Your deals</Link> <span className="muted">cards you bought, labels, and arrivals to record</span>
          </li>
        </ul>
        <AddressPanel orgId={orgId} title="Where cards ship to" />
      </ConsentGate>
    )
  }
  const containers = (
    <Containers orgId={orgId} title={title ?? org.name} lead={lead} kind={containerKind(org.type)} contributor={org.type === 'CONTRIBUTOR'} />
  )
  if (org.type === 'COLLECTOR') {
    return (
      <ConsentGate orgId={orgId} title="Before you sell" intro="Please read what posting a card for sale commits you to." accepted="seller terms" guide="seller">
        {containers}
      </ConsentGate>
    )
  }
  return org.type === 'CONTRIBUTOR' ? (
    <ConsentGate orgId={orgId} guide="contributor">
      {containers}
    </ConsentGate>
  ) : (
    containers
  )
}

/** Collectors, contributors and buyers see how it works, then read and accept their terms before taking part. */
function ConsentGate({
  orgId,
  children,
  guide,
  title = 'Before you share',
  intro = 'Please read how we use the photos and grades you share.',
  accepted = 'data-use terms',
}: {
  orgId: string
  children: ReactNode
  guide: GuideFor
  title?: string
  intro?: string
  accepted?: string
}) {
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<ConsentStatus>('GET', apiPath`/api/orgs/${orgId}/consent`), [api, orgId])
  const [acceptError, setAcceptError] = useState<string | null>(null)
  const [readGuide, setReadGuide] = useState(false)

  // Same address, new page: do what a route change would.
  useEffect(() => {
    if (!readGuide) return
    window.scrollTo(0, 0)
    focusHeading()
  }, [readGuide])

  async function accept() {
    setAcceptError(null)
    try {
      await api('POST', apiPath`/api/orgs/${orgId}/consent`, { version: data?.version })
      window.scrollTo(0, 0)
      reload()
    } catch (err) {
      setAcceptError(err instanceof Error ? err.message : String(err))
    }
  }

  if (error) return <PageError message={error} />
  if (!data) return <Loading />
  if (data.acceptedAt) {
    return (
      <>
        {children}
        <p className="muted small">
          You accepted the {accepted} on {new Date(data.acceptedAt).toLocaleDateString()}. <Link to={`/orgs/${orgId}/terms`}>Read them again</Link>
        </p>
      </>
    )
  }
  if (!readGuide) {
    return (
      <div className="guide">
        <h1>How it works</h1>
        <HowItWorksGuide only={guide} />
        <div className="gate-bar">
          <button onClick={() => setReadGuide(true)}>Continue to the terms</button>
        </div>
      </div>
    )
  }
  return (
    <>
      <h1>{title}</h1>
      <p>{intro}</p>
      {/* Scrolls in its own box, so it takes keyboard focus to scroll. */}
      <pre className="terms" tabIndex={0} role="region" aria-label="Terms">
        {data.text}
      </pre>
      {acceptError && <p className="error" role="alert">{acceptError}</p>}
      <div className="gate-bar">
        <button onClick={accept}>I agree</button>
      </div>
    </>
  )
}

function Containers({
  orgId,
  title,
  lead,
  kind,
  contributor,
}: {
  orgId: string
  title: string
  lead?: ReactNode
  kind: ReturnType<typeof containerKind>
  contributor: boolean
}) {
  const api = useApi()
  const navigate = useNavigate()
  const { data, error } = useLoad(
    () => api<ContainerSummary[]>('GET', apiPath`/api/orgs/${orgId}/${kind.path}`),
    [api, orgId, kind.path],
  )
  const [name, setName] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const formError = useFormError(createError)

  async function create(e: FormEvent) {
    e.preventDefault()
    setCreateError(null)
    setCreating(true)
    try {
      const created = await api<ContainerSummary>('POST', apiPath`/api/orgs/${orgId}/${kind.path}`, { name })
      navigate(`/orgs/${encodeURIComponent(orgId)}/${kind.path}/${encodeURIComponent(created.id)}`)
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : String(err))
    } finally {
      setCreating(false)
    }
  }

  const createForm = (
    <>
      <form onSubmit={create} className="row" aria-describedby={formError.describedBy}>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder={`New ${kind.singular} name`}
          aria-label={`New ${kind.singular} name`}
          aria-invalid={createError ? true : undefined}
          aria-describedby={formError.describedBy}
          required
          maxLength={200}
        />
        <button type="submit" disabled={creating} aria-busy={creating}>
          Create
        </button>
      </form>
      {formError.message}
    </>
  )

  // Nothing yet, not even the heading: it may be the binder's, and focus goes to the first one that shows.
  if (!data && !error) return <Loading />

  // One binder is the usual case, so "Add card" is right here. It needs no name or tap of its own.
  if (data?.length === 1 && !contributor && kind.path === 'collections') {
    return (
      <ContainerView orgId={orgId} kind={kind.path} containerId={data[0].id} title={title} lead={lead}>
        <details className="disclosure">
          <summary>Add another {kind.singular}</summary>
          {createForm}
        </details>
      </ContainerView>
    )
  }

  return (
    <>
      <h1>{title}</h1>
      {lead}
      <h2>{kind.plural}</h2>
      {createForm}
      {error && <p className="error" role="alert">{error}</p>}
      {data && data.length === 0 && <EmptyState title={`No ${kind.plural.toLowerCase()} yet.`} />}
      {contributor && (
        <section className="panel">
          <h2>How contributing works</h2>
          <ol className="small">
            <li>Create a binder, e.g. "Graded cards".</li>
            <li>Add each card you've had graded, with its name and number.</li>
            <li>Add photos of its front and back from before it was graded: the raw card, out of the slab. Photos of the slab can't be used.</li>
            <li>Enter the grader, cert number and grade. We check the cert.</li>
          </ol>
          <p className="muted small">Sending a card to be graded? Photograph it now, then add the grade when it comes back. You don't have to sell anything.</p>
        </section>
      )}
      <ul className="tile-list">
        {data?.map((c) => (
          <li key={c.id} className="tile">
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

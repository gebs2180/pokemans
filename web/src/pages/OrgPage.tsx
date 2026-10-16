import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { useApi, type ContainerSummary } from '../api'
import { useLoad } from '../useLoad'
import { containerKind, useOrg } from '../useOrg'

export function OrgPage() {
  const { orgId = '' } = useParams()
  const { data: org, error: orgError } = useOrg(orgId)
  if (orgError) return <p className="error">{orgError}</p>
  if (!org) return <p>Loading…</p>
  return <Containers orgId={orgId} orgName={org.name} kind={containerKind(org.type)} />
}

function Containers({ orgId, orgName, kind }: { orgId: string; orgName: string; kind: ReturnType<typeof containerKind> }) {
  const api = useApi()
  const { data, error, reload } = useLoad(
    () => api<ContainerSummary[]>('GET', `/api/orgs/${orgId}/${kind.path}`),
    [api, orgId, kind.path],
  )
  const [name, setName] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)

  async function create(e: FormEvent) {
    e.preventDefault()
    try {
      await api('POST', `/api/orgs/${orgId}/${kind.path}`, { name })
      setName('')
      reload()
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <>
      <h1>{orgName}</h1>
      <h2>{kind.plural}</h2>
      <form onSubmit={create} className="row">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder={`New ${kind.singular} name`} required maxLength={200} />
        <button type="submit">Create</button>
      </form>
      {createError && <p className="error">{createError}</p>}
      {error && <p className="error">{error}</p>}
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

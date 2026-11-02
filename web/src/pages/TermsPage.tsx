import { Link, useParams } from 'react-router'
import { apiPath, useApi, type ConsentStatus } from '../api'
import { Loading, PageError } from '../ui'
import { useLoad } from '../useLoad'

/** The terms an org's members agreed to, to read again at any time. */
export function TermsPage() {
  const { orgId = '' } = useParams()
  const api = useApi()
  const { data, error } = useLoad(() => api<ConsentStatus>('GET', apiPath`/api/orgs/${orgId}/consent`), [api, orgId])

  if (error) return <PageError message={error} />
  if (!data) return <Loading />

  return (
    <>
      <p className="back">
        <Link to="/how-it-works">← How it works</Link>
      </p>
      <h1>Your terms</h1>
      <p className="muted">
        {data.acceptedAt ? `You accepted these on ${new Date(data.acceptedAt).toLocaleDateString()}.` : "You haven't accepted these yet."}
      </p>
      <pre className="terms terms-full">{data.text}</pre>
    </>
  )
}

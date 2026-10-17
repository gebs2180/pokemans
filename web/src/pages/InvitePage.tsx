import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useApi, type InvitePreview } from '../api'
import { useLoad } from '../useLoad'

const ROLE_LABEL: Record<string, string> = { PARTNER: 'submission partner', COLLECTOR: 'collector', CONTRIBUTOR: 'contributor' }

export function InvitePage() {
  const { token = '' } = useParams()
  const api = useApi()
  const navigate = useNavigate()
  const { data: invite, error } = useLoad(() => api<InvitePreview>('GET', `/api/invites/${token}`), [api, token])
  const [acceptError, setAcceptError] = useState<string | null>(null)

  async function accept() {
    try {
      const accepted = await api<{ orgId: string }>('POST', `/api/invites/${token}/accept`)
      navigate(`/orgs/${accepted.orgId}`, { replace: true })
    } catch (err) {
      setAcceptError(err instanceof Error ? err.message : String(err))
    }
  }

  if (error) return <p className="error">This invite link is not valid. {error}</p>
  if (!invite) return <p>Loading…</p>

  return (
    <>
      <h1>Join {invite.orgName}</h1>
      <p>
        You have been invited as a {ROLE_LABEL[invite.role] ?? invite.role.toLowerCase()}.
      </p>
      {invite.usable ? (
        <button onClick={accept}>Accept invite</button>
      ) : (
        <p className="error">This invite has expired or was already used. Ask for a new link.</p>
      )}
      {acceptError && <p className="error">{acceptError}</p>}
    </>
  )
}

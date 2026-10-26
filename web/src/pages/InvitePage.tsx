import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { apiPath, useApi, type InvitePreview } from '../api'
import { Loading } from '../ui'
import { useLoad } from '../useLoad'

const ROLE_LABEL: Record<string, string> = { PARTNER: 'submission partner', COLLECTOR: 'collector', CONTRIBUTOR: 'contributor' }

export function InvitePage() {
  const { token = '' } = useParams()
  const api = useApi()
  const navigate = useNavigate()
  const { data: invite, error } = useLoad(() => api<InvitePreview>('GET', apiPath`/api/invites/${token}`), [api, token])
  const [acceptError, setAcceptError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function accept() {
    setAcceptError(null)
    setBusy(true)
    try {
      const accepted = await api<{ orgId: string }>('POST', apiPath`/api/invites/${token}/accept`)
      navigate(`/orgs/${encodeURIComponent(accepted.orgId)}`, { replace: true })
    } catch (err) {
      setAcceptError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  if (error) return <p className="error" role="alert">This invite link is not valid. {error}</p>
  if (!invite) return <Loading />

  return (
    <>
      <h1>Join {invite.orgName}</h1>
      <p>
        You have been invited as a {ROLE_LABEL[invite.role] ?? invite.role.toLowerCase()}.
      </p>
      {invite.usable ? (
        <div className="actions">
          <button onClick={accept} disabled={busy} aria-busy={busy}>
            Accept invite
          </button>
        </div>
      ) : (
        <p className="error" role="alert">This invite has expired or was already used. Ask for a new link.</p>
      )}
      {acceptError && <p className="error" role="alert">{acceptError}</p>}
    </>
  )
}

import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { apiPath, useApi, type InvitePreview, type OrgType } from '../api'
import { Loading, PageError } from '../ui'
import { useLoad } from '../useLoad'

/** What a person is invited to do: the org's own name is an internal one, chosen by the admin. */
const INVITED_TO: Partial<Record<OrgType, string>> = {
  COLLECTOR: 'sell your cards on Rawmark',
  BUYER: 'buy cards on Rawmark',
  CONTRIBUTOR: 'share graded cards with Rawmark',
}

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

  if (error) return <PageError message={`This invite link is not valid. ${error}`} />
  if (!invite) return <Loading />

  return (
    <>
      {INVITED_TO[invite.orgType] ? (
        <>
          <h1>You're invited to {INVITED_TO[invite.orgType]}</h1>
          <p>Next you'll see how it works and read the terms. Nothing's agreed until you accept them.</p>
        </>
      ) : (
        <>
          <h1>Join {invite.orgName}</h1>
          <p>You have been invited as a submission partner.</p>
        </>
      )}
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

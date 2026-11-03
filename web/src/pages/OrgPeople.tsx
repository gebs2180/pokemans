import { useState, type FormEvent } from 'react'
import { apiPath, useApi, type OpenInvite, type OrgMember } from '../api'
import { useLoad } from '../useLoad'
import { message } from '../format'
import { Loading } from '../ui'

/** Who's in an org and which invite links still work, with the admin's way to end either. */
export function OrgPeople({ orgId, invitesMade }: { orgId: string; invitesMade: number }) {
  const api = useApi()
  const { data: members, error: membersError, reload: reloadMembers } = useLoad(() => api<OrgMember[]>('GET', apiPath`/api/admin/orgs/${orgId}/members`), [api, orgId])
  const { data: invites, error: invitesError, reload: reloadInvites } = useLoad(
    () => api<OpenInvite[]>('GET', apiPath`/api/admin/orgs/${orgId}/invites`),
    [api, orgId, invitesMade],
  )
  const [removing, setRemoving] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function act(action: () => Promise<unknown>, reload: () => void) {
    setActionError(null)
    setBusy(true)
    try {
      await action()
      reload()
      setRemoving(null)
      setReason('')
    } catch (err) {
      setActionError(message(err))
    } finally {
      setBusy(false)
    }
  }

  function remove(e: FormEvent, membershipId: string) {
    e.preventDefault()
    void act(() => api('POST', apiPath`/api/admin/orgs/${orgId}/members/${membershipId}/end`, { reason }), reloadMembers)
  }

  const error = membersError ?? invitesError ?? actionError
  return (
    <div className="invite">
      {error && <p className="error" role="alert">{error}</p>}
      {!members && !membersError && <Loading />}
      {members?.length === 0 && <p className="muted small">No members.</p>}
      <ul className="list">
        {members?.map((member) => (
          <li key={member.id}>
            {member.email ?? 'No email on file'}{' '}
            <span className="muted">joined {new Date(member.joinedAt).toLocaleDateString()}</span>{' '}
            {removing === member.id ? (
              <form onSubmit={(e) => remove(e, member.id)} className="row">
                <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Why" aria-label="Why they're being removed" required maxLength={2000} />
                <button type="submit" disabled={busy || !reason.trim()}>
                  Remove from this org
                </button>
                <button type="button" className="link" onClick={() => setRemoving(null)}>
                  Keep
                </button>
              </form>
            ) : (
              <button className="link" onClick={() => { setReason(''); setRemoving(member.id) }}>
                Remove
              </button>
            )}
          </li>
        ))}
      </ul>
      {invites && invites.length > 0 && (
        <ul className="list">
          {invites.map((invite) => (
            <li key={invite.id}>
              Open invite link from {new Date(invite.createdAt).toLocaleDateString()}{' '}
              <span className="muted">expires {new Date(invite.expiresAt).toLocaleDateString()}</span>{' '}
              <button className="link" disabled={busy} onClick={() => void act(() => api('POST', apiPath`/api/admin/orgs/${orgId}/invites/${invite.id}/revoke`), reloadInvites)}>
                Revoke
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

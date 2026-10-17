import { Link } from 'react-router'
import { ORG_TYPE_LABEL, useApi, type Me } from '../api'
import { useLoad } from '../useLoad'

export function HomePage() {
  const api = useApi()
  const { data: me, error } = useLoad(() => api<Me>('GET', '/api/me'), [api])

  if (error) return <p className="error">{error}</p>
  if (!me) return <p>Loading…</p>

  const admin = me.roles.includes('ADMIN')
  return (
    <>
      <h1>Your orgs</h1>
      {me.memberships.length === 0 && !admin && (
        <p>You are not part of any org yet. Ask for an invite link and open it while signed in.</p>
      )}
      <ul className="list">
        {me.memberships.map((m) => (
          <li key={m.orgId}>
            <Link to={`/orgs/${m.orgId}`}>{m.orgName}</Link>{' '}
            <span className="muted">{ORG_TYPE_LABEL[m.orgType]}</span>
          </li>
        ))}
      </ul>
      {admin && (
        <p>
          <Link to="/admin">Admin: orgs and invites</Link>
        </p>
      )}
      <details className="muted">
        <summary>Account details</summary>
        <p>
          Signed in as <code>{me.subject}</code>. Roles: {me.roles.length ? me.roles.join(', ') : 'none'}. Token roles
          claim: {me.claimedRoles.length ? me.claimedRoles.join(', ') : 'missing or empty'}. MFA: {me.mfa ? 'yes' : 'no'}.
        </p>
      </details>
    </>
  )
}

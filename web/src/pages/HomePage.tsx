import { Link } from 'react-router'
import { ORG_TYPE_LABEL, useApi, type Me } from '../api'
import { EmptyState, Loading, PageError } from '../ui'
import { useLoad } from '../useLoad'
import { soleOrg } from '../useOrg'
import { NeedsYou } from './NeedsYou'
import { OrgHome } from './OrgPage'

export function HomePage() {
  const api = useApi()
  const { data: me, error } = useLoad(() => api<Me>('GET', '/api/me'), [api])

  if (error) return <PageError message={error} />
  if (!me) return <Loading />

  const needs = <NeedsYou orgs={me.memberships.filter((m) => m.orgType === 'COLLECTOR' || m.orgType === 'CONTRIBUTOR')} />
  // Most people have one org and no reason to see the word: home is their org.
  const sole = soleOrg(me)
  if (sole) return <OrgHome orgId={sole.orgId} title="Your cards" lead={needs} />

  const admin = me.roles.includes('ADMIN')
  return (
    <>
      <h1>Your cards</h1>
      {needs}
      {me.memberships.length === 0 && !admin && (
        <EmptyState title="You're not set up yet.">Ask for an invite link and open it while signed in.</EmptyState>
      )}
      <ul className="tile-list">
        {me.memberships.map((m) => (
          <li key={m.orgId} className="tile">
            <Link to={`/orgs/${m.orgId}`}>{m.orgName}</Link>{' '}
            <span className="muted">{ORG_TYPE_LABEL[m.orgType]}</span>
          </li>
        ))}
      </ul>
      {admin && (
        <>
          <ul className="tile-list">
            <li className="tile">
              <Link to="/admin">Admin: orgs and invites</Link>
            </li>
          </ul>
          <details className="muted">
            <summary>Account details</summary>
            <p>
              Signed in as <code>{me.subject}</code>. Roles: {me.roles.join(', ')}. Token roles claim:{' '}
              {me.claimedRoles.length ? me.claimedRoles.join(', ') : 'missing or empty'}. MFA: {me.mfa ? 'yes' : 'no'}.
            </p>
          </details>
        </>
      )}
    </>
  )
}

import { useApi, type Me, type Org, type OrgType } from './api'
import { useLoad } from './useLoad'

export type OrgInfo = { id: string; name: string; type: OrgType }

/** The org's name and type: from the caller's memberships, or the admin org list for admins. */
export function useOrg(orgId: string) {
  const api = useApi()
  return useLoad(async (): Promise<OrgInfo> => {
    const me = await api<Me>('GET', '/api/me')
    const membership = me.memberships.find((m) => m.orgId === orgId)
    if (membership) {
      return { id: orgId, name: membership.orgName, type: membership.orgType }
    }
    if (me.roles.includes('ADMIN')) {
      const org = (await api<Org[]>('GET', '/api/admin/orgs')).find((o) => o.id === orgId)
      if (org) {
        return { id: org.id, name: org.name, type: org.type }
      }
    }
    throw new Error('Org not found')
  }, [api, orgId])
}

/**
 * The org whose page is this member's home page: they have just the one, and aren't the admin or a partner. A seller who
 * also buys has one seller org plus a buyer org, and home is still the seller org (the buyer org is a "Buying" section).
 */
export function soleOrg(me: Me) {
  if (me.roles.includes('ADMIN')) return null
  const only = me.memberships.length === 1 ? me.memberships[0] : sellerOrgs(me).length === 1 ? sellerOrgs(me)[0] : null
  return only && only.orgType !== 'PARTNER' ? only : null
}

/** The memberships that aren't buyer orgs. */
function sellerOrgs(me: Me) {
  return me.memberships.filter((m) => m.orgType !== 'BUYER')
}

/** URL segment and labels for an org's containers: batches for partners, binders (collections in the API) for everyone else. */
export function containerKind(type: OrgType) {
  return type === 'PARTNER'
    ? { path: 'batches', singular: 'batch', plural: 'Batches' }
    : { path: 'collections', singular: 'binder', plural: 'Binders' }
}

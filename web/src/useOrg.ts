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

/** URL segment and labels for an org's containers: batches for partners, collections for collectors. */
export function containerKind(type: OrgType) {
  return type === 'PARTNER'
    ? { path: 'batches', singular: 'batch', plural: 'Batches' }
    : { path: 'collections', singular: 'collection', plural: 'Collections' }
}

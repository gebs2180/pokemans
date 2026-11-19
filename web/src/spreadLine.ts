/**
 * Which spread each provider's grade got, from a system assessment's basis: tight when its lowest of
 * corners, edges and surface is at or above its grade; high when a 9's corners, edges and surface are each 9.5 or
 * better with at least two 10s (Zach, 2026-11-19). Older assessments don't record it.
 */
export function spreadLine(basis: Record<string, unknown>): string | null {
  const used = basis.spreadUsed as Record<string, string> | undefined
  if (!used || Object.keys(used).length === 0) return null
  const folded = (basis.foldedGrades ?? {}) as Record<string, number>
  const minSub = (basis.minSubgrades ?? {}) as Record<string, number | null>
  const given = (basis.subgrades ?? {}) as Record<string, { corners: number; edges: number; surface: number }>
  const parts = Object.entries(used).map(([provider, spread]) => {
    const name = provider.charAt(0) + provider.slice(1).toLowerCase()
    const low = minSub[provider]
    const subs = given[provider]
    const why =
      spread === 'high' && subs
        ? `corners ${subs.corners}, edges ${subs.edges}, surface ${subs.surface}: none below 9.5 and two or more 10s`
        : low == null
        ? 'no corners, edges and surface given'
        : `lowest of corners, edges and surface ${low} ${spread === 'tight' ? '≥' : '<'} grade ${folded[provider]}`
    return `${name}: ${spread} spread (${why})`
  })
  return parts.join('; ') + '.'
}

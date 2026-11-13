import { useApi, type GraderStandard } from './api'
import { useLoad } from './useLoad'

/** The graders' centering tables (display only), fetched once per page. */
export function useGraderStandards() {
  const api = useApi()
  return useLoad(() => api<GraderStandard[]>('GET', '/api/admin/centering-standards'), [api])
}

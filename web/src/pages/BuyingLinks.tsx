import { Link } from 'react-router'
import { useApi, type BuyingState, type Me } from '../api'
import { useLoad } from '../useLoad'

/** Where the person stands on buying, or null while it loads or if that fails: these links are a convenience, never a gate. */
function useBuyingState() {
  const api = useApi()
  return useLoad(() => api<BuyingState>('GET', '/api/me/buying'), [api]).data
}

/** A way into Start buying, shown only while the person hasn't started (UX review finding 7). */
export function StartBuyingLink() {
  const state = useBuyingState()
  if (state?.status !== 'NOT_STARTED') return null
  return (
    <p>
      Already selling here? <Link to="/account">Start buying</Link> from the same login.
    </p>
  )
}

/**
 * Home's "Buying" side (UX review findings 7 and 12). For someone with one seller org it also stands in for the buyer
 * org, which Home no longer lists: where they stand, and the market and deals once approved. For someone in several
 * seller orgs the buyer org is already in the list, so only the way to start shows.
 */
export function HomeBuying({ me, soleSeller }: { me: Me; soleSeller: boolean }) {
  const state = useBuyingState()
  if (!state || me.memberships.length === 0) return null
  if (state.status === 'NOT_STARTED') return <StartBuyingLink />
  if (!soleSeller || !state.orgId) return null
  const org = `/orgs/${state.orgId}`
  return (
    <section className="panel" aria-labelledby="home-buying">
      <h2 id="home-buying">Buying</h2>
      {state.status === 'APPROVED' ? (
        <ul className="tile-list">
          <li className="tile">
            <Link to={`${org}/market`}>Cards for sale</Link> <span className="muted">raw cards at one firm price each</span>
          </li>
          <li className="tile">
            <Link to={`${org}/deals`}>Your deals</Link> <span className="muted">cards you bought</span>
          </li>
        </ul>
      ) : (
        <p role="status">
          {state.status === 'WAITING' ? "Waiting for approval. We'll email you when it's answered." : "We couldn't approve buying."}{' '}
          <Link to="/account">See where it stands</Link>
        </p>
      )}
    </section>
  )
}

import { useAuth0 } from '@auth0/auth0-react'
import { lazy, Suspense } from 'react'
import { Link, Route, Routes, useLocation } from 'react-router'
import { CardPage } from './pages/CardPage'
import { ContainerPage } from './pages/ContainerPage'
import { HomePage } from './pages/HomePage'
import { InvitePage } from './pages/InvitePage'
import { DealsPage, MarketPage } from './pages/MarketPage'
import { OrgPage } from './pages/OrgPage'

// Admin and review screens are only for us, so partners' phones don't download them.
const AdminPage = lazy(() => import('./pages/AdminPage').then((m) => ({ default: m.AdminPage })))
const AdminOutcomesPage = lazy(() => import('./pages/AdminOutcomesPage').then((m) => ({ default: m.AdminOutcomesPage })))
const OfferTrackRecordPage = lazy(() => import('./pages/OfferTrackRecordPage').then((m) => ({ default: m.OfferTrackRecordPage })))
const ReviewQueuePage = lazy(() => import('./pages/ReviewQueuePage').then((m) => ({ default: m.ReviewQueuePage })))
const ReviewCardPage = lazy(() => import('./pages/ReviewCardPage').then((m) => ({ default: m.ReviewCardPage })))
const ValuationPage = lazy(() => import('./pages/ValuationPage').then((m) => ({ default: m.ValuationPage })))
const ValuationRunPage = lazy(() => import('./pages/ValuationRunPage').then((m) => ({ default: m.ValuationRunPage })))

/** Lender and insurer screens are paused unless the build turns them on. */
export const COLLATERAL = import.meta.env.VITE_COLLATERAL_ENABLED === 'true'

function App() {
  const { isLoading, isAuthenticated, user, error, loginWithRedirect, logout } = useAuth0()
  const location = useLocation()

  return (
    <div className={/^\/admin\/(review|valuation)/.test(location.pathname) ? 'shell wide' : 'shell'}>
      <header className="topbar">
        <Link to="/" className="brand">
          pokemans
        </Link>
        {isAuthenticated && (
          <span className="who">
            {user?.email ?? user?.name}{' '}
            <button className="link" onClick={() => logout({ logoutParams: { returnTo: window.location.origin } })}>
              Log out
            </button>
          </span>
        )}
      </header>

      {error && <p className="error" role="alert">Login error: {error.message}</p>}
      {isLoading && <p>Loading…</p>}

      {!isLoading && !isAuthenticated && (
        <main>
          <p>Raw collection collateral reports.</p>
          <button onClick={() => loginWithRedirect({ appState: { returnTo: location.pathname + location.search } })}>Log in</button>
        </main>
      )}

      {isAuthenticated && (
        <main>
          <Suspense fallback={<p>Loading…</p>}>
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/admin" element={<AdminPage />} />
              <Route path="/admin/review" element={<ReviewQueuePage />} />
              <Route path="/admin/outcomes" element={<AdminOutcomesPage />} />
              <Route path="/admin/offers" element={<OfferTrackRecordPage />} />
              <Route path="/admin/review/:orgId/:cardId" element={<ReviewCardPage />} />
              {COLLATERAL && <Route path="/admin/valuation/:orgId/:collectionId" element={<ValuationPage />} />}
              {COLLATERAL && <Route path="/admin/valuation-runs/:runId" element={<ValuationRunPage />} />}
              <Route path="/invite/:token" element={<InvitePage />} />
              <Route path="/orgs/:orgId" element={<OrgPage />} />
              <Route path="/orgs/:orgId/:kind/:containerId" element={<ContainerPage />} />
              <Route path="/orgs/:orgId/cards/:cardId" element={<CardPage />} />
              <Route path="/orgs/:orgId/market" element={<MarketPage />} />
              <Route path="/orgs/:orgId/deals" element={<DealsPage />} />
              <Route path="*" element={<p>Page not found.</p>} />
            </Routes>
          </Suspense>
        </main>
      )}
    </div>
  )
}

export default App

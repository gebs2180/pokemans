import { useAuth0 } from '@auth0/auth0-react'
import { lazy, Suspense } from 'react'
import { Link, Route, Routes, useLocation } from 'react-router'
import { CardPage } from './pages/CardPage'
import { ContainerPage } from './pages/ContainerPage'
import { HomePage } from './pages/HomePage'
import { InvitePage } from './pages/InvitePage'
import { HowItWorksPage } from './pages/HowItWorksPage'
import { DealsPage, MarketPage } from './pages/MarketPage'
import { OrgPage } from './pages/OrgPage'
import { TermsPage } from './pages/TermsPage'
import { Loading, NotFound, RouteErrorBoundary } from './ui'
import { useRouteFocus } from './useRouteFocus'
import { COLLATERAL } from './flags'

// Admin and review screens are only for us, so partners' phones don't download them.
const AdminPage = lazy(() => import('./pages/AdminPage').then((m) => ({ default: m.AdminPage })))
const AdminOutcomesPage = lazy(() => import('./pages/AdminOutcomesPage').then((m) => ({ default: m.AdminOutcomesPage })))
const AdminOffersPage = lazy(() => import('./pages/AdminOffersPage').then((m) => ({ default: m.AdminOffersPage })))
const OfferTrackRecordPage = lazy(() => import('./pages/OfferTrackRecordPage').then((m) => ({ default: m.OfferTrackRecordPage })))
const AdminLabelsPage = lazy(() => import('./pages/AdminLabelsPage').then((m) => ({ default: m.AdminLabelsPage })))
const ShippingChargesPage = lazy(() => import('./pages/ShippingChargesPage').then((m) => ({ default: m.ShippingChargesPage })))
const ReviewQueuePage = lazy(() => import('./pages/ReviewQueuePage').then((m) => ({ default: m.ReviewQueuePage })))
const CenteringLabelsPage = lazy(() => import('./pages/CenteringLabelsPage').then((m) => ({ default: m.CenteringLabelsPage })))
const CenteringLabelCardPage = lazy(() => import('./pages/CenteringLabelsPage').then((m) => ({ default: m.CenteringLabelCardPage })))
const ReviewCardPage = lazy(() => import('./pages/ReviewCardPage').then((m) => ({ default: m.ReviewCardPage })))
const ValuationPage = lazy(() => import('./pages/ValuationPage').then((m) => ({ default: m.ValuationPage })))
const ValuationRunPage = lazy(() => import('./pages/ValuationRunPage').then((m) => ({ default: m.ValuationRunPage })))

function App() {
  const { isLoading, isAuthenticated, user, error, loginWithRedirect, logout } = useAuth0()
  const location = useLocation()
  useRouteFocus()

  return (
    <div className={/^\/admin\/(review|valuation|centering)/.test(location.pathname) ? 'shell wide' : 'shell'}>
      <header className="topbar">
        <Link to="/" className="brand">
          Rawmark
        </Link>
        <Link to="/how-it-works" className="nav-link">
          How it works
        </Link>
        {isAuthenticated && (
          <span className="who">
            <span className="email">{user?.email ?? user?.name}</span>{' '}
            <button className="link" onClick={() => logout({ logoutParams: { returnTo: window.location.origin } })}>
              Log out
            </button>
          </span>
        )}
      </header>

      {error && <p className="error" role="alert">Login error: {error.message}</p>}
      {isLoading && <Loading />}

      {!isLoading && !isAuthenticated && location.pathname === '/how-it-works' && (
        <main>
          <HowItWorksPage />
        </main>
      )}

      {!isLoading && !isAuthenticated && location.pathname !== '/how-it-works' && (
        <main className="welcome">
          <p className="lead">{COLLATERAL ? 'Raw collection collateral reports.' : 'Instant offers on raw cards.'}</p>
          {!COLLATERAL && <p>A small closed test, run by Zach.</p>}
          {location.pathname.startsWith('/invite/') && (
            <p>You've been invited to join Rawmark. Log in, or sign up, to see the invite and accept it.</p>
          )}
          <button className="block" onClick={() => loginWithRedirect({ appState: { returnTo: location.pathname + location.search } })}>
            {location.pathname.startsWith('/invite/') ? 'Log in or sign up' : 'Log in'}
          </button>
        </main>
      )}

      {isAuthenticated && (
        <main>
          <RouteErrorBoundary key={location.pathname}>
          <Suspense fallback={<Loading />}>
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/how-it-works" element={<HowItWorksPage />} />
              <Route path="/admin" element={<AdminPage />} />
              <Route path="/admin/review" element={<ReviewQueuePage />} />
              <Route path="/admin/outcomes" element={<AdminOutcomesPage />} />
              <Route path="/admin/offers" element={<OfferTrackRecordPage />} />
              <Route path="/admin/deals" element={<AdminOffersPage />} />
              <Route path="/admin/shipping" element={<ShippingChargesPage />} />
              <Route path="/admin/labels" element={<AdminLabelsPage />} />
              <Route path="/admin/centering" element={<CenteringLabelsPage />} />
              <Route path="/admin/centering/:orgId/:cardId" element={<CenteringLabelCardPage />} />
              <Route path="/admin/review/:orgId/:cardId" element={<ReviewCardPage />} />
              {COLLATERAL && <Route path="/admin/valuation/:orgId/:collectionId" element={<ValuationPage />} />}
              {COLLATERAL && <Route path="/admin/valuation-runs/:runId" element={<ValuationRunPage />} />}
              <Route path="/invite/:token" element={<InvitePage />} />
              <Route path="/orgs/:orgId" element={<OrgPage />} />
              <Route path="/orgs/:orgId/terms" element={<TermsPage />} />
              <Route path="/orgs/:orgId/:kind/:containerId" element={<ContainerPage />} />
              <Route path="/orgs/:orgId/cards/:cardId" element={<CardPage />} />
              <Route path="/orgs/:orgId/market" element={<MarketPage />} />
              <Route path="/orgs/:orgId/deals" element={<DealsPage />} />
              <Route path="*" element={<NotFound />} />
            </Routes>
          </Suspense>
          </RouteErrorBoundary>
        </main>
      )}
    </div>
  )
}

export default App

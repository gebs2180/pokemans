import { useAuth0 } from '@auth0/auth0-react'
import { Link, Route, Routes, useLocation } from 'react-router'
import { AdminPage } from './pages/AdminPage'
import { CardPage } from './pages/CardPage'
import { ContainerPage } from './pages/ContainerPage'
import { HomePage } from './pages/HomePage'
import { InvitePage } from './pages/InvitePage'
import { OrgPage } from './pages/OrgPage'

function App() {
  const { isLoading, isAuthenticated, user, error, loginWithRedirect, logout } = useAuth0()
  const location = useLocation()

  return (
    <div className="shell">
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

      {error && <p className="error">Login error: {error.message}</p>}
      {isLoading && <p>Loading…</p>}

      {!isLoading && !isAuthenticated && (
        <main>
          <p>Raw collection collateral reports.</p>
          <button onClick={() => loginWithRedirect({ appState: { returnTo: location.pathname } })}>Log in</button>
        </main>
      )}

      {isAuthenticated && (
        <main>
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/admin" element={<AdminPage />} />
            <Route path="/invite/:token" element={<InvitePage />} />
            <Route path="/orgs/:orgId" element={<OrgPage />} />
            <Route path="/orgs/:orgId/:kind/:containerId" element={<ContainerPage />} />
            <Route path="/orgs/:orgId/cards/:cardId" element={<CardPage />} />
            <Route path="*" element={<p>Page not found.</p>} />
          </Routes>
        </main>
      )}
    </div>
  )
}

export default App

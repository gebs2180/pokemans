import { useAuth0 } from '@auth0/auth0-react'
import { useEffect, useState } from 'react'
import { fetchMe, type Me } from './api'

// Placeholder shell. Role-based areas (capture, partner, collector, admin console) arrive in M1+.
function App() {
  const { isLoading, isAuthenticated, user, error, loginWithRedirect, logout, getAccessTokenSilently } = useAuth0()
  const [me, setMe] = useState<Me | null>(null)
  const [apiError, setApiError] = useState<string | null>(null)

  useEffect(() => {
    if (!isAuthenticated) {
      return
    }
    getAccessTokenSilently()
      .then((token) => {
        if (!token) {
          throw new Error('No access token')
        }
        return fetchMe(token)
      })
      .then(setMe)
      .catch((e: unknown) => setApiError(e instanceof Error ? e.message : String(e)))
  }, [isAuthenticated, getAccessTokenSilently])

  return (
    <main className="shell">
      <h1>pokemans</h1>
      <p>Raw collection collateral reports.</p>

      {isLoading && <p>Loading…</p>}
      {error && <p className="error">Login error: {error.message}</p>}

      {!isLoading && !isAuthenticated && <button onClick={() => loginWithRedirect()}>Log in</button>}

      {isAuthenticated && (
        <section>
          <p>Signed in as {user?.email ?? user?.name}</p>
          {me && (
            <p>
              API sees you as <code>{me.subject}</code> with roles: {me.roles.length ? me.roles.join(', ') : 'none'}
            </p>
          )}
          {apiError && <p className="error">{apiError}</p>}
          <button onClick={() => logout({ logoutParams: { returnTo: window.location.origin } })}>Log out</button>
        </section>
      )}
    </main>
  )
}

export default App

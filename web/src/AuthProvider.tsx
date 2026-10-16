import { Auth0Provider, type AppState } from '@auth0/auth0-react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router'

/** Auth0 inside the router, so a login started on a deep link (e.g. an invite) returns there. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  return (
    <Auth0Provider
      domain={import.meta.env.VITE_AUTH0_DOMAIN}
      clientId={import.meta.env.VITE_AUTH0_CLIENT_ID}
      authorizationParams={{
        redirect_uri: window.location.origin,
        audience: import.meta.env.VITE_AUTH0_AUDIENCE,
      }}
      // Rotating refresh tokens kept in localStorage so a reload doesn't force a new login.
      // Needs Refresh Token Rotation on the SPA and Allow Offline Access on the API (docs/auth.md).
      useRefreshTokens
      cacheLocation="localstorage"
      onRedirectCallback={(appState?: AppState) => navigate(appState?.returnTo ?? '/', { replace: true })}
    >
      {children}
    </Auth0Provider>
  )
}

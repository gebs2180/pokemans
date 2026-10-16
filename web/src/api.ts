export type Me = {
  subject: string
  issuer: string | null
  roles: string[]
  claimedRoles: string[]
  mfa: boolean
}

/** Calls the backend with the user's access token. In dev, Vite proxies /api to localhost:8080. */
export async function fetchMe(accessToken: string): Promise<Me> {
  const response = await fetch('/api/me', {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  if (!response.ok) {
    throw new Error(`GET /api/me failed: ${response.status}`)
  }
  return response.json()
}

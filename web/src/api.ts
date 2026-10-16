import { useAuth0 } from '@auth0/auth0-react'
import { useCallback } from 'react'

export type OrgType = 'PARTNER' | 'COLLECTOR'
export type Side = 'FRONT' | 'BACK' | 'DETAIL'
export type PhotoStatus = 'AWAITING_UPLOAD' | 'PASSED' | 'RETAKE'

export type Membership = { orgId: string; orgName: string; orgType: OrgType; role: string }

export type Me = {
  subject: string
  issuer: string | null
  roles: string[]
  claimedRoles: string[]
  mfa: boolean
  memberships: Membership[]
}

export type Org = { id: string; name: string; type: OrgType; createdAt: string; members: number }
export type CreatedInvite = { id: string; token: string; acceptPath: string; expiresAt: string }
export type InvitePreview = { orgName: string; orgType: OrgType; role: string; expiresAt: string; usable: boolean }

export type ContainerSummary = { id: string; name: string; createdAt: string; cards: number }
export type ContainerDetail = { id: string; name: string; createdAt: string; cards: CardView[] }

export type RetakeReason = { code: string; message: string }
export type PhotoView = {
  id: string
  side: Side
  status: PhotoStatus
  retakeReasons: RetakeReason[]
  width: number | null
  height: number | null
  blurScore: number | null
  glareFraction: number | null
  capturedAt: string | null
  uploadedAt: string | null
}

export type CardView = {
  id: string
  label: string
  lot: boolean
  quantity: number
  captureComplete: boolean
  missingSides: Side[]
  photos: PhotoView[]
}

export type UploadSlot = {
  photoId: string
  url: string
  method: string
  headers: Record<string, string>
  expiresAt: string
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/** Fetch wrapper that adds the access token and turns problem-detail responses into ApiError. */
export function useApi() {
  const { getAccessTokenSilently } = useAuth0()

  return useCallback(
    async <T,>(method: string, path: string, body?: unknown): Promise<T> => {
      const token = await getAccessTokenSilently()
      const response = await fetch(path, {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      if (!response.ok) {
        let detail = `${method} ${path} failed: ${response.status}`
        try {
          const problem = await response.json()
          detail = problem.detail ?? problem.title ?? detail
        } catch {
          // not JSON
        }
        throw new ApiError(response.status, detail)
      }
      return (response.status === 204 ? undefined : await response.json()) as T
    },
    [getAccessTokenSilently],
  )
}

/** PUTs a file straight to storage using a presigned upload slot. */
export async function uploadToStorage(slot: UploadSlot, file: Blob): Promise<void> {
  const response = await fetch(slot.url, { method: slot.method, headers: slot.headers, body: file })
  if (!response.ok) {
    throw new Error(`Upload to storage failed: ${response.status}`)
  }
}

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

// Review (admin): identification and condition

export type Edition = 'UNLIMITED' | 'FIRST_EDITION' | 'SHADOWLESS'
export type Variant = 'NORMAL' | 'HOLO' | 'REVERSE_HOLO'
export type Confidence = 'A' | 'B' | 'C' | 'D'
export type AuthenticityRisk = 'NONE' | 'VINTAGE_HOLO' | 'JAPANESE_PROMO' | 'RECENT_CHASE'

export type QueueItem = {
  cardId: string
  orgId: string
  label: string
  createdAt: string
  identified: boolean
  identifiedAs: string | null
  assessment: 'NONE' | 'SYSTEM' | 'REVIEWER'
  confidence: Confidence | null
  openRequests: number
}

export type CatalogHit = { externalId: string; number: string; name: string; setId: string | null; imageUrl: string | null }

export type ProviderCard = {
  externalId: string
  language: string
  setId: string | null
  setName: string
  seriesName: string | null
  setReleaseDate: string | null
  number: string
  name: string
  rarity: string | null
  editions: Edition[]
  variants: Variant[]
  stamps: string[]
  promo: boolean
  imageUrl: string | null
}

export type IdentifiedCard = {
  cardItemId: string
  catalogCardId: string
  language: string
  setName: string
  seriesName: string | null
  setReleaseDate: string | null
  number: string
  name: string
  rarity: string | null
  edition: Edition
  variant: Variant
  stamp: string | null
  promo: boolean
  authenticityRisk: AuthenticityRisk
  imageUrl: string | null
}

export type ReviewPhoto = { id: string; side: Side; url: string; cropUrl: string | null; capturedAt: string | null }

export type PredictionRequestView = {
  id: string
  provider: string
  status: 'PENDING' | 'SUBMITTED' | 'DONE' | 'FAILED'
  attempts: number
  lastError: string | null
  createdAt: string
  finishedAt: string | null
}

export type Prediction = {
  id: string
  provider: string
  modelVersion: string | null
  succeeded: boolean
  overallGrade: number | null
  psaGrade: number | null
  centering: number | null
  corners: number | null
  edges: number | null
  surface: number | null
  details: Record<string, unknown> | null
  error: string | null
  latencyMs: number | null
  createdAt: string
}

/** Outcome ("U", "1" … "10") to probability. */
export type Distribution = Record<string, number>

export type Assessment = {
  id: string
  version: number
  current: boolean
  distribution: Distribution
  median: string
  confidence: Confidence
  authorType: 'SYSTEM' | 'REVIEWER'
  authorId: string | null
  reason: string | null
  adjustments: Distribution | null
  basis: Record<string, unknown>
  createdAt: string
}

export type CardCondition = {
  cardId: string
  orgId: string
  label: string
  enabledProviders: string[]
  photos: ReviewPhoto[]
  identification: IdentifiedCard | null
  requests: PredictionRequestView[]
  predictions: Prediction[]
  assessments: Assessment[]
}

export const OUTCOMES = ['U', '1', '2', '3', '4', '5', '6', '7', '8', '9', '10'] as const

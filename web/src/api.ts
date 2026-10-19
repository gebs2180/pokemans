import { useAuth0 } from '@auth0/auth0-react'
import { useCallback } from 'react'

export type OrgType = 'PARTNER' | 'COLLECTOR' | 'CONTRIBUTOR'

export const ORG_TYPE_LABEL: Record<OrgType, string> = { PARTNER: 'Partner', COLLECTOR: 'Collector', CONTRIBUTOR: 'Contributor' }
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

/** Builds an API path, URL-encoding every interpolated segment: apiPath`/api/orgs/${orgId}`. */
export function apiPath(strings: TemplateStringsArray, ...values: (string | number)[]) {
  return strings.reduce((out, s, i) => out + s + (i < values.length ? encodeURIComponent(values[i]) : ''), '')
}

/** Auth0 errors that mean the session is gone and only a new login helps. */
const LOGIN_ERRORS = new Set(['login_required', 'missing_refresh_token', 'invalid_grant', 'consent_required'])

/** Fetch wrapper that adds the access token and turns problem-detail responses into ApiError; a lost session goes back to login. */
export function useApi() {
  const { getAccessTokenSilently, loginWithRedirect } = useAuth0()

  return useCallback(
    async <T,>(method: string, path: string, body?: unknown): Promise<T> => {
      const relogin = async () => {
        // A backend that rejects every token (no issuer configured, wrong audience) would loop through
        // login forever: redirect at most once a minute, otherwise just report the 401.
        const key = 'pokemans.reloginAt'
        let last = 0
        try {
          last = Number(sessionStorage.getItem(key) ?? 0)
          sessionStorage.setItem(key, String(Date.now()))
        } catch {
          // storage blocked: redirect anyway
        }
        if (Date.now() - last < 60_000) {
          throw new ApiError(401, 'Not signed in: the API rejected your login. Check the API issuer and audience settings.')
        }
        await loginWithRedirect({ appState: { returnTo: window.location.pathname + window.location.search } })
      }
      const token = await getAccessTokenSilently().catch(async (e: unknown) => {
        if (LOGIN_ERRORS.has((e as { error?: string }).error ?? '')) {
          await relogin()
          throw new ApiError(401, 'Your session has ended. Redirecting to log in…')
        }
        throw e
      })
      const response = await fetch(path, {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      if (response.status === 401) {
        await relogin()
        throw new ApiError(401, 'Your session has ended. Redirecting to log in…')
      }
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
      const text = await response.text()
      return (text ? JSON.parse(text) : undefined) as T
    },
    [getAccessTokenSilently, loginWithRedirect],
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
  /** When the card was first confirmed as this catalog card since it was last something else. */
  identifiedSince: string
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

// Contributors: consent and grading results

export type ConsentStatus = { version: string; text: string; acceptedAt: string | null }

export type Grader = 'PSA' | 'BGS' | 'CGC' | 'SGC' | 'TAG'

export type OutcomeView = {
  id: string
  orgId: string
  cardId: string
  cardLabel: string
  source: 'CONTRIBUTOR' | 'PARTNER' | 'ADMIN'
  grader: Grader
  certNumber: string
  grade: number | null
  gradeLabel: string | null
  gradedOn: string | null
  status: 'UNVERIFIED' | 'VERIFIED' | 'MISMATCH'
  verificationNote: string | null
  lookupUrl: string
  createdAt: string
}

// Automatic recognition

export type CatalogCardView = {
  id: string
  provider: 'TCGDEX' | 'MANUAL'
  externalId: string | null
  language: string
  setId: string | null
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

export type CardReadingView = {
  id: string
  reader: string
  status: 'PENDING' | 'DONE' | 'FAILED'
  name: string | null
  number: string | null
  setTotal: number | null
  language: string | null
  firstEditionStamp: boolean | null
  finish: Variant | null
  copyrightYear: number | null
  notes: string | null
  error: string | null
  createdAt: string
}

export type Suggestion = { id: string; card: CatalogCardView; confidence: number; note: string | null }

export type CardIdentificationView = {
  cardId: string
  current: { id: string; card: CatalogCardView } | null
  recognition: boolean
  reading: CardReadingView | null
  suggestions: Suggestion[]
}

// Pricing (admin): sold comps entered by hand and per-grader price tables

export type GradingCompany = 'PSA' | 'BGS' | 'CGC' | 'SGC' | 'TAG' | 'ACE'
export type RawCondition = 'NEAR_MINT' | 'LIGHTLY_PLAYED' | 'MODERATELY_PLAYED' | 'HEAVILY_PLAYED' | 'DAMAGED' | 'ALTERED'
export type ListingType = 'AUCTION' | 'FIXED_PRICE' | 'BEST_OFFER_ACCEPTED' | 'PRIVATE_SALE' | 'UNKNOWN'
export type PriceMethod = 'OBSERVED' | 'INTERPOLATED' | 'STEP_DOWN' | 'CARD_STEP_DOWN' | 'CARRIED_DOWN' | 'NO_SALES'

export type CompRequest = {
  gradingCompany: GradingCompany | null
  grade: number | null
  rawCondition: RawCondition | null
  price: number
  soldOn: string
  listingType: ListingType
  marketplace: string
  url: string | null
  note: string | null
}

export type CompView = {
  id: string
  source: string
  gradingCompany: GradingCompany | null
  grade: number | null
  rawCondition: RawCondition | null
  price: number
  currency: string
  soldOn: string
  listingType: ListingType
  url: string | null
  licence: string
  details: Record<string, unknown>
  createdAt: string
}

export type GradeRow = {
  grade: string
  price: number
  method: PriceMethod
  /** Today's: lowered when no refresh has replaced the table. */
  confidence: Confidence
  pricedConfidence: Confidence
  n: number
  compIds: string[]
  windowDays: number | null
  basis: string[]
  stepDownRatio: number | null
}

export type PriceTable = {
  gradingCompany: GradingCompany
  snapshotSetId: string
  asOf: string
  parameterSet: string
  grades: GradeRow[]
  sigma: number | null
  sigmaChanges: number
  salesPerMonth: number
  daysOld: number
  /** Confidence levels lost because no refresh has replaced this table. */
  staleLevels: number
}

export type PricesView = { catalogCardId: string; comps: CompView[]; tables: PriceTable[]; skipped: string[] }

// Provenance: authenticity findings and custody

export type Finding = 'VERIFIED_AUTHENTIC' | 'SUSPECTED_COUNTERFEIT' | 'COUNTERFEIT'
export type AuthMethod = 'IN_HAND_INSPECTION' | 'GRADER_SLAB' | 'THIRD_PARTY_AUTHENTICATION' | 'PHOTO_REVIEW'
export type AuthState =
  | 'NOT_FLAGGED'
  | 'VERIFIED_AUTHENTIC'
  | 'NOT_IDENTIFIED'
  | 'AWAITING_VERIFICATION'
  | 'SUSPECTED_COUNTERFEIT'
  | 'COUNTERFEIT'
export type HolderType = 'OWNER' | 'PARTNER' | 'VAULT' | 'GRADER' | 'LENDER' | 'OTHER'
export type CustodyMethod = 'IN_PERSON' | 'VAULT_STATEMENT' | 'GRADER_RECEIPT' | 'VIDEO_CALL'
export type CustodyState = 'NOT_RECORDED' | 'UNVERIFIED' | 'VERIFIED' | 'LAPSED'

export type VerificationView = {
  id: string
  status: Finding
  method: AuthMethod
  note: string | null
  catalogCardId: string
  /** False once the card has been re-identified as something else. */
  forCurrentIdentity: boolean
  /** The card's photos the finding was based on. */
  photoIds: string[]
  verifiedBy: string
  createdAt: string
}

export type CustodyView = {
  id: string
  holderType: HolderType
  holderName: string
  location: string
  heldSince: string
  verified: boolean
  verificationMethod: CustodyMethod | null
  verifiedBy: string | null
  note: string | null
  recordedBy: string
  createdAt: string
}

export type ProvenanceView = {
  cardId: string
  authFlagUnresolved: boolean
  authState: AuthState
  authenticityRisk: AuthenticityRisk | null
  /** Methods that can verify this card as genuine (photo review only below high risk); any method can record a doubt. */
  verifyMethods: AuthMethod[]
  verifications: VerificationView[]
  custodyVerified: boolean
  custodyState: CustodyState
  /** When the current custody check lapses; null unless custody is verified. */
  custodyVerifiedUntil: string | null
  custody: CustodyView | null
  custodyHistory: CustodyView[]
}

export type VerifyRequest = { status: Finding; method: AuthMethod; note: string | null; photoIds: string[] }

export type CustodyRequest = {
  holderType: HolderType
  holderName: string
  location: string
  heldSince: string
  verificationMethod: CustodyMethod | null
  note: string | null
}

// Valuation runs and reports (M4)

export type GradingCost = { fee: number; shipping: number; insurance: number; timeToCashRate: number }

export type ValuationDefaults = { parameterSet: string; gradingCompany: string; gradingCost: GradingCost }

/** A run's portfolio figures. Alpha is a suggested range only: the lender sets it. */
export type RunSummary = {
  id: string
  orgId: string
  collectionId: string
  collectionName: string
  asOf: string
  parameterSetVersion: string
  gradingCompany: string
  gradingCost: GradingCost
  inputsSha256: string
  cardCount: number
  valuedCount: number
  eligibleCount: number
  eligibleFlv: number
  collateralBase: number
  rawShare: number
  gradedShare: number
  alphaLow: number
  alphaHigh: number
  advanceLow: number
  advanceHigh: number
  capsApplied: boolean
  capsMinCards: number
  totalRv: number
  note: string | null
  createdAt: string
}

export type TraceStep = { subject: string; rule: string; value: string; detail: string }

export type CardInputs = {
  cardItemId: string
  label: string
  lot: boolean
  quantity: number
  identity: { catalogCardId: string; name: string; setName: string; number: string; language: string } | null
  assessment: { assessmentId: string; version: number; distribution: Record<string, number>; photoConfidence: Confidence } | null
  graded: { outcomeId: string; gradingCompany: string; grade: number; valuedGrade: string; certNumber: string } | null
  prices: { snapshotSetId: string; gradingCompany: string; asOf: string; daysOld: number; staleLevels: number; sigma: number | null } | null
  copiesHeld: number
  provenance: { authFlagUnresolved: boolean; authState: string; custodyVerified: boolean; custodyState: string }
  unvaluedReason: string | null
}

export type CardLine = {
  cardItemId: string
  position: number
  status: 'VALUED' | 'UNVALUED'
  unvaluedReason: string | null
  graded: boolean
  inputs: CardInputs
  fmv: number | null
  rv: number | null
  qsv: number | null
  stressedFmv: number | null
  flv: number | null
  confidence: Confidence | null
  eligible: boolean
  exclusionReasons: string[]
  contribution: number
  insuredRv: number
  insurerNote: string | null
  flvGraded: number | null
  flvRawForLift: number | null
  gradingCost: number | null
  lift: number | null
  gradeFirstRank: number | null
  trace: TraceStep[]
}

export type RunDetail = { run: RunSummary; cards: CardLine[]; portfolioTrace: TraceStep[]; confidenceMix: Record<string, number> }

export type ReportType = 'LENDER' | 'INSURER'

export type ReportView = {
  id: string
  runId: string
  collectionId: string
  type: ReportType
  lenderAlpha: number | null
  templateVersion: string
  sha256: string
  sizeBytes: number
  issuedAt: string
}

export type DownloadLink = { url: string }

export function money(amount: number | null | undefined) {
  return amount == null ? '–' : amount.toLocaleString('en-US', { style: 'currency', currency: 'USD' })
}

export function percent(share: number) {
  return `${Math.round(share * 1000) / 10}%`
}

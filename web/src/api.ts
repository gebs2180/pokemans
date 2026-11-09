import { useAuth0 } from '@auth0/auth0-react'
import { useCallback } from 'react'

export type OrgType = 'PARTNER' | 'COLLECTOR' | 'CONTRIBUTOR' | 'BUYER'

export const ORG_TYPE_LABEL: Record<OrgType, string> = { PARTNER: 'Partner', COLLECTOR: 'Collector', CONTRIBUTOR: 'Contributor', BUYER: 'Buyer' }
export type Side = 'FRONT' | 'BACK' | 'DETAIL'
export const SIDE_LABEL: Record<Side, string> = { FRONT: 'Front', BACK: 'Back', DETAIL: 'Close-up' }
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
export type OpenInvite = { id: string; createdAt: string; expiresAt: string }
export type OrgMember = { id: string; email: string | null; role: string; joinedAt: string }
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
  /** The side's newest passing photo: the one buyers and reviewers see. */
  current: boolean
  /** A short-lived thumbnail link (the card crop if there is one); null in lists. */
  url: string | null
}

export type CardView = {
  id: string
  label: string
  lot: boolean
  quantity: number
  captureComplete: boolean
  missingSides: Side[]
  photos: PhotoView[]
  /** Why the photos can't change any more (posted or sold), or null. */
  photosFrozen: string | null
}

/** Where a seller's card is on the way to a sale. */
export type CardStage =
  | 'PHOTOS'
  | 'LOT'
  | 'IDENTIFYING'
  | 'ASSESSING'
  | 'PRICING'
  | 'NO_OFFER'
  | 'READY'
  | 'OFFERED'
  | 'LISTED'
  | 'SOLD'
  | 'SHIPPED'
  | 'ARRIVED'
  | 'PAID'
  | 'RETURNED'

export const STAGE_LABEL: Record<CardStage, string> = {
  PHOTOS: 'Needs photos',
  LOT: 'Lot: no offers',
  IDENTIFYING: 'Waiting for us to identify it',
  ASSESSING: 'Waiting for condition review',
  PRICING: 'Waiting for prices',
  NO_OFFER: 'No offer yet',
  READY: 'Ready for an offer',
  OFFERED: 'Offer ready',
  LISTED: 'Posted to buyers',
  SOLD: 'Sold',
  SHIPPED: 'Shipped',
  ARRIVED: 'Arrived',
  PAID: 'Paid',
  RETURNED: 'Returned',
}

/** One side's measured ratios in whole percent, left or top border first, e.g. "49/51". */
export type SideCentering = { leftRight: string; topBottom: string }

/** Our own centering measurement; a side that wasn't measured is null. */
export type MeasuredCentering = { front: SideCentering | null; back: SideCentering | null }

/** mostLikely is 0 before there's an assessment; a centering measurement can come first. */
export type ConditionSummary = {
  low: number
  high: number
  mostLikely: number
  mayBeUngradable: boolean
  holdingBack: string[]
  centering: MeasuredCentering | null
}

export type CardStatus = {
  cardId: string
  stage: CardStage
  identity: string | null
  nextStep: string
  canOffer: boolean
  condition: ConditionSummary | null
  /** When the stage runs out: the last day to ship, or when the offer or posting ends. */
  deadline: string | null
}

/** A deadline as a date nobody can misread: "Nov 5". */
export function shortDate(iso: string) {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

/** The stage's deadline, as a date on the card. */
export function deadlineText({ stage, deadline }: Pick<CardStatus, 'stage' | 'deadline'>) {
  if (!deadline) return null
  const label: Partial<Record<CardStage, string>> = { SOLD: 'Ship by', OFFERED: 'Offer good until', LISTED: 'Posted until' }
  return label[stage] ? `${label[stage]} ${shortDate(deadline)}` : null
}

/** What's waiting on a seller, for their home page. */
export type NeedsYouItem = {
  cardId: string
  label: string
  need: 'LABEL_READY' | 'PAYMENT_DUE' | 'PAYMENT_CHECK' | 'CARD_RETURNING' | 'OFFER_EXPIRING' | 'OFFER_READY' | 'GET_OFFER' | 'RETAKE'
  due: string | null
  sides: Side[]
}

/** Deals the admin should look at. */
export type OverdueDeals = { labels: number }

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
      const response = await reach(
        () =>
          fetch(path, {
            method,
            headers: {
              Authorization: `Bearer ${token}`,
              ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
            },
            body: body === undefined ? undefined : JSON.stringify(body),
          }),
        UNREACHABLE,
      )
      if (response.status === 401) {
        await relogin()
        throw new ApiError(401, 'Your session has ended. Redirecting to log in…')
      }
      if (!response.ok) {
        let detail =
          response.status === 403
            ? "You don't have access to this page."
            : response.status === 404
              ? "We couldn't find that."
              : `${method} ${path} failed: ${response.status}`
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

export const UNREACHABLE = "Couldn't reach Rawmark. Check your connection and try again."
export const UPLOAD_FAILED = "The photo didn't upload. Check your connection and try again."

/**
 * A fetch that never reached the server (offline, blocked, dropped) rejects with the browser's own
 * TypeError, e.g. "Failed to fetch"; people see `message` instead.
 */
async function reach(request: () => Promise<Response>, message: string): Promise<Response> {
  try {
    return await request()
  } catch (e) {
    if (e instanceof TypeError) throw new Error(message, { cause: e })
    throw e
  }
}

/** PUTs a file straight to storage using a presigned upload slot. */
export async function uploadToStorage(slot: UploadSlot, file: Blob): Promise<void> {
  const response = await reach(() => fetch(slot.url, { method: slot.method, headers: slot.headers, body: file }), UPLOAD_FAILED)
  if (!response.ok) {
    // An expired slot (403) or a storage hiccup: asking again gets a fresh slot.
    throw new ApiError(response.status, `The photo didn't upload (storage answered ${response.status}). Please try again.`)
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
  assessment: 'NONE' | 'SYSTEM' | 'REVIEWER' | 'PHOTOS_CHANGED'
  confidence: Confidence | null
  openRequests: number
  /** Its catalog card has a PSA price table: without one the seller is stuck on "Waiting for prices". */
  priced: boolean
  /** Whose card it is. */
  orgName: string | null
}

export type CatalogHit = {
  externalId: string
  number: string
  name: string
  setId: string | null
  imageUrl: string | null
  /** e.g. "Base Set", when the catalog says */
  setName: string | null
  /** the size printed after the slash (102 in 4/102), when the catalog says */
  setTotal: number | null
}

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

// Centering labels (admin): the printed frame read by hand

export type CenteringFace = 'FRONT' | 'BACK'

/** Borders in mm from the card's edge; null is "can't see it". */
export type CenteringLabel = {
  id: string
  face: CenteringFace
  photoId: string
  left: number | null
  right: number | null
  top: number | null
  bottom: number | null
  hardCard: boolean
  labelledBy: string
  createdAt: string
}

/** @param refused the latest measurement refused this face; null if none ran. Its numbers aren't shown, so they can't bias a label. */
export type CenteringFaceView = { label: CenteringLabel | null; refused: boolean | null; photos: number }

export type CenteringQueueItem = {
  cardId: string
  orgId: string
  label: string
  orgName: string | null
  identifiedAs: string | null
  front: CenteringFaceView
  back: CenteringFaceView
}

export type CenteringCard = {
  cardId: string
  orgId: string
  label: string
  identifiedAs: string | null
  front: CenteringLabel | null
  back: CenteringLabel | null
  photos: { id: string; face: CenteringFace; uploadedAt: string }[]
}

/** One photo straightened as the measurer straightens it; refused says why not, and the images are null then. */
export type StraightenedCard = {
  photoId: string
  pxPerMm: number
  width: number
  height: number
  image: string | null
  contrast: string | null
  refused: string | null
}

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
  centeringCap: number | null
  /** The front or back was retaken since the current distribution: it doesn't count until assessed again. */
  photosChanged: boolean
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
  source: 'CONTRIBUTOR' | 'PARTNER' | 'ADMIN' | 'BUYER'
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

/** Which PokeTrace card a catalog card is; poketraceId is null when never mapped or unmapped. */
export type PokeTraceMappingView = {
  enabled: boolean
  poketraceId: string | null
  poketraceCard: Record<string, unknown> | null
  overrideReason: string | null
  mappedBy: string | null
  mappedAt: string | null
}

/** What pulling a card from PokeTrace now stored; repriced is false when another refresh was running. */
export type PokeTracePull = {
  fetched: number
  inserted: number
  duplicates: number
  rejected: number
  errors: string[]
  repriced: boolean
}

export type PokeTraceCandidate = {
  poketraceId: string
  name: string | null
  cardNumber: string | null
  variant: string | null
  setName: string | null
  disagreements: string[]
}

/**
 * An identified card with no PokeTrace mapping yet. searchedAt is null when PokeTrace hasn't been searched;
 * suggestedId is the one clean match, when there is exactly one. A suggestion prices nothing until approved.
 */
export type PokeTraceQueueItem = {
  catalogCardId: string
  orgId: string
  cardItemId: string
  name: string
  setName: string | null
  number: string | null
  edition: Edition | null
  variant: Variant | null
  language: string | null
  searchedAt: string | null
  suggestedId: string | null
  candidates: PokeTraceCandidate[]
  error: string | null
}

export type PokeTraceQueue = { enabled: boolean; searching: boolean; items: PokeTraceQueueItem[] }

export type PokeTraceApproval = { mapped: string[]; failures: { catalogCardId: string; error: string }[] }

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
  /** True when the concentration caps lowered at least one card. */
  capsApplied: boolean
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

/** A dollar amount as typed, e.g. "1,234.50" or "$1234": NaN unless it is a plain number once "$" and commas are dropped. */
export function parseMoney(text: string): number {
  const plain = text.replace(/[$,\s]/g, '')
  return /^\d+(\.\d{1,2})?$/.test(plain) ? Number(plain) : NaN
}

export { money, percent } from './format'

// Instant offers (2026-10-19): one firm price, a prepaid label on accept

/** What a buyer could pay for the card at one grade, and how likely the grade is: how the firm price was reached. */
export type Rung = { grade: string; probability: number; price: number }

export type Offer = {
  id: string
  /** The firm price. */
  price: number
  fmv: number
  confidence: Confidence
  /** The margin left to the buyer, set by the confidence (per the parameter set; usePolicy().margins has them). */
  buyerMargin: number
  graded: boolean
  subjectToAuthentication: boolean
  ladder: Rung[]
  createdAt: string
  expiresAt: string
  expired: boolean
  /** Set once we voided the offer: it can't be posted, the seller gets a new one. */
  voided: Undo | null
  /** What each grade recently sold for, from the comps stored with the offer; null when that's switched off. */
  sold: SoldPrice[] | null
  /** Why the confidence isn't an A, in the order they weigh; empty at an A. */
  lessSureBecause: LessSure[]
  /** The raw sanity check: for the seller and the admin only (null to buyers, for offers before it, or with sold prices off). */
  rawSales?: RawSales | null
}

/**
 * The card's raw near-mint sales of the 90 days before the quote. `below` flags an offer under the lowest of them:
 * a flag only, the offer stands (deep review 2026-11-09, B1).
 */
export type RawSales = { lowest: number | null; sales: number; since: string; below: boolean }

/** One grade's sold price as the offer used it: the recency-weighted median of so many sales over the window. */
export type SoldPrice = { gradingCompany: string; grade: string; price: number; sales: number; windowDays: number }

export type LessSure = 'PHOTOS' | 'FEW_SALES' | 'OLD_SALES' | 'ESTIMATED_GRADES'

/** The confidence in words, for sellers and buyers who don't know what a B means. */
export const LESS_SURE: Record<LessSure, string> = {
  PHOTOS: 'the photos leave its condition less certain',
  FEW_SALES: 'there are only a few recent sales',
  OLD_SALES: "the sales haven't been refreshed lately",
  ESTIMATED_GRADES: "a grade it's likely to be has no sales of its own, so we estimated it",
}

export const CONFIDENCE_WORDS: Record<Confidence, string> = {
  A: 'very sure',
  B: 'fairly sure',
  C: 'less sure',
  D: 'not sure enough to offer',
}

/** "Fairly sure (B): there are only a few recent sales." */
export function confidenceInWords(offer: Pick<Offer, 'confidence' | 'lessSureBecause'>) {
  const head = `${CONFIDENCE_WORDS[offer.confidence]} (${offer.confidence})`
  const why = (offer.lessSureBecause ?? []).map((r) => LESS_SURE[r])
  const text = why.length ? `${head}, because ${joinAnd(why)}` : head
  return text.charAt(0).toUpperCase() + text.slice(1) + '.'
}

function joinAnd(parts: string[]) {
  return parts.length < 2 ? parts.join('') : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
}

/**
 * What the label would cost the buyer, stored nowhere and with no label bought. quoted: a live rate for this
 * buyer's address; otherwise our usual postage, because there's no address or the quote failed.
 */
export type PostageEstimate = {
  postage: number
  insurance: number
  total: number
  carrier: string | null
  service: string | null
  quoted: boolean
}

/** Something the admin undid (2026-10-21): a voided offer or listing, or a cancelled deal, and why. */
export type Undo = { reason: string; at: string; byUserId?: string; by?: string | null }

/**
 * VOIDED: we took the listing down with its offer. CANCELLED: a buyer took it and we undid the deal. BACK: a
 * buyer took it and sent it back, and the seller has it again.
 */
export type ListingStatus = 'OPEN' | 'ACCEPTED' | 'WITHDRAWN' | 'EXPIRED' | 'VOIDED' | 'CANCELLED' | 'BACK'
export type Party = { orgName: string | null; email: string | null }
export type DeclinedReason = 'NOT_GENUINE' | 'NOT_THE_LISTED_CARD' | 'DOES_NOT_MATCH_PHOTOS'

export const RETURN_REASON: Record<DeclinedReason, string> = {
  NOT_GENUINE: 'not genuine',
  NOT_THE_LISTED_CARD: 'not the listed card',
  DOES_NOT_MATCH_PHOTOS: "doesn't match its photos",
}

export type PaymentMethod = 'PAYPAL' | 'VENMO' | 'ZELLE'

export const PAYMENT_METHOD: Record<PaymentMethod, string> = { PAYPAL: 'PayPal', VENMO: 'Venmo', ZELLE: 'Zelle' }

/** How a seller gets paid, off the app. A buyer sees it only once they've accepted. */
export type PaymentHandle = { method: PaymentMethod; handle: string }

export type ShippingLabel = {
  carrier: string
  service: string
  trackingCode: string | null
  /** The printable label, for the seller only, until it's used: it shows the seller's address, which buyers never see. */
  labelUrl: string | null
  insuredValue: number
  boughtAt: string
  /** When the seller must hand it to the carrier. */
  shipBy: string
}

/** What happened once the card arrived. INSPECTED is a grade recorded under the per-grade offers that came before. */
export type Outcome = {
  type: 'RECEIVED' | 'RETURNED' | 'INSPECTED'
  /** Only on a RECEIVED from before payment became a step of its own: use progress.pricePaid. */
  pricePaid: number | null
  returnedReason: DeclinedReason | null
  inspectedGrade: number | null
  note: string | null
  recordedAt: string
}

export type DealStage = 'SOLD' | 'SHIPPED' | 'ARRIVED' | 'PAID' | 'RETURNED' | 'BACK'

/** What each side has said about a deal. Rawmark doesn't handle the money, so this is their word, not a fact we checked. */
export type DealProgress = {
  stage: DealStage
  shippedAt: string | null
  arrivedAt: string | null
  /** The policy's payWithinDays from the recorded arrival. */
  payBy: string | null
  /** When the buyer says they paid. */
  paidAt: string | null
  pricePaid: number | null
  paidNote: string | null
  /** When the seller said they got paid. */
  confirmedAt: string | null
  /** When the seller raised something that's still open. */
  disputedAt: string | null
  /** What they said: never sent to the buyer. */
  disputeNote: string | null
  /** How the admin last closed something on this deal, written for both sides. */
  review: Undo | null
  /** Returned as not genuine and not reviewed yet: no new offer. */
  held: boolean
  cardBackAt: string | null
}

export type Listing = {
  id: string
  offerId: string
  status: ListingStatus
  listedAt: string
  expiresAt: string
  closedAt: string | null
  buyer: Party | null
  label: ShippingLabel | null
  outcome: Outcome | null
  /** Why we took it down (VOIDED) or cancelled the deal (CANCELLED). */
  undone: Undo | null
  /** Where the deal is, once a buyer took it; null otherwise or once cancelled. */
  progress: DealProgress | null
}

export type Sale = { offer: Offer | null; listing: Listing | null }

export type OfferCard = {
  name: string
  setName: string
  number: string
  language: string
  edition: string
  variant: string
  label: string
  distribution: Record<string, number>
  gradedAs: string | null
}

/** url: the small copy for lists. fullUrl: the photo as taken, with no crop or re-encode, for a close look. */
export type PhotoLink = { side: Side; url: string; fullUrl: string }

export type MarketItem = { listingId: string; card: OfferCard; offer: Offer; photos: PhotoLink[]; listedAt: string; expiresAt: string }

export type Deal = {
  listingId: string
  card: OfferCard
  offer: Offer
  photos: PhotoLink[]
  seller: Party
  acceptedAt: string
  label: ShippingLabel | null
  outcome: Outcome | null
  /** Set once we cancelled the deal: nothing more to record, and the label isn't used. */
  cancelled: Undo | null
  /** How the seller gets paid. Null once nothing is owed, or if they gave none. */
  payTo: PaymentHandle | null
  progress: DealProgress | null
}

export type DealOutcomeKind = 'GRADED' | 'RESOLD_RAW' | 'KEPT'

/** The newest thing the buyer said happened to a card after its deal. Corrections are new rows: earlier counts the ones before. */
export type RecordedDealOutcome = {
  id: string
  listingId: string
  offerId: string
  cardItemId: string
  buyerOrgId: string
  kind: DealOutcomeKind
  gradeOutcomeId: string | null
  grader: Grader | null
  certNumber: string | null
  /** As on the label, half grades kept; null for a result without a number. */
  grade: number | null
  gradeLabel: string | null
  gradedOn: string | null
  certStatus: OutcomeView['status'] | null
  lookupUrl: string | null
  resalePrice: number | null
  note: string | null
  recordedAt: string
  earlier: number
}

/** A buyer org's record: of the cards it graded, how many came back within one grade of our prediction. */
export type BuyerOutcomeRecord = {
  graded: number
  withinOne: number
  verified: number
  resold: number
  kept: number
  outcomes: RecordedDealOutcome[]
}

export type CalibrationSummary = { graded: number; withinOne: number; belowStressed: number }

/** One deal's outcome against its offer's prediction. The prediction fields are null when the offer priced a card already graded. */
export type AdminDealOutcomeRow = {
  listingId: string
  offerId: string
  buyerOrgId: string
  buyer: string | null
  card: OfferCard
  offerPrice: number
  confidence: Confidence | null
  predicted: Record<string, number> | null
  median: string | null
  stressShift: number | null
  stressedMedian: string | null
  outcome: RecordedDealOutcome
  returnedGrade: string | null
  withinOne: boolean | null
  belowStressed: boolean | null
}

export type AdminCalibration = {
  all: CalibrationSummary
  verified: CalibrationSummary
  resold: number
  kept: number
  rows: AdminDealOutcomeRow[]
}

/** A deal whose label hasn't been bought, for the admin. */
export type AwaitingLabel = {
  listingId: string
  buyerOrgId: string
  buyer: Party
  seller: Party
  card: OfferCard
  offer: Offer
  photos: PhotoLink[]
  acceptedAt: string
}

/** The admin's preview of a card's offer, stored nowhere. */
export type OfferPreview = {
  offered: boolean
  refusal: string | null
  price: number | null
  fmv: number | null
  confidence: Confidence | null
  buyerMargin: number | null
  /** The steps that set the confidence: parameters, σ fallback, base, penalties, derived grades. */
  why: TraceStep[]
  /** The raw sanity check, or null when there's no offer. */
  rawSales?: RawSales | null
}

/** @param labelPending why the label didn't come with the accept, or null */
export type AcceptResult = { deals: Deal[]; labelPending: string | null }

export type ShipAddress = {
  name: string
  company: string | null
  street1: string
  street2: string | null
  city: string
  state: string
  zip: string
  country: string
  phone: string | null
  /** How a seller gets paid; a buyer's address has neither. */
  paymentMethod: PaymentMethod | null
  paymentHandle: string | null
  recordedAt?: string
}

export type ChargeLine = {
  listingId: string
  boughtAt: string
  carrier: string
  service: string
  trackingCode: string | null
  postage: number
  insurance: number
  /** null, or the refund's state: "requested" until the provider answers, then its answer. */
  refund: string | null
  /** False once the deal was cancelled: its postage is ours, refunded or not. */
  billed: boolean
}

export type BuyerCharges = {
  buyerOrgId: string
  buyerName: string | null
  labels: number
  postage: number
  insurance: number
  total: number
  lines: ChargeLine[]
}

/** Acceptance among listings in one price band (or all). Rates are null when nothing has closed. */
export type Acceptance = {
  band: string
  listings: number
  open: number
  accepted: number
  withdrawn: number
  expired: number
  closed: number
  acceptanceRate: number | null
  medianHoursToAccept: number | null
  /** Listings we took down and deals we cancelled: left out of the rate. */
  voided: number
  cancelled: number
}

export type Deliveries = {
  taken: number
  labels: number
  /** Labelled, nothing recorded yet. */
  inTransit: number
  /** Taken, no label bought yet, nothing recorded. */
  awaitingLabel: number
  received: number
  returned: number
  returnRate: number | null
  returnedBy: Partial<Record<DeclinedReason, number>>
  earlierModel: number
  medianHoursToArrival: number | null
}

export type PriceCheck = {
  compared: number
  belowOffer: number
  atOffer: number
  aboveOffer: number
  medianRatio: number | null
  totalPaid: number
  totalOffered: number
}

export type ShippingCost = { labels: number; postage: number; insurance: number; meanPerLabel: number | null }

export type TrackRecord = {
  asOf: string
  overall: Acceptance
  byPriceBand: Acceptance[]
  deliveries: Deliveries
  prices: PriceCheck
  shipping: ShippingCost
  caveats: string[]
}

/** A label refund the admin asked for, and the provider's answer (null when it couldn't be asked). */
export type LabelRefund = Undo & { providerStatus: string | null; providerError: string | null }

/** An offer's latest listing, as the admin sees it. */
export type AdminListing = {
  id: string
  status: ListingStatus
  listedAt: string
  closedAt: string | null
  buyerOrgId: string | null
  buyer: Party | null
  label: ShippingLabel | null
  labelRefund: LabelRefund | null
  outcome: Outcome['type'] | null
  undone: Undo | null
}

/** NOT_PAID: the seller says they weren't paid. RETURNED: a return to review. RETURN_DISPUTED: the seller disagrees with one. */
export type AttentionKind = 'NOT_PAID' | 'RETURNED' | 'RETURN_DISPUTED'

/** Something on a deal the admin has to act on. */
export type DealAttention = {
  listingId: string
  kind: AttentionKind
  since: string
  note: string | null
  sellerOrgId: string
  cardItemId: string
  card: OfferCard
  price: number
  buyer: Party
  seller: Party
  trackingCode: string | null
  outcome: Outcome | null
  progress: DealProgress | null
}

/** Admin: one offer with its latest listing, to void, cancel or refund (2026-10-21). */
export type AdminOffer = {
  offerId: string
  orgId: string
  sellerName: string | null
  cardItemId: string
  card: OfferCard
  price: number
  fmv: number
  confidence: Confidence
  createdAt: string
  expiresAt: string
  expired: boolean
  voided: Undo | null
  listing: AdminListing | null
}

/**
 * The rungs worth showing: every grade the card might come back as, then the best price for anything
 * lower, which caps every grade below it.
 */
export function likelyRungs(ladder: Rung[]): { likely: Rung[]; lower: Rung | null } {
  const gradable = ladder.filter((r) => r.grade !== 'U')
  const likely = gradable.filter((r) => r.probability > 0)
  const lowest = likely.length ? Number(likely[likely.length - 1].grade) : 11
  const lower = gradable.find((r) => Number(r.grade) < lowest) ?? null
  return { likely, lower }
}

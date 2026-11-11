/**
 * The web app's API types, checked against the backend's (architecture review #32, docs/api-types.md).
 *
 * api.gen.ts is generated from openapi.json, which the backend's OpenApiSpecTest writes. The hand-written
 * types in api.ts stay the ones the app uses, because only they say which fields can be null. Each line here
 * fails `tsc` when the backend no longer sends a field the web type reads, or sends it as another type: the
 * error names the field. Fields only the backend has are fine, and null is ignored on both sides.
 */
import type * as A from './api'
import type { components } from './api.gen'

type S = components['schemas']

/** Without null or undefined, all the way down. Optional fields stay optional. */
type Strip<T> = T extends readonly (infer U)[]
  ? Strip<NonNullable<U>>[]
  : T extends object
    ? { [K in keyof T]: Strip<NonNullable<T[K]>> }
    : NonNullable<T>

/** A Java Object (in a Map<String, Object>): the backend doesn't say what's in it. */
type Unsaid<G> = string extends keyof G ? ([G[keyof G]] extends [never] ? true : false) : false

/**
 * Whether a value the backend sends as G fits the web's W. Where the backend says only `string` (a Java
 * String), the web may name the values it expects ('PSA' | 'BGS'); where the backend has an enum, the web's
 * values must cover it.
 */
type Fits<G, W> = Unsaid<G> extends true
  ? true
  : [G] extends [readonly (infer GU)[]]
  ? [W] extends [readonly (infer WU)[]]
    ? Fits<GU, WU>
    : false
  : [G] extends [string]
    ? string extends G
      ? [W] extends [string] ? true : false
      : [G] extends [W] ? true : false
    : [G] extends [object]
      ? [W] extends [object]
        ? [Mismatched<G, W>] extends [never] ? true : false
        : false
      : [G] extends [W] ? true : false

/** The fields of W that G doesn't send (unless W can do without), or sends as another type. */
type Mismatched<G, W> = {
  [K in keyof W]-?: K extends keyof G
    ? Fits<NonNullable<G[K]>, NonNullable<W[K]>> extends true ? never : K
    : {} extends Pick<W, K> ? never : K
}[keyof W]

/** true when what the backend sends (G) has everything the web type (W) reads. */
type Covers<G, W> = [Mismatched<Strip<G>, Strip<W>>] extends [never]
  ? true
  : { fieldsTheBackendDoesntSendThisWay: Mismatched<Strip<G>, Strip<W>> }

type Ok<T extends true> = T

export type ApiTypesMatchTheBackend = {
  Membership: Ok<Covers<S['MeControllerMembershipView'], A.Membership>>
  Me: Ok<Covers<S['MeControllerMe'], A.Me>>
  Org: Ok<Covers<S['AdminOrgControllerOrgView'], A.Org>>
  CreatedInvite: Ok<Covers<S['AdminOrgControllerInviteView'], A.CreatedInvite>>
  OpenInvite: Ok<Covers<S['InviteServiceOpenInvite'], A.OpenInvite>>
  OrgMember: Ok<Covers<S['InviteServiceMember'], A.OrgMember>>
  InvitePreview: Ok<Covers<S['InviteServiceInvitePreview'], A.InvitePreview>>
  ContainerSummary: Ok<Covers<S['IntakeViewsContainerSummary'], A.ContainerSummary>>
  ContainerDetail: Ok<Covers<S['IntakeViewsContainerDetail'], A.ContainerDetail>>
  RetakeReason: Ok<Covers<S['IntakeViewsReasonView'], A.RetakeReason>>
  PhotoView: Ok<Covers<S['IntakeViewsPhotoView'], A.PhotoView>>
  CardView: Ok<Covers<S['IntakeViewsCardView'], A.CardView>>
  SideCentering: Ok<Covers<S['AssessmentsSideCentering'], A.SideCentering>>
  MeasuredCentering: Ok<Covers<S['AssessmentsMeasuredCentering'], A.MeasuredCentering>>
  ConditionSummary: Ok<Covers<S['AssessmentsSummary'], A.ConditionSummary>>
  CardStatus: Ok<Covers<S['CardStatusServiceCardStatus'], A.CardStatus>>
  NeedsYouItem: Ok<Covers<S['CardStatusServiceNeedsYou'], A.NeedsYouItem>>
  OverdueDeals: Ok<Covers<S['ListingViewsOverdue'], A.OverdueDeals>>
  UploadSlot: Ok<Covers<S['IntakeViewsUploadSlot'], A.UploadSlot>>
  QueueItem: Ok<Covers<S['ConditionControllerQueueItem'], A.QueueItem>>
  CatalogHit: Ok<Covers<S['CatalogProviderHit'], A.CatalogHit>>
  ProviderCard: Ok<Covers<S['CatalogControllerProviderCardView'], A.ProviderCard>>
  IdentifiedCard: Ok<Covers<S['CatalogIdentifiedCard'], A.IdentifiedCard>>
  ReviewPhoto: Ok<Covers<S['ConditionControllerPhotoLink'], A.ReviewPhoto>>
  CenteringLabel: Ok<Covers<S['CenteringLabelControllerLabelView'], A.CenteringLabel>>
  CenteringFaceView: Ok<Covers<S['CenteringLabelControllerFaceView'], A.CenteringFaceView>>
  CenteringQueueItem: Ok<Covers<S['CenteringLabelControllerQueueItem'], A.CenteringQueueItem>>
  CenteringCard: Ok<Covers<S['CenteringLabelControllerCardView'], A.CenteringCard>>
  StraightenedCard: Ok<Covers<S['CenteringLabelsStraightened'], A.StraightenedCard>>
  PredictionRequestView: Ok<Covers<S['ConditionControllerRequestView'], A.PredictionRequestView>>
  Prediction: Ok<Covers<S['ConditionControllerPredictionView'], A.Prediction>>
  Assessment: Ok<Covers<S['ConditionControllerAssessmentView'], A.Assessment>>
  CardCondition: Ok<Covers<S['ConditionControllerCardConditionView'], A.CardCondition>>
  ConsentStatus: Ok<Covers<S['ConsentControllerConsentStatus'], A.ConsentStatus>>
  OutcomeView: Ok<Covers<S['OutcomeControllerOutcomeView'], A.OutcomeView>>
  CatalogCardView: Ok<Covers<S['CatalogControllerCatalogCardView'], A.CatalogCardView>>
  CardReadingView: Ok<Covers<S['CatalogControllerReadingView'], A.CardReadingView>>
  Suggestion: Ok<Covers<S['CatalogControllerSuggestionView'], A.Suggestion>>
  CardIdentificationView: Ok<Covers<S['CatalogControllerCardIdentificationView'], A.CardIdentificationView>>
  CompRequest: Ok<Covers<S['PricingControllerCompRequest'], A.CompRequest>>
  CompView: Ok<Covers<S['PricingControllerCompView'], A.CompView>>
  GradeRow: Ok<Covers<S['PricingControllerGradeRow'], A.GradeRow>>
  PriceTable: Ok<Covers<S['PricingControllerTableView'], A.PriceTable>>
  PricesView: Ok<Covers<S['PricingControllerPricesView'], A.PricesView>>
  PokeTraceMappingView: Ok<Covers<S['PokeTraceControllerMappingView'], A.PokeTraceMappingView>>
  PokeTracePull: Ok<Covers<S['PokeTraceControllerPullView'], A.PokeTracePull>>
  PokeTraceCandidate: Ok<Covers<S['PokeTraceSuggestionsCandidate'], A.PokeTraceCandidate>>
  PokeTraceQueueItem: Ok<Covers<S['PokeTraceSuggestionsQueueItem'], A.PokeTraceQueueItem>>
  PokeTraceQueue: Ok<Covers<S['PokeTraceQueueControllerQueueView'], A.PokeTraceQueue>>
  PokeTraceApproval: Ok<Covers<S['PokeTraceSuggestionsApproval'], A.PokeTraceApproval>>
  PokeTracePullGap: Ok<Covers<S['PokeTracePullGapsGap'], A.PokeTracePullGap>>
  PokeTracePullGaps: Ok<Covers<S['PokeTracePullGapControllerPullGapsView'], A.PokeTracePullGaps>>
  VerificationView: Ok<Covers<S['ProvenanceControllerVerificationView'], A.VerificationView>>
  CustodyView: Ok<Covers<S['ProvenanceControllerCustodyView'], A.CustodyView>>
  ProvenanceView: Ok<Covers<S['ProvenanceControllerProvenanceView'], A.ProvenanceView>>
  VerifyRequest: Ok<Covers<S['ProvenanceControllerVerifyRequest'], A.VerifyRequest>>
  CustodyRequest: Ok<Covers<S['ProvenanceControllerCustodyRequest'], A.CustodyRequest>>
  GradingCost: Ok<Covers<S['GradingCost'], A.GradingCost>>
  ValuationDefaults: Ok<Covers<S['ValuationControllerDefaults'], A.ValuationDefaults>>
  RunSummary: Ok<Covers<S['ValuationsRunSummary'], A.RunSummary>>
  TraceStep: Ok<Covers<S['TraceStep'], A.TraceStep>>
  CardInputs: Ok<Covers<S['CardInputs'], A.CardInputs>>
  CardLine: Ok<Covers<S['ValuationsCardLine'], A.CardLine>>
  RunDetail: Ok<Covers<S['ValuationsRunDetail'], A.RunDetail>>
  ReportView: Ok<Covers<S['ReportControllerReportView'], A.ReportView>>
  DownloadLink: Ok<Covers<S['ReportControllerDownloadLink'], A.DownloadLink>>
  Rung: Ok<Covers<S['ListingViewsRungView'], A.Rung>>
  Offer: Ok<Covers<S['ListingViewsOfferView'], A.Offer>>
  RawSales: Ok<Covers<S['ListingViewsRawSalesView'], A.RawSales>>
  SoldPrice: Ok<Covers<S['ListingViewsSoldView'], A.SoldPrice>>
  PostageEstimate: Ok<Covers<S['ShippingEstimate'], A.PostageEstimate>>
  Undo: Ok<Covers<S['OfferAdminServiceUndo'], A.Undo>>
  Party: Ok<Covers<S['ListingViewsParty'], A.Party>>
  PaymentHandle: Ok<Covers<S['PaymentHandle'], A.PaymentHandle>>
  ShippingLabel: Ok<Covers<S['ListingViewsLabelView'], A.ShippingLabel>>
  Outcome: Ok<Covers<S['ListingViewsOutcomeView'], A.Outcome>>
  DealProgress: Ok<Covers<S['ListingStateMachineProgress'], A.DealProgress>>
  Listing: Ok<Covers<S['ListingViewsListingView'], A.Listing>>
  Sale: Ok<Covers<S['ListingViewsSaleView'], A.Sale>>
  OfferCard: Ok<Covers<S['ListingViewsCardView'], A.OfferCard>>
  PhotoLink: Ok<Covers<S['ListingViewsPhotoLink'], A.PhotoLink>>
  MarketItem: Ok<Covers<S['ListingViewsMarketItem'], A.MarketItem>>
  Deal: Ok<Covers<S['ListingViewsDealView'], A.Deal>>
  RecordedDealOutcome: Ok<Covers<S['DealOutcomesRecorded'], A.RecordedDealOutcome>>
  BuyerOutcomeRecord: Ok<Covers<S['DealOutcomeServiceBuyerRecord'], A.BuyerOutcomeRecord>>
  CalibrationSummary: Ok<Covers<S['DealOutcomeServiceSummary'], A.CalibrationSummary>>
  AdminDealOutcomeRow: Ok<Covers<S['DealOutcomeServiceAdminRow'], A.AdminDealOutcomeRow>>
  AdminCalibration: Ok<Covers<S['DealOutcomeServiceAdminCalibration'], A.AdminCalibration>>
  AwaitingLabel: Ok<Covers<S['ListingViewsAwaitingLabel'], A.AwaitingLabel>>
  OfferPreview: Ok<Covers<S['OfferServicePreview'], A.OfferPreview>>
  TenCap: Ok<Covers<S['OfferLadderTenCap'], A.TenCap>>
  AcceptResult: Ok<Covers<S['OfferControllerAcceptResult'], A.AcceptResult>>
  ShipAddress: Ok<Covers<S['OfferControllerAddressView'], A.ShipAddress>>
  ChargeLine: Ok<Covers<S['ShippingBillingChargeLine'], A.ChargeLine>>
  BuyerCharges: Ok<Covers<S['ShippingBillingBuyerCharges'], A.BuyerCharges>>
  Acceptance: Ok<Covers<S['TrackRecordServiceAcceptance'], A.Acceptance>>
  Deliveries: Ok<Covers<S['TrackRecordServiceDeliveries'], A.Deliveries>>
  PriceCheck: Ok<Covers<S['TrackRecordServicePriceCheck'], A.PriceCheck>>
  ShippingCost: Ok<Covers<S['TrackRecordServiceShippingCost'], A.ShippingCost>>
  TrackRecord: Ok<Covers<S['TrackRecordServiceTrackRecord'], A.TrackRecord>>
  LabelRefund: Ok<Covers<S['OfferAdminServiceRefund'], A.LabelRefund>>
  AdminListing: Ok<Covers<S['OfferAdminServiceListing'], A.AdminListing>>
  DealAttention: Ok<Covers<S['OfferAdminServiceAttention'], A.DealAttention>>
  AdminOffer: Ok<Covers<S['OfferAdminServiceOffer'], A.AdminOffer>>
}

/** The checker itself: each of these must fail, or tsc says the expect-error is unused. */
export type TheCheckCatchesDrift = {
  // A field the backend doesn't send.
  // @ts-expect-error
  missing: Ok<Covers<S['MeControllerMe'], A.Me & { renamed: string }>>
  // A field sent as another type.
  // @ts-expect-error
  retyped: Ok<Covers<S['MeControllerMe'], Omit<A.Me, 'mfa'> & { mfa: string }>>
  // A backend enum value the web type doesn't know.
  // @ts-expect-error
  enumValue: Ok<Covers<S['ListingViewsListingView'], Omit<A.Listing, 'status'> & { status: 'OPEN' | 'ACCEPTED' }>>
  // Nested: a field of a field.
  // @ts-expect-error
  nested: Ok<Covers<S['MeControllerMe'], Omit<A.Me, 'memberships'> & { memberships: (A.Membership & { gone: number })[] }>>
}

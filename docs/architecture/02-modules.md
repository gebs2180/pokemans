# Backend modules and web app

The Spring Boot app is a modular monolith under `com.pokemans.app`, one package per module, checked by Spring Modulith (`ModularityTest`: no cycles). `valuation-core` is a separate Gradle module with no Spring and no I/O. Drawn from the actual `import` statements, as of 2026-11-02.

## Backend module dependencies

An arrow means "imports from". The number is how many files in the source module import the target.

```mermaid
flowchart TB
  subgraph app[backend/app]
    offers
    reporting
    valuation
    condition
    provenance
    pricing
    outcomes
    catalog
    intake
    identity
    storage
  end
  core[[backend/valuation-core<br/>GradePricer · CardValuator · OfferLadder<br/>PortfolioEvaluator · ParameterSet]]

  offers -->|11| identity
  offers -->|4| intake
  offers -->|4| valuation
  offers -->|2| storage
  offers -->|1| condition
  offers -->|1| provenance
  offers --> core

  reporting -->|4| valuation
  reporting -->|3| identity
  reporting -->|3| storage
  reporting -->|1| intake

  valuation -->|1 each| catalog & condition & identity & intake & outcomes & pricing & provenance
  valuation --> core

  condition -->|10| intake
  condition -->|9| storage
  condition -->|5| catalog
  condition -->|4| identity
  condition -->|1| pricing

  provenance -->|5| catalog
  provenance -->|2| intake
  provenance -->|1| identity

  pricing -->|5| catalog
  pricing -->|1| identity
  pricing --> core

  outcomes -->|4| identity
  outcomes -->|1| intake

  catalog -->|3| intake
  catalog -->|2| identity
  catalog -->|2| storage

  intake -->|9| identity
  intake -->|5| storage
```

`identity` and `storage` depend on nothing. Most types are package-private: `offers` and `reporting` export nothing, `condition`, `pricing`, `provenance` and `outcomes` one type each (their published API). `identity` exports the most (14 public types), since every module needs the current user, org access and consents.

## What each module owns

| Module | Owns | Published API | Tables |
|---|---|---|---|
| `identity` | Orgs, members, invites, roles, terms acceptances, contact directory | `CurrentUser`, `OrgAccess`, `Consents`, `Directory` | `org`, `app_user`, `membership`, `invite`, `consent_record` |
| `storage` | Presigned URLs, blob reads | `BlobStore` (`S3BlobStore`) | — |
| `intake` | Collections, cards, photos, photo checks, card detection and crop | `CardItems` and its events | `collection`, `submission_batch`, `card_item`, `photo` |
| `catalog` | TCGdex catalog, identification, card reader queue | `Catalog` | `catalog_card`, `card_identification`, `card_reading` |
| `condition` | Provider predictions, centering, assessments (distributions), review queue, centering labels | `Assessments` | `prediction_request`, `provider_prediction`, `condition_assessment`, `centering_label` |
| `provenance` | Authenticity findings, custody | `Provenance` | `authenticity_verification`, `authenticity_verification_photo`, `custody_record` |
| `pricing` | Comps, per-grade price snapshots, σ and sales rate | `Prices` | `sold_comp`, `price_snapshot`, `price_stats`, `comp_ingest_run` |
| `outcomes` | Returned grades and their verification | `Outcomes` | `grade_outcome`, `outcome_verification` |
| `valuation` | Valuation runs; one card valued as of now for offers | `Valuations` | `valuation_run`, `card_valuation` |
| `reporting` | Lender and insurer PDFs (paused behind `pokemans.collateral.enabled`) | — | `report` |
| `offers` | Offers, listings, deals, labels, after-the-sale events, undo, track record | — | `card_offer`, `sale_listing`, `listing_event`, `shipping_label`, `ship_address`, `card_offer_void`, `label_refund` |

The ER diagram ([03-data-model.md](03-data-model.md)) has the exact tables.

## Async work

There is no job framework. Three modules poll with `@Scheduled`:

| Scheduler | What it does | Default |
|---|---|---|
| `catalog.RecognitionScheduler` | Works the `card_reading` queue (Claude card reader), and sweeps stuck rows | every 15 s; sweep 5 min |
| `condition.PredictionScheduler` | Works the provider prediction queue (Ximilar, CardGrade.io, centering), and sweeps | every 15 s; sweep 5 min |
| `pricing.PricingScheduler` | Ingests comps from every `PriceSource` and snapshots prices | 06:30 UTC, only with ingestion enabled |

Each queue is also kicked straight after commit by a `@TransactionalEventListener`, so the poll is the retry path. Offer and listing expiry isn't a job: status is computed from `expires_at` when read.

## Web app

```mermaid
flowchart LR
  subgraph web[web/ · React + TypeScript + Vite PWA]
    main[main.tsx<br/>Auth0Provider · router]
    pages[pages/*<br/>one file per page or panel]
    api[api.ts<br/>fetch wrapper + hand-written DTO types]
  end
  main --> pages --> api
  api -- "REST /api/* (Bearer JWT)" --> be[backend]
  pages -- presigned PUT/GET --> r2[(R2)]
  main -- login --> a0[[Auth0]]
```

Areas: seller (collections, card page with the offer panel, needs-you home), buyer (market, deals, ship-to address), admin (review queue, review card, prices, offers and deals, labels, shipping charges, track record, orgs, centering labels, valuation runs when collateral is on). Admin screens are hidden client-side by role; the backend enforces it on `/api/admin/**`.

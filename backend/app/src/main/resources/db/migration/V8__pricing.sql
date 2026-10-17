-- Pricing (M3): sold comps with provenance, per-grade price snapshots and per-card market stats.

-- One run of comp ingestion from one price source: what came back and what was kept.
create table comp_ingest_run (
    id          uuid primary key,
    source      text        not null,
    from_date   date        not null,
    to_date     date        not null,
    cards       integer     not null,
    fetched     integer     not null default 0,
    inserted    integer     not null default 0,
    duplicates  integer     not null default 0,
    rejected    integer     not null default 0,
    errors      jsonb,                   -- per-card failures and rejection reasons
    started_at  timestamptz not null,
    finished_at timestamptz
);

-- Append-only: one sold sale, as the source reported it. Never updated or deleted in place; a
-- correction is a new row. Asking prices are never comps: every row has a sold date.
-- Provenance (source, licence, retrieval time, the record as received) is kept on every row so
-- comps can be kept or purged per source licence, and every report value shows its licence basis.
create table sold_comp (
    id               uuid primary key,
    source           text          not null, -- price source, e.g. MANUAL or a vendor name
    external_sale_id text          not null, -- the source's id for the sale
    catalog_card_id  uuid          not null references catalog_card (id),
    grading_company  text check (grading_company in ('PSA', 'BGS', 'CGC', 'SGC', 'TAG', 'ACE')), -- null for raw
    grade            numeric(3, 1) check (grade >= 1 and grade <= 10), -- as sold; half grades kept, folded when pricing
    raw_condition    text check (raw_condition in ('NEAR_MINT', 'LIGHTLY_PLAYED', 'MODERATELY_PLAYED',
                                                   'HEAVILY_PLAYED', 'DAMAGED', 'ALTERED')),
    price            numeric(12, 2) not null check (price > 0),
    currency         text          not null,
    sold_on          date          not null,
    listing_type     text          not null check (listing_type in ('AUCTION', 'FIXED_PRICE', 'BEST_OFFER_ACCEPTED',
                                                                    'PRIVATE_SALE', 'UNKNOWN')),
    url              text,
    licence          text          not null, -- licence basis at retrieval, e.g. a plan name or 'manual entry'
    retrieved_at     timestamptz   not null,
    ingest_run_id    uuid references comp_ingest_run (id),
    raw              jsonb,                  -- the source's record, as received
    created_at       timestamptz   not null,
    unique (source, external_sale_id),
    check ((grading_company is null) = (grade is null)),
    check ((grade is null) = (raw_condition is not null))
);

create index sold_comp_card_idx on sold_comp (catalog_card_id, sold_on);

-- Append-only: the price of one grade of one card as of a date, and how it was set. The 11 rows
-- (U, 1-10) computed together share a snapshot_set_id; a valuation run references the set.
create table price_snapshot (
    id                uuid primary key,
    snapshot_set_id   uuid          not null,
    catalog_card_id   uuid          not null references catalog_card (id),
    grade             text          not null check (grade in ('U', '1', '2', '3', '4', '5', '6', '7', '8', '9', '10')),
    grading_company   text,                    -- whose graded comps priced grades 1-10; null for U
    as_of             date          not null,
    price             numeric(12, 2) not null, -- gross sale price, before grading costs (netted in the valuation run)
    currency          text          not null,
    method            text          not null check (method in ('OBSERVED', 'INTERPOLATED', 'STEP_DOWN', 'CARRIED_DOWN', 'FLOOR')),
    comp_ids          jsonb         not null,  -- sold_comp ids behind an observed price, after outlier trimming
    n                 integer       not null,  -- sales in the window, before trimming
    window_days       integer,                 -- 90, or 180 when extended; null when not observed
    basis_grades      jsonb         not null,  -- grades a derived price came from
    step_down_ratio   numeric(10, 6),          -- era ratio used, for STEP_DOWN
    confidence        text          not null check (confidence in ('A', 'B', 'C', 'D')),
    parameter_set     text          not null,
    trace             jsonb         not null,  -- explanation steps for this grade
    created_at        timestamptz   not null,
    unique (snapshot_set_id, grade)
);

create index price_snapshot_card_idx on price_snapshot (catalog_card_id, as_of, created_at);

-- Append-only: per-card market stats computed with a snapshot set.
create table price_stats (
    id               uuid primary key,
    snapshot_set_id  uuid          not null unique,
    catalog_card_id  uuid          not null references catalog_card (id),
    as_of            date          not null,
    sigma            numeric(12, 8),           -- monthly σ of 30-day price changes over 12 months; null when too few
    sigma_changes    integer       not null,   -- 30-day changes behind sigma
    sales_per_month  numeric(10, 2) not null,  -- all sold comps of the card over the last 90 days, per 30 days
    created_at       timestamptz   not null
);

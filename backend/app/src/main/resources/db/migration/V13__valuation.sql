-- Valuation runs and reports (M4). A run values one collection as of a date: it snapshots every
-- input (assessment versions, price snapshot sets, provenance states, the parameter set body and the
-- grading cost of the day) next to the results and the full trace, so a report can be reproduced
-- from the run alone. Everything here is append-only: a new valuation is a new run.

create table valuation_run (
    id                    uuid primary key,
    org_id                uuid           not null references org (id),
    collection_id         uuid           not null references collection (id),
    collection_name       text           not null, -- as it was named at run time, for the report
    as_of                 date           not null,
    parameter_set_version text           not null,
    parameter_set         jsonb          not null, -- the full body the run was valued with
    grading_company       text           not null, -- whose table values raw cards (PSA, Zach 2026-10-18)
    grading_cost          jsonb          not null, -- fee, shipping, insurance, time-to-cash rate
    inputs_sha256         text           not null, -- over the parameter set, grading cost and every card's inputs
    card_count            integer        not null,
    valued_count          integer        not null,
    eligible_count        integer        not null,
    eligible_flv          numeric(14, 2) not null,
    collateral_base       numeric(14, 2) not null,
    raw_share             numeric(9, 6)  not null,
    graded_share          numeric(9, 6)  not null,
    alpha_low             numeric(5, 4)  not null, -- suggested range only: the lender sets alpha
    alpha_high            numeric(5, 4)  not null,
    advance_low           numeric(14, 2) not null,
    advance_high          numeric(14, 2) not null,
    caps_applied          boolean        not null,
    total_rv              numeric(14, 2) not null, -- the insurer total: RV of every insurable card
    portfolio_trace       jsonb          not null,
    note                  text,
    created_by            uuid           not null references app_user (id),
    created_at            timestamptz    not null
);

create index valuation_run_collection_idx on valuation_run (collection_id, created_at);
create index valuation_run_org_idx on valuation_run (org_id);

-- One row per card item in the collection at run time, valued or not.
create table card_valuation (
    id                 uuid primary key,
    run_id             uuid           not null references valuation_run (id),
    org_id             uuid           not null references org (id),
    card_item_id       uuid           not null references card_item (id),
    position           integer        not null, -- order in the collection
    status             text           not null check (status in ('VALUED', 'UNVALUED')),
    unvalued_reason    text,
    catalog_card_id    uuid references catalog_card (id),
    assessment_id      uuid references condition_assessment (id),
    assessment_version integer,
    snapshot_set_id    uuid,
    graded             boolean        not null,
    inputs             jsonb          not null, -- everything the valuation used, enough to replay it
    fmv                numeric(14, 2),
    rv                 numeric(14, 2),
    qsv                numeric(14, 2),
    stressed_fmv       numeric(14, 2),
    flv                numeric(14, 2),
    confidence         text check (confidence in ('A', 'B', 'C', 'D')),
    eligible           boolean        not null,
    exclusion_reasons  jsonb          not null default '[]',
    contribution       numeric(14, 2) not null, -- to the collateral base, after caps and haircuts
    insured_rv         numeric(14, 2) not null, -- RV on the insurer report; 0 for doubted or unvalued cards
    insurer_note       text,
    flv_graded         numeric(14, 2), -- grade-first, raw cards only
    flv_raw_for_lift   numeric(14, 2), -- raw FLV at gross prices, so the lift counts grading cost once
    grading_cost       numeric(14, 2),
    lift               numeric(12, 4),
    grade_first_rank   integer,        -- 1-20 when on the recommended list
    trace              jsonb          not null default '[]',
    unique (run_id, card_item_id),
    check ((status = 'VALUED') = (fmv is not null)),
    check ((status = 'UNVALUED') = (unvalued_reason is not null))
);

create index card_valuation_run_idx on card_valuation (run_id, position);
create index card_valuation_card_idx on card_valuation (card_item_id);

-- An issued PDF. The stored object is written once and its hash kept here.
create table report (
    id               uuid primary key,
    run_id           uuid        not null references valuation_run (id),
    org_id           uuid        not null references org (id),
    collection_id    uuid        not null references collection (id),
    type             text        not null check (type in ('LENDER', 'INSURER')),
    lender_alpha     numeric(5, 4) check (lender_alpha between 0 and 1), -- only if the lender gave one
    template_version text        not null,
    storage_key      text        not null unique,
    sha256           text        not null,
    size_bytes       bigint      not null,
    issued_by        uuid        not null references app_user (id),
    issued_at        timestamptz not null,
    check (type = 'LENDER' or lender_alpha is null)
);

create index report_collection_idx on report (collection_id, issued_at);
create index report_run_idx on report (run_id);

-- refuse_update() is from V9. Deletes stay possible, for data-deletion requests.
create trigger valuation_run_append_only before update on valuation_run
    for each row execute function refuse_update();
create trigger card_valuation_append_only before update on card_valuation
    for each row execute function refuse_update();
create trigger report_append_only before update on report
    for each row execute function refuse_update();

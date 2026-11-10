-- Review A3 and B4 (2026-11-09 deep review).

-- One price snapshot run: every card priced as of a date, each card committed on its own. A run that didn't
-- finish (status RUNNING after a crash, or ABORTED) is resumed by the next run for the same date and parameter
-- set, which skips the cards already done.
create table price_snapshot_run (
    id            uuid primary key,
    as_of         date        not null,
    parameter_set text        not null,
    status        text        not null check (status in ('RUNNING', 'COMPLETE', 'ABORTED')),
    resumed_from  uuid references price_snapshot_run (id),
    cards         integer     not null default 0, -- cards in the run, those already done when resumed included
    priced        integer     not null default 0,
    skipped       integer     not null default 0,
    failed        integer     not null default 0,
    comps         integer     not null default 0, -- comps read while pricing, for the growth the review asked to watch
    excluded      integer     not null default 0, -- comps the PokeTrace day guards left out
    errors        jsonb,                          -- per-card failures, and what aborted the run
    started_at    timestamptz not null,
    finished_at   timestamptz
);

create index price_snapshot_run_as_of_idx on price_snapshot_run (as_of, parameter_set, started_at);

-- A card a run has finished with, committed with its price tables, so a resumed run knows to skip it.
create table price_snapshot_run_card (
    snapshot_run_id uuid        not null references price_snapshot_run (id),
    catalog_card_id uuid        not null references catalog_card (id),
    outcome         text        not null check (outcome in ('PRICED', 'SKIPPED')),
    created_at      timestamptz not null,
    primary key (snapshot_run_id, catalog_card_id)
);

-- Append-only: a stored comp a run left out of pricing, and why (store everything). The comp itself is kept.
create table comp_exclusion (
    id              uuid primary key,
    snapshot_run_id uuid        not null references price_snapshot_run (id),
    catalog_card_id uuid        not null references catalog_card (id),
    sold_comp_id    uuid        not null references sold_comp (id),
    rule            text        not null, -- INCONSISTENT, WIDE_DAY or HIGH_DAY
    reason          text        not null,
    parameter_set   text        not null,
    created_at      timestamptz not null,
    unique (snapshot_run_id, sold_comp_id)
);

create index comp_exclusion_comp_idx on comp_exclusion (sold_comp_id);

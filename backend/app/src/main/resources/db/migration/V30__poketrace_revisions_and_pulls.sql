-- PR #114 follow-ups: revised PokeTrace days and pull-gap detection.

-- A PokeTrace day whose average, low or high changed since we stored it is appended as a new sold_comp
-- (external sale id <day>#r<n>), never edited. Each ingest run counts them.
alter table comp_ingest_run add column revised integer not null default 0;

comment on column comp_exclusion.rule is
    'INCONSISTENT, WIDE_DAY or HIGH_DAY (day guards, 1.5.0), or SUPERSEDED (a newer revision of the PokeTrace day replaced it, 1.7.0)';

-- Append-only: one row per card each time a source was asked for its sales, so a mapped card whose pulls
-- stopped can be flagged. ok = the source answered and its sales were stored.
create table comp_pull (
    id              uuid primary key,
    ingest_run_id   uuid        not null references comp_ingest_run (id),
    source          text        not null,
    catalog_card_id uuid        not null references catalog_card (id),
    ok              boolean     not null,
    fetched         integer     not null,
    error           text,
    pulled_at       timestamptz not null
);

create index comp_pull_card_idx on comp_pull (source, catalog_card_id, pulled_at);
create index comp_pull_run_idx on comp_pull (ingest_run_id);

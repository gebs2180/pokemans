-- Raw-sale floors (parameter set 1.8.0, deep review B1 step 2, Zach 2026-11-11). A grade's net price is the higher of
-- its net graded price and the raw floor of the condition mapped to it (near mint 7 to 10, lightly played 5 and 6).
-- The floor is stored with the grade's snapshot row because the grade price is stored gross and netted per valuation
-- run: the run applies the floor after netting. Null for no floor, and on every row from sets before 1.8.0.
alter table price_snapshot
    add column raw_floor             numeric(12, 2),
    add column raw_floor_condition   text,
    add column raw_floor_comp_ids    jsonb,
    add column raw_floor_n           integer,
    add column raw_floor_window_days integer;

comment on column price_snapshot.raw_floor is
    'Raw-sale floor for this grade (no grading cost): the recency-weighted low quantile of the condition''s sale days; null for none';
comment on column price_snapshot.raw_floor_condition is 'Raw condition whose sales set the floor (NEAR_MINT, LIGHTLY_PLAYED, ...)';
comment on column price_snapshot.raw_floor_comp_ids is 'The raw sale days in the floor''s window';
comment on column price_snapshot.raw_floor_n is 'Raw sale days in the floor''s window';
comment on column price_snapshot.raw_floor_window_days is '90, or 180 when extended';

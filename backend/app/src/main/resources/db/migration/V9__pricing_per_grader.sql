-- Pricing decisions of 2026-10-17: each grading company's comps price that company's grades on
-- their own, there is no bulk floor, and the append-only pricing tables refuse updates.

-- No bulk floor: U with no damaged or altered raw sales is valued at zero (NO_SALES), and a card
-- with no graded sales is left unpriced instead of floored.
alter table price_snapshot drop constraint price_snapshot_method_check;
update price_snapshot set method = 'NO_SALES', price = 0 where method = 'FLOOR' and grade = 'U';
delete from price_snapshot where method = 'FLOOR';
alter table price_snapshot add constraint price_snapshot_method_check
    check (method in ('OBSERVED', 'INTERPOLATED', 'STEP_DOWN', 'CARRIED_DOWN', 'NO_SALES'));

-- Every row of a snapshot set carries the set's grading company, U included.
update price_snapshot u set grading_company = (
    select max(o.grading_company) from price_snapshot o where o.snapshot_set_id = u.snapshot_set_id)
where u.grading_company is null;
delete from price_snapshot where grading_company is null;
alter table price_snapshot alter column grading_company set not null;
alter table price_snapshot add constraint price_snapshot_grading_company_check
    check (grading_company in ('PSA', 'BGS', 'CGC', 'SGC', 'TAG', 'ACE'));

drop index price_snapshot_card_idx;
create index price_snapshot_card_idx on price_snapshot (catalog_card_id, grading_company, as_of, created_at);

comment on column price_stats.snapshot_set_id is
    'Stats are per card: every grading company''s set from one refresh gets the same sigma and sales rate.';

-- Append-only, enforced: rows can be added, and deleted (a vendor licence may require purging its
-- comps), but never changed. A correction is a new row.
create function refuse_update() returns trigger language plpgsql as $$
begin
    raise exception '% is append-only: add a new row instead of updating', tg_table_name;
end;
$$;

create trigger sold_comp_append_only before update on sold_comp
    for each row execute function refuse_update();
create trigger price_snapshot_append_only before update on price_snapshot
    for each row execute function refuse_update();
create trigger price_stats_append_only before update on price_stats
    for each row execute function refuse_update();

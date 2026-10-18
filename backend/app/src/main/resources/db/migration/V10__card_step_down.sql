-- Below the lowest sold grade, with no era step-down ratio yet, a card can step down with its own
-- price ladder (CARD_STEP_DOWN) when it has sales across a spread of grades (decision of 2026-10-17).
alter table price_snapshot drop constraint price_snapshot_method_check;
alter table price_snapshot add constraint price_snapshot_method_check
    check (method in ('OBSERVED', 'INTERPOLATED', 'STEP_DOWN', 'CARD_STEP_DOWN', 'CARRIED_DOWN', 'NO_SALES'));

comment on column price_snapshot.step_down_ratio is 'Ratio applied to the grade above: the era ratio for STEP_DOWN, the card''s own for CARD_STEP_DOWN.';

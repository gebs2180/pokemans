-- What happened to a card after a buyer took it: graded, resold raw, or kept (review C1, 2026-11-09).
-- The buyer's grade is the label the firm price is calibrated against, so it goes into grade_outcome like
-- any other returned grade, and the admin checks its cert the same way.

-- Buyers record grades too.
alter table grade_outcome drop constraint grade_outcome_source_check;
alter table grade_outcome add constraint grade_outcome_source_check
    check (source in ('CONTRIBUTOR', 'PARTNER', 'ADMIN', 'BUYER'));

-- A buyer corrects a grade by appending a new outcome, and a typo in the grade keeps the same cert. So the
-- same cert may now appear more than once, but only ever on one card: the application refuses a cert
-- already recorded on another card, and contributors still can't record one twice.
drop index grade_outcome_cert_idx;
create index grade_outcome_cert_idx on grade_outcome (grader, cert_number);

-- Append-only: a correction is a new row, and the latest row per listing is what happened.
create table deal_outcome (
    id               uuid primary key,
    listing_id       uuid          not null references sale_listing (id),
    offer_id         uuid          not null references card_offer (id),
    card_item_id     uuid          not null references card_item (id),
    buyer_org_id     uuid          not null references org (id),
    kind             text          not null check (kind in ('GRADED', 'RESOLD_RAW', 'KEPT')),
    grade_outcome_id uuid references grade_outcome (id),
    resale_price     numeric(12, 2) check (resale_price >= 0),
    note             text,
    created_by       uuid          not null references app_user (id),
    created_at       timestamptz   not null,
    constraint deal_outcome_graded_has_grade check ((kind = 'GRADED') = (grade_outcome_id is not null)),
    constraint deal_outcome_resold_has_price check ((kind = 'RESOLD_RAW') = (resale_price is not null))
);

create index deal_outcome_listing_idx on deal_outcome (listing_id, created_at);
create index deal_outcome_buyer_idx on deal_outcome (buyer_org_id);
create index deal_outcome_grade_idx on deal_outcome (grade_outcome_id);

create trigger deal_outcome_append_only before update on deal_outcome
    for each row execute function refuse_update();

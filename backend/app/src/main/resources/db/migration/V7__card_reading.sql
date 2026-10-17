-- Card recognition (M2): reading name and number off the front photo to suggest an identity.

-- Work queue and result in one row: one reading of one photo by one reader. Append-only once done.
create table card_reading (
    id                  uuid primary key,
    org_id              uuid        not null references org (id),
    card_item_id        uuid        not null references card_item (id),
    photo_id            uuid        not null references photo (id),
    reader              text        not null,
    status              text        not null check (status in ('PENDING', 'DONE', 'FAILED')),
    attempts            integer     not null default 0,
    next_attempt_at     timestamptz not null,
    last_error          text,
    name                text,
    number              text,
    set_total           integer,
    language            text,
    first_edition_stamp boolean,
    finish              text,
    copyright_year      integer,
    set_name            text,
    notes               text,
    model               text,
    raw                 jsonb,       -- the reader's response as received
    requested_by        uuid references app_user (id),
    created_at          timestamptz not null,
    finished_at         timestamptz
);

create index card_reading_due_idx on card_reading (status, next_attempt_at);
create index card_reading_card_idx on card_reading (card_item_id, created_at);

-- Suggestions carry how sure the match is and which reading they came from.
alter table card_identification
    add column confidence numeric(4, 3),
    add column reading_id uuid references card_reading (id);

-- Catalog identities first seen in a suggestion have no human author.
alter table catalog_card alter column created_by drop not null;

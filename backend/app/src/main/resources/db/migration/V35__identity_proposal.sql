-- The collector's own pick from the catalog when adding a card (card picker, 2026-11-13). A proposal,
-- never an identification: the admin still confirms the identity in card_identification, and nothing
-- reads a proposal for pricing, authenticity flags or findings.

create table card_identity_proposal (
    id               uuid primary key,
    org_id           uuid        not null references org (id),
    card_item_id     uuid        not null references card_item (id),
    provider         text        not null check (provider in ('TCGDEX')),
    external_id      text        not null,
    language         text        not null,
    -- As the catalog listed the card when it was picked, for the reviewer to see.
    name             text        not null,
    set_name         text,
    number           text        not null,
    set_release_date date,
    image_url        text,
    -- Null: the collector wasn't sure, and the catalog listed more than one. Left for review.
    edition          text check (edition in ('UNLIMITED', 'FIRST_EDITION', 'SHADOWLESS')),
    variant          text check (variant in ('NORMAL', 'HOLO', 'REVERSE_HOLO')),
    stamp            text,                 -- a stamp the catalog lists, or null for none (or unsure)
    stamp_unsure     boolean     not null, -- the catalog lists stamps and the collector wasn't sure
    proposed_by      uuid        not null references app_user (id),
    created_at       timestamptz not null
);

create index card_identity_proposal_card_idx on card_identity_proposal (card_item_id, created_at);
create index card_identity_proposal_org_idx on card_identity_proposal (org_id);
create index card_identity_proposal_user_idx on card_identity_proposal (proposed_by);

-- Append-only like card_identification: a changed pick is a new row. refuse_update() is from V9.
create trigger card_identity_proposal_append_only before update on card_identity_proposal
    for each row execute function refuse_update();

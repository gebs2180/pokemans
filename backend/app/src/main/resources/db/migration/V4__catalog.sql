-- Catalog (M2): canonical card identities and which identity each physical card was given.

-- One canonical identity: set, number, language, edition, print variant, stamp and promo status.
-- A provider card (e.g. TCGdex ex13-11) splits into several of these, one per edition/variant.
create table catalog_card (
    id                 uuid primary key,
    identity_key       text        not null unique, -- provider, card, language, edition, variant, stamp, promo
    provider           text        not null check (provider in ('TCGDEX', 'MANUAL')),
    external_id        text,                        -- provider card id; null for manual entries
    language           text        not null,        -- ISO 639-1: en, ja, ...
    set_id             text,
    set_name           text        not null,
    series_name        text,
    set_release_date   date,
    number             text        not null,
    name               text        not null,
    rarity             text,
    edition            text        not null check (edition in ('UNLIMITED', 'FIRST_EDITION', 'SHADOWLESS')),
    variant            text        not null check (variant in ('NORMAL', 'HOLO', 'REVERSE_HOLO')),
    stamp              text,
    promo              boolean     not null,
    authenticity_risk  text        not null check (authenticity_risk in ('NONE', 'VINTAGE_HOLO', 'JAPANESE_PROMO', 'RECENT_CHASE')),
    image_url          text,
    provider_payload   jsonb,                       -- what the provider returned, as received
    created_by         uuid        not null references app_user (id),
    created_at         timestamptz not null
);

-- Append-only: re-identifying a card adds a row. The latest confirmed row is current.
create table card_identification (
    id              uuid primary key,
    org_id          uuid        not null references org (id),
    card_item_id    uuid        not null references card_item (id),
    catalog_card_id uuid        not null references catalog_card (id),
    source          text        not null check (source in ('REVIEWER', 'PROVIDER')),
    status          text        not null check (status in ('SUGGESTED', 'CONFIRMED')),
    note            text,
    created_by      uuid references app_user (id), -- null for provider suggestions
    created_at      timestamptz not null
);

create index card_identification_card_idx on card_identification (card_item_id, created_at);

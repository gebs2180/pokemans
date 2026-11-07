-- Which PokeTrace card a catalog card is (docs/price-data-sources.md, PokeTrace). An admin confirms
-- each one: PokeTrace's search doesn't reliably split editions, so nothing is matched automatically.
-- Append-only: mapping again, or unmapping (poketrace_id null), adds a row; the newest row counts.
create table poketrace_card (
    id                uuid        primary key,
    catalog_card_id   uuid        not null references catalog_card (id),
    poketrace_id      varchar(64),
    -- What PokeTrace said the card is when it was mapped (name, set, number, variant), for the record.
    poketrace_card    jsonb,
    -- Why an admin mapped a card whose edition, variant or number didn't agree with ours.
    override_reason   varchar(1000),
    mapped_by         uuid        not null references app_user (id),
    created_at        timestamptz not null
);

create index poketrace_card_catalog_card_idx on poketrace_card (catalog_card_id, created_at desc);

create trigger poketrace_card_append_only before update on poketrace_card
    for each row execute function refuse_update();

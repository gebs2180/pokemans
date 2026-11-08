-- What PokeTrace's search found for a catalog card, searched automatically when a card is identified.
-- A suggestion never prices anything: only an admin's mapping in poketrace_card does. suggested_id is
-- set only when exactly one candidate agrees with our card on everything we check, so the admin can
-- approve it in one click; the rest are decided on the card page.
-- Append-only: searching again adds a row; the newest row counts.
create table poketrace_suggestion (
    id               uuid        primary key,
    catalog_card_id  uuid        not null references catalog_card (id),
    suggested_id     varchar(64),
    -- The top candidates after narrowing to our number and set: id, name, number, variant, set, disagreements.
    candidates       jsonb       not null,
    -- Why the search failed, if it did.
    error            varchar(1000),
    created_at       timestamptz not null
);

create index poketrace_suggestion_catalog_card_idx on poketrace_suggestion (catalog_card_id, created_at desc);

create trigger poketrace_suggestion_append_only before update on poketrace_suggestion
    for each row execute function refuse_update();

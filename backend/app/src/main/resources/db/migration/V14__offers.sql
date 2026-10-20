-- Instant offers (Zach, 2026-10-19). A member sees our computed offer on a raw card: a price for each
-- grade it could inspect at. Selling posts it to every buyer org, and the first buyer to accept takes
-- it. Shipping, inspection and payment happen between the two; the buyer records what they found.
-- Everything here is append-only: a listing's life is a series of events, never an update.

alter table org drop constraint org_type_check;
alter table org add constraint org_type_check check (type in ('PARTNER', 'COLLECTOR', 'CONTRIBUTOR', 'BUYER'));
alter table membership drop constraint membership_role_check;
alter table membership add constraint membership_role_check check (role in ('PARTNER', 'COLLECTOR', 'CONTRIBUTOR', 'BUYER'));
alter table invite drop constraint invite_role_check;
alter table invite add constraint invite_role_check check (role in ('PARTNER', 'COLLECTOR', 'CONTRIBUTOR', 'BUYER'));

-- Our offer on one card as of a moment, with every input and the trace, valid for a set time.
create table card_offer (
    id                        uuid primary key,
    org_id                    uuid           not null references org (id),
    card_item_id              uuid           not null references card_item (id),
    catalog_card_id           uuid           not null references catalog_card (id),
    assessment_id             uuid references condition_assessment (id), -- null for a graded card
    snapshot_set_id           uuid           not null,
    graded                    boolean        not null,
    inputs                    jsonb          not null, -- the card's valuation inputs, as a valuation run stores them
    parameter_set             text           not null,
    buyer_margin              numeric(5, 4)  not null,
    ladder                    jsonb          not null, -- [{grade, probability, price}], best grade first
    expected_offer            numeric(14, 2) not null, -- sum p x price, for display only
    fmv                       numeric(14, 2) not null,
    confidence                text           not null check (confidence in ('A', 'B', 'C')),
    subject_to_authentication boolean        not null, -- a risk category not yet verified: the buyer authenticates in hand
    trace                     jsonb          not null,
    created_by                uuid           not null references app_user (id),
    created_at                timestamptz    not null,
    expires_at                timestamptz    not null
);

create index card_offer_card_idx on card_offer (card_item_id, created_at);

-- An offer posted to buyers. Open until a buyer accepts it, the seller withdraws it, or it expires.
create table sale_listing (
    id           uuid primary key,
    offer_id     uuid        not null references card_offer (id),
    org_id       uuid        not null references org (id),
    card_item_id uuid        not null references card_item (id),
    listed_by    uuid        not null references app_user (id),
    created_at   timestamptz not null,
    expires_at   timestamptz not null
);

create index sale_listing_card_idx on sale_listing (card_item_id, created_at);
create index sale_listing_expires_idx on sale_listing (expires_at);

-- What happened to a listing. ACCEPTED and WITHDRAWN close it, and only one of them can ever happen,
-- so two buyers racing (or a buyer and the seller) can't both win. INSPECTED is the buyer's record
-- of the card in hand: kept as calibration data of its own (source BUYER_INSPECTION), apart from
-- graders' results, since a buyer has a reason to grade low.
create table listing_event (
    id              uuid primary key,
    listing_id      uuid          not null references sale_listing (id),
    type            text          not null check (type in ('ACCEPTED', 'WITHDRAWN', 'INSPECTED')),
    actor_org_id    uuid          not null references org (id),
    actor_user_id   uuid          not null references app_user (id),
    inspected_grade numeric(3, 1) check (inspected_grade between 0 and 10), -- PSA scale; 0 = ungradable
    authentic       boolean,
    price_paid      numeric(14, 2) check (price_paid >= 0),
    declined_reason text check (declined_reason in ('NOT_GENUINE', 'NOT_THE_LISTED_CARD', 'DOES_NOT_MATCH_PHOTOS')),
    note            text,
    created_at      timestamptz   not null,
    check (type = 'INSPECTED' or (inspected_grade is null and authentic is null and price_paid is null and declined_reason is null))
);

create unique index listing_event_closed_once on listing_event (listing_id) where type in ('ACCEPTED', 'WITHDRAWN');
create unique index listing_event_inspected_once on listing_event (listing_id) where type = 'INSPECTED';
create index listing_event_actor_idx on listing_event (actor_org_id, created_at);

-- refuse_update() is from V9. Deletes stay possible, for data-deletion requests.
create trigger card_offer_append_only before update on card_offer
    for each row execute function refuse_update();
create trigger sale_listing_append_only before update on sale_listing
    for each row execute function refuse_update();
create trigger listing_event_append_only before update on listing_event
    for each row execute function refuse_update();

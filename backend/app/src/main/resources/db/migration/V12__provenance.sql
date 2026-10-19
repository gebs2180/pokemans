-- Provenance (M2, before M4 eligibility): authenticity verifications and custody records per
-- physical card. A card in an authenticity-risk category carries zero collateral value until it is
-- verified, and a card whose custody isn't verified gets no advance (CLAUDE.md pipeline step 1).

-- An admin's finding on whether a card is genuine. The latest row is the card's status, but only
-- while the card still has the identity the finding was made against: re-identifying it as a
-- different catalog card starts again from that card's risk category, doubts included.
create table authenticity_verification (
    id              uuid primary key,
    org_id          uuid        not null references org (id),
    card_item_id    uuid        not null references card_item (id),
    catalog_card_id uuid        not null references catalog_card (id), -- the identity it was checked as
    status          text        not null check (status in ('VERIFIED_AUTHENTIC', 'SUSPECTED_COUNTERFEIT', 'COUNTERFEIT')),
    method          text        not null check (method in ('IN_HAND_INSPECTION', 'GRADER_SLAB', 'THIRD_PARTY_AUTHENTICATION',
                                                           'PHOTO_REVIEW')),
    note            text,
    verified_by     uuid        not null references app_user (id),
    -- Which methods can verify depends on the card's risk category, so the app checks it: photo
    -- review (of a passing close-up) can verify a recent chase card but not a vintage holo or a
    -- Japanese promo.
    created_at      timestamptz not null
);

create index authenticity_verification_card_idx on authenticity_verification (card_item_id, created_at);

-- The card's photos a finding was based on, any number. A photo review has to name them, and a
-- photo-review verification needs a passing close-up among them.
create table authenticity_verification_photo (
    verification_id uuid not null references authenticity_verification (id) on delete cascade,
    photo_id        uuid not null references photo (id),
    primary key (verification_id, photo_id)
);

create index authenticity_verification_photo_idx on authenticity_verification_photo (photo_id);

-- Who holds a card, where, and since when. The latest row is current custody; a move or a check is
-- a new row. Members of the card's org can record custody; only an admin can mark a row verified,
-- with how it was checked, and a check lapses after pokemans.provenance.custody-check-valid-for.
create table custody_record (
    id                  uuid primary key,
    org_id              uuid        not null references org (id),
    card_item_id        uuid        not null references card_item (id),
    holder_type         text        not null check (holder_type in ('OWNER', 'PARTNER', 'VAULT', 'GRADER', 'LENDER', 'OTHER')),
    holder_name         text        not null,
    location            text        not null,
    held_since          date        not null,
    verified            boolean     not null,
    verification_method text check (verification_method in ('IN_PERSON', 'VAULT_STATEMENT', 'GRADER_RECEIPT', 'VIDEO_CALL')),
    verified_by         uuid references app_user (id),
    note                text,
    recorded_by         uuid        not null references app_user (id),
    created_at          timestamptz not null,
    constraint custody_record_verified_check check (
        verified = (verification_method is not null) and verified = (verified_by is not null))
);

create index custody_record_card_idx on custody_record (card_item_id, created_at);

-- refuse_update() is from V9. Deletes stay possible, for data-deletion requests.
create trigger authenticity_verification_append_only before update on authenticity_verification
    for each row execute function refuse_update();
create trigger authenticity_verification_photo_append_only before update on authenticity_verification_photo
    for each row execute function refuse_update();
create trigger custody_record_append_only before update on custody_record
    for each row execute function refuse_update();

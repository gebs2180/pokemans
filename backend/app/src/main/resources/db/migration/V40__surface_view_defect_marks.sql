-- Surface view (2026-11-18): a full-resolution crop of each photo and a local-contrast ("detail") version of it, and
-- defects marked on it by a reviewer. Both crops are null for photos checked before they were kept, until the admin's
-- backfill makes them.
alter table photo add column surface_key text;
alter table photo add column surface_detail_key text;

-- A defect a reviewer marked on a photo's surface view, for the collector to see. Position is a share of the crop
-- (0 left/top, 1 right/bottom). Soft-deleted rather than removed: we store everything. Marks are shown only; they do not
-- feed the condition distribution or any price (a methodology decision not yet made).
create table defect_mark (
    id           uuid primary key,
    org_id       uuid             not null references org (id),
    card_item_id uuid             not null references card_item (id),
    photo_id     uuid             not null references photo (id),
    side         text             not null check (side in ('FRONT', 'BACK', 'DETAIL')),
    x            double precision not null check (x >= 0 and x <= 1),
    y            double precision not null check (y >= 0 and y <= 1),
    type         text             not null check (type in ('SCRATCH', 'PRINT_LINE', 'DENT_CREASE', 'WHITENING', 'STAIN', 'OTHER')),
    severity     text             not null check (severity in ('MINOR', 'MODERATE', 'MAJOR')),
    note         text check (char_length(note) <= 500),
    created_by   uuid             not null references app_user (id),
    created_at   timestamptz      not null,
    deleted_by   uuid references app_user (id),
    deleted_at   timestamptz,
    check ((deleted_by is null) = (deleted_at is null))
);

create index defect_mark_card_idx on defect_mark (card_item_id, created_at);
create index defect_mark_photo_idx on defect_mark (photo_id);

-- forget_cards as in V37, with the marks deleted before the photos they point at.
create or replace function forget_cards(cards uuid[]) returns table (deleted_from text, deleted_rows bigint)
    language plpgsql as $$
declare
    gone_listing uuid[] := array(select id from sale_listing where card_item_id = any (cards));
    gone_offer   uuid[] := array(select id from card_offer where card_item_id = any (cards));
    gone_photo   uuid[] := array(select id from photo where card_item_id = any (cards));
    n            bigint;
begin
    delete from deal_outcome where card_item_id = any (cards) or listing_id = any (gone_listing);
    get diagnostics n = row_count; deleted_from := 'deal_outcome'; deleted_rows := n; return next;
    delete from label_refund where listing_id = any (gone_listing);
    get diagnostics n = row_count; deleted_from := 'label_refund'; deleted_rows := n; return next;
    delete from shipping_label where listing_id = any (gone_listing);
    get diagnostics n = row_count; deleted_from := 'shipping_label'; deleted_rows := n; return next;
    delete from listing_event where listing_id = any (gone_listing);
    get diagnostics n = row_count; deleted_from := 'listing_event'; deleted_rows := n; return next;
    delete from sale_listing where id = any (gone_listing);
    get diagnostics n = row_count; deleted_from := 'sale_listing'; deleted_rows := n; return next;
    delete from card_offer_void where offer_id = any (gone_offer);
    get diagnostics n = row_count; deleted_from := 'card_offer_void'; deleted_rows := n; return next;
    delete from card_offer where id = any (gone_offer);
    get diagnostics n = row_count; deleted_from := 'card_offer'; deleted_rows := n; return next;
    delete from card_valuation where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'card_valuation'; deleted_rows := n; return next;
    delete from outcome_verification where outcome_id in (select id from grade_outcome where card_item_id = any (cards));
    get diagnostics n = row_count; deleted_from := 'outcome_verification'; deleted_rows := n; return next;
    delete from grade_outcome where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'grade_outcome'; deleted_rows := n; return next;
    delete from defect_mark where card_item_id = any (cards) or photo_id = any (gone_photo);
    get diagnostics n = row_count; deleted_from := 'defect_mark'; deleted_rows := n; return next;
    delete from centering_label where card_item_id = any (cards) or photo_id = any (gone_photo);
    get diagnostics n = row_count; deleted_from := 'centering_label'; deleted_rows := n; return next;
    delete from authenticity_verification_photo
    where photo_id = any (gone_photo)
       or verification_id in (select id from authenticity_verification where card_item_id = any (cards));
    get diagnostics n = row_count; deleted_from := 'authenticity_verification_photo'; deleted_rows := n; return next;
    delete from authenticity_verification where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'authenticity_verification'; deleted_rows := n; return next;
    delete from custody_record where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'custody_record'; deleted_rows := n; return next;
    delete from condition_assessment where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'condition_assessment'; deleted_rows := n; return next;
    delete from provider_prediction where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'provider_prediction'; deleted_rows := n; return next;
    delete from prediction_request where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'prediction_request'; deleted_rows := n; return next;
    delete from card_identity_proposal where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'card_identity_proposal'; deleted_rows := n; return next;
    delete from card_identification where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'card_identification'; deleted_rows := n; return next;
    delete from card_reading where card_item_id = any (cards) or photo_id = any (gone_photo);
    get diagnostics n = row_count; deleted_from := 'card_reading'; deleted_rows := n; return next;
    delete from photo where id = any (gone_photo);
    get diagnostics n = row_count; deleted_from := 'photo'; deleted_rows := n; return next;
    delete from card_item where id = any (cards);
    get diagnostics n = row_count; deleted_from := 'card_item'; deleted_rows := n; return next;
end;
$$;

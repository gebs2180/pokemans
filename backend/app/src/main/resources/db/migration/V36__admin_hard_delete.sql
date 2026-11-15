-- Admin hard delete of test and junk cards and orgs (2026-11-15). "Store everything" still holds for real data; this is
-- a deliberate, narrow exception: admin only, refused on anything with live money or trades, and audited.

-- Everything recorded about these cards, children first, and the cards themselves. One path for both deletions:
-- deploy/forget-org.sql (a data-deletion request) and the admin's hard delete (AdminDeletionService). A card with a
-- deal loses the deal too (listing, events, label, refund requests, outcome): forget-org.sql never passes a card a buyer
-- took, and the admin delete refuses one whose deal is still going. Returns the rows removed per table.
--
-- A new table that points at card_item, photo, card_offer or sale_listing must be added here (with create or replace
-- in a new migration); ForgetOrgScriptTest and AdminDeletionTest fail until it is.
create function forget_cards(cards uuid[]) returns table (deleted_from text, deleted_rows bigint)
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
    delete from card_identification where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'card_identification'; deleted_rows := n; return next;
    delete from card_identity_proposal where card_item_id = any (cards);
    get diagnostics n = row_count; deleted_from := 'card_identity_proposal'; deleted_rows := n; return next;
    delete from card_reading where card_item_id = any (cards) or photo_id = any (gone_photo);
    get diagnostics n = row_count; deleted_from := 'card_reading'; deleted_rows := n; return next;
    delete from photo where id = any (gone_photo);
    get diagnostics n = row_count; deleted_from := 'photo'; deleted_rows := n; return next;
    delete from card_item where id = any (cards);
    get diagnostics n = row_count; deleted_from := 'card_item'; deleted_rows := n; return next;
end;
$$;

-- One row per admin hard delete, kept after what it deleted is gone: no foreign keys, and names copied in.
-- removed: rows removed per table. storage_prefixes: the photo-bucket folders and report keys to delete; the objects
-- go after the rows commit, and objects_deleted / storage_error say how that went, so a failure can be finished by hand.
create table admin_deletion (
    id               uuid primary key,
    kind             text        not null check (kind in ('CARD', 'ORG')),
    target_id        uuid        not null,
    target_name      text        not null,
    org_id           uuid        not null,
    org_name         text        not null,
    deleted_by       uuid        not null,
    removed          jsonb       not null,
    storage_prefixes jsonb       not null,
    objects_deleted  integer,
    storage_error    text,
    storage_done_at  timestamptz,
    created_at       timestamptz not null
);

create index admin_deletion_created_idx on admin_deletion (created_at desc);

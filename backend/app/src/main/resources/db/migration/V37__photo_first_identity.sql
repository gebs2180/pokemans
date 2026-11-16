-- Photo-first identification (2026-11-15). A collector adds a card with no name, takes front and back, and the reader
-- suggests which card it is; the collector confirms a suggestion, picks one by search, or describes it. The admin still
-- confirms every identity, and nothing is priced from a proposal.

-- A card is added before anyone knows its name. It is named from the collector's proposal (or the admin's
-- identification, if that comes first); until then the app shows a placeholder. A lot is still described up front.
alter table card_item alter column label drop not null;
alter table card_item add constraint card_item_lot_label check (not is_lot or label is not null);

-- Where a proposal came from: the reader's suggestion confirmed (SUGGESTION), a card picked by search (SEARCH), or the
-- collector's own words when neither found it (DESCRIBED). Existing rows were all picked by search.
alter table card_identity_proposal add column source text not null default 'SEARCH'
    check (source in ('SUGGESTION', 'SEARCH', 'DESCRIBED'));
alter table card_identity_proposal alter column source drop default;
-- The reading the collector was shown, if any, and the suggestion they confirmed.
alter table card_identity_proposal add column reading_id uuid references card_reading (id);
alter table card_identity_proposal add column suggestion_id uuid references card_identification (id);
alter table card_identity_proposal add column description text;

alter table card_identity_proposal alter column provider drop not null;
alter table card_identity_proposal alter column external_id drop not null;
alter table card_identity_proposal alter column language drop not null;
alter table card_identity_proposal alter column name drop not null;
alter table card_identity_proposal alter column number drop not null;
alter table card_identity_proposal alter column stamp_unsure drop not null;

alter table card_identity_proposal add constraint card_identity_proposal_shape check (
    case when source = 'DESCRIBED'
        then description is not null and provider is null and external_id is null and language is null and name is null
            and number is null and stamp_unsure is null
        else description is null and provider is not null and external_id is not null and language is not null
            and name is not null and number is not null and stamp_unsure is not null
    end);
alter table card_identity_proposal add constraint card_identity_proposal_suggestion check (
    (source = 'SUGGESTION') = (suggestion_id is not null) and (suggestion_id is null or reading_id is not null));

create index card_identity_proposal_suggestion_idx on card_identity_proposal (suggestion_id);
create index card_identity_proposal_reading_idx on card_identity_proposal (reading_id);

-- forget_cards as in V36, with the proposals deleted before the identifications they can now point at.
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

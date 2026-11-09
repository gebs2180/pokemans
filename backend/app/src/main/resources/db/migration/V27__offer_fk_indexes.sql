-- Indexes on the offers tables' foreign keys that had none (2026-11 architecture review #28, deep review A5).
-- Postgres doesn't index a referencing column by itself: without these, a lookup by listing or offer, and every
-- check when a referenced row is touched, scans the whole table. The tables are small in the beta, so a plain
-- (locking) create index is fine here.

-- The two the review named. listing_event's partial unique indexes cover only some event types.
create index listing_event_listing_idx on listing_event (listing_id, created_at);
create index sale_listing_offer_idx on sale_listing (offer_id);

-- The rest of the offers tables' foreign keys with no index leading on them.
create index card_offer_org_idx on card_offer (org_id, created_at);
create index card_offer_catalog_card_idx on card_offer (catalog_card_id);
create index card_offer_created_by_idx on card_offer (created_by);
create index card_offer_void_voided_by_idx on card_offer_void (voided_by);
create index sale_listing_org_idx on sale_listing (org_id, created_at);
create index sale_listing_listed_by_idx on sale_listing (listed_by);
create index listing_event_actor_user_idx on listing_event (actor_user_id);
create index ship_address_recorded_by_idx on ship_address (recorded_by);
create index shipping_label_from_address_idx on shipping_label (from_address_id);
create index shipping_label_to_address_idx on shipping_label (to_address_id);
create index label_refund_listing_idx on label_refund (listing_id);
create index label_refund_requested_by_idx on label_refund (requested_by);

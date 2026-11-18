-- Decision sheet follow-ups (Zach, 2026-11-18; docs/decisions-to-confirm.md items 6 and 12).

-- The lowball flag compares the firm price with what the offer's own formula would pay for a card certain to be worth
-- the lowest raw near-mint sale, not with the sale itself, which every offer is under by its haircut.
alter table card_offer add column raw_nm_net_low numeric(12, 2);
comment on column card_offer.raw_nm_net_low is
    'raw_nm_low x (1 - f)(1 - d)(1 - m) at the offer''s own tier and margin; null when no raw sale, and before 2026-11-18';
comment on column card_offer.below_raw_sales is
    'The firm price was below raw_nm_net_low when quoted (before 2026-11-18: below raw_nm_low). Shown to the admin and the seller, never refused';

-- A re-price void keeps both prices, so the direction of each move can be counted.
alter table card_offer_void add column repriced_was numeric(14, 2);
alter table card_offer_void add column repriced_now numeric(14, 2);
comment on column card_offer_void.repriced_was is 'For a re-price void: the offer''s price';
comment on column card_offer_void.repriced_now is 'For a re-price void: its re-run on that day''s prices; null when it couldn''t be priced';

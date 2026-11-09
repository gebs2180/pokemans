-- Raw sanity band (deep review 2026-11-09, B1 step 1): when an offer is quoted, the card's raw near-mint sales
-- of the last 90 days are kept beside it, and an offer below the lowest of them is flagged. A flag only:
-- the offer stands and its formula is unchanged. Offers made before this have no raw check (nulls, not flagged).
alter table card_offer add column raw_nm_low numeric(12, 2);
alter table card_offer add column raw_nm_sales integer;
alter table card_offer add column raw_nm_since date;
alter table card_offer add column below_raw_sales boolean not null default false;

comment on column card_offer.raw_nm_low is 'Lowest raw near-mint comp of the card in the 90 days before the quote (a PokeTrace comp is a day''s average); null when none';
comment on column card_offer.raw_nm_sales is 'Raw near-mint comps in that window (sale days for PokeTrace); null for offers from before the check';
comment on column card_offer.raw_nm_since is 'First day of that window';
comment on column card_offer.below_raw_sales is 'The firm price was below raw_nm_low when quoted: shown to the admin and the seller, never refused';

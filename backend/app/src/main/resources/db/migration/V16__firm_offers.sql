-- Firm offers (Zach, 2026-10-19). An instant offer is one firm price, the expected value of the
-- per-grade ladder; the buyer accepts it or doesn't, and no grade is settled afterwards. On accept a
-- prepaid, insured label is bought on our shipping account and billed to the buyer monthly. On
-- arrival the buyer records that the card came as shown and what they paid, or returns it for one of
-- the reasons the buyer terms allow.

alter table card_offer rename column expected_offer to price;
comment on column card_offer.price is 'The firm price: sum of p x the buyer''s price at each grade (the ladder)';

-- Where an org ships from (sellers) or to (buyers). The latest row is current; a move is a new row.
create table ship_address (
    id          uuid primary key,
    org_id      uuid        not null references org (id),
    name        text        not null,
    company     text,
    street1     text        not null,
    street2     text,
    city        text        not null,
    state       text        not null,
    zip         text        not null,
    country     text        not null check (country = 'US'), -- US only in v1
    phone       text,
    recorded_by uuid        not null references app_user (id),
    created_at  timestamptz not null
);

create index ship_address_org_idx on ship_address (org_id, created_at);

-- The prepaid label for a taken card, bought on our account and billed to the buyer each month.
create table shipping_label (
    id                   uuid primary key,
    listing_id           uuid           not null unique references sale_listing (id),
    buyer_org_id         uuid           not null references org (id),
    from_address_id      uuid           not null references ship_address (id),
    to_address_id        uuid           not null references ship_address (id),
    provider             text           not null,
    provider_shipment_id text,
    carrier              text           not null,
    service              text           not null,
    tracking_code        text,
    label_url            text           not null,
    postage              numeric(10, 2) not null check (postage >= 0),
    insured_value        numeric(14, 2) not null check (insured_value >= 0), -- the firm price
    insurance_cost       numeric(10, 2) not null check (insurance_cost >= 0),
    currency             text           not null check (currency = 'USD'),
    created_at           timestamptz    not null
);

create index shipping_label_buyer_idx on shipping_label (buyer_org_id, created_at);

-- RECEIVED: the card arrived genuine and as shown, and the buyer paid (price_paid). RETURNED: it didn't,
-- for one of the allowed reasons, and goes back to the seller. INSPECTED stays for listings taken
-- under the per-grade offers that came before.
alter table listing_event drop constraint listing_event_type_check;
alter table listing_event add constraint listing_event_type_check
    check (type in ('ACCEPTED', 'WITHDRAWN', 'INSPECTED', 'RECEIVED', 'RETURNED'));
alter table listing_event drop constraint listing_event_check;
alter table listing_event add constraint listing_event_fields_check check (
    (type in ('ACCEPTED', 'WITHDRAWN') and inspected_grade is null and authentic is null and price_paid is null
        and declined_reason is null)
    or type = 'INSPECTED'
    or (type = 'RECEIVED' and price_paid is not null and declined_reason is null and inspected_grade is null)
    or (type = 'RETURNED' and declined_reason is not null and price_paid is null and inspected_grade is null));
drop index listing_event_inspected_once;
create unique index listing_event_outcome_once on listing_event (listing_id) where type in ('INSPECTED', 'RECEIVED', 'RETURNED');

-- refuse_update() is from V9. Deletes stay possible, for data-deletion requests.
create trigger ship_address_append_only before update on ship_address
    for each row execute function refuse_update();
create trigger shipping_label_append_only before update on shipping_label
    for each row execute function refuse_update();

-- The track record, for firm offers. Buyers no longer judge a grade, so there is no grade calibration
-- from offers; grade accuracy will come from graders' results for sold cards.
drop view v_offer_grade_calibration;
drop view v_offer_outcomes;

-- One row per listing (an offer posted to buyers). A card posted three times is three rows.
--
--   status   OPEN, ACCEPTED, WITHDRAWN or EXPIRED as of now
--   outcome  RECEIVED (arrived as shown, paid), RETURNED (with the reason), INSPECTED (taken under the
--            earlier per-grade offers), or null while the card is on its way
create view v_offer_outcomes as
select l.id                                                        as listing_id,
       o.id                                                        as offer_id,
       l.card_item_id,
       l.org_id                                                    as seller_org_id,
       o.catalog_card_id,
       o.inputs -> 'identity' ->> 'name'                           as card_name,
       o.inputs -> 'identity' ->> 'setName'                        as set_name,
       o.inputs -> 'identity' ->> 'number'                         as card_number,
       o.inputs -> 'identity' ->> 'language'                       as language,
       o.graded,
       o.confidence,
       o.subject_to_authentication,
       o.parameter_set,
       (o.inputs -> 'prices' ->> 'salesPerMonth')::numeric         as sales_per_month,
       o.created_at                                                as offered_at,
       o.price,
       o.fmv,
       l.created_at                                                as listed_at,
       l.expires_at                                                as listing_expires_at,
       case
           when acc.id is not null then 'ACCEPTED'
           when wd.id is not null then 'WITHDRAWN'
           when l.expires_at > now() then 'OPEN'
           else 'EXPIRED'
       end                                                         as status,
       acc.id is not null                                          as accepted,
       acc.created_at                                              as accepted_at,
       acc.actor_org_id                                            as buyer_org_id,
       extract(epoch from acc.created_at - l.created_at) / 3600.0  as hours_to_accept,
       wd.id is not null                                           as withdrawn,
       acc.id is null and wd.id is null and l.expires_at <= now()  as expired,
       lbl.created_at                                              as label_at,
       lbl.carrier,
       lbl.service,
       lbl.postage,
       lbl.insurance_cost,
       done.type                                                   as outcome,
       done.created_at                                             as outcome_at,
       done.price_paid,
       done.declined_reason                                        as returned_reason,
       extract(epoch from done.created_at - lbl.created_at) / 3600.0 as hours_label_to_outcome
from sale_listing l
         join card_offer o on o.id = l.offer_id
         left join listing_event acc on acc.listing_id = l.id and acc.type = 'ACCEPTED'
         left join listing_event wd on wd.listing_id = l.id and wd.type = 'WITHDRAWN'
         left join listing_event done on done.listing_id = l.id and done.type in ('INSPECTED', 'RECEIVED', 'RETURNED')
         left join shipping_label lbl on lbl.listing_id = l.id;

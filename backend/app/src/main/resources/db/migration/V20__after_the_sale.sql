-- What happens after a card sells (UX review, 2026-11-01). Rawmark never touches the money: these rows
-- only record what the two sides say, appended, never edited.

-- How a seller gets paid: shown to the buyer once they accept. It sits with the seller's address because
-- the seller gives both on one form. The latest row is current. Buyers leave it empty, and rows from
-- before this migration have none: such a seller adds it before posting.
alter table ship_address add column payment_method text check (payment_method in ('PAYPAL', 'VENMO', 'ZELLE'));
alter table ship_address add column payment_handle text check (length(btrim(payment_handle)) > 0);
alter table ship_address add constraint ship_address_payment_check check ((payment_method is null) = (payment_handle is null));

-- RECEIVED now means only that the card arrived as shown: PAID comes later, with the amount, since the
-- buyer has 7 days. A RECEIVED row with price_paid is from before the split, when the two were one fact.
--
--   SHIPPED            the seller says the card is with the carrier, so the label is used
--   PAID               the buyer says they paid, and how much
--   PAYMENT_CONFIRMED  the seller says they got paid
--   DISPUTED           the seller says they haven't been paid, or disagrees with a return
--   CARD_BACK          the seller says a returned card is back with them, so it can get a new offer
--   RESOLVED           the admin closed what was raised, saying how; no actor org, the note holds it
alter table listing_event drop constraint listing_event_type_check;
alter table listing_event add constraint listing_event_type_check
    check (type in ('ACCEPTED', 'WITHDRAWN', 'INSPECTED', 'RECEIVED', 'RETURNED', 'VOIDED', 'CANCELLED',
                    'SHIPPED', 'PAID', 'PAYMENT_CONFIRMED', 'DISPUTED', 'CARD_BACK', 'RESOLVED'));
alter table listing_event drop constraint listing_event_fields_check;
alter table listing_event add constraint listing_event_fields_check check (
    (type in ('ACCEPTED', 'WITHDRAWN', 'SHIPPED', 'PAYMENT_CONFIRMED', 'DISPUTED', 'CARD_BACK') and inspected_grade is null
        and authentic is null and price_paid is null and declined_reason is null)
    or type = 'INSPECTED'
    or (type = 'RECEIVED' and declined_reason is null and inspected_grade is null)
    or (type = 'PAID' and price_paid is not null and authentic is null and declined_reason is null and inspected_grade is null)
    or (type = 'RETURNED' and declined_reason is not null and price_paid is null and inspected_grade is null)
    or (type in ('VOIDED', 'CANCELLED', 'RESOLVED') and note is not null and length(btrim(note)) > 0 and inspected_grade is null
        and authentic is null and price_paid is null and declined_reason is null));
alter table listing_event drop constraint listing_event_actor_check;
alter table listing_event add constraint listing_event_actor_check
    check (type in ('VOIDED', 'CANCELLED', 'RESOLVED') or actor_org_id is not null);

-- Each of these happens once to a deal. DISPUTED and RESOLVED can repeat: a closed matter can be raised again.
create unique index listing_event_after_sale_once on listing_event (listing_id, type)
    where type in ('SHIPPED', 'PAID', 'PAYMENT_CONFIRMED', 'CARD_BACK');

-- The track record: the amount paid now comes from PAID, or from RECEIVED for deals before the split.
-- New columns go last.
create or replace view v_offer_outcomes as
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
           when cx.id is not null then 'CANCELLED'
           when acc.id is not null then 'ACCEPTED'
           when wd.id is not null then 'WITHDRAWN'
           when vd.id is not null then 'VOIDED'
           when l.expires_at > now() then 'OPEN'
           else 'EXPIRED'
       end                                                         as status,
       acc.id is not null                                          as accepted,
       acc.created_at                                              as accepted_at,
       acc.actor_org_id                                            as buyer_org_id,
       extract(epoch from acc.created_at - l.created_at) / 3600.0  as hours_to_accept,
       wd.id is not null                                           as withdrawn,
       acc.id is null and wd.id is null and vd.id is null and l.expires_at <= now() as expired,
       lbl.created_at                                              as label_at,
       lbl.carrier,
       lbl.service,
       lbl.postage,
       lbl.insurance_cost,
       done.type                                                   as outcome,
       done.created_at                                             as outcome_at,
       coalesce(paid.price_paid, done.price_paid)                  as price_paid,
       done.declined_reason                                        as returned_reason,
       extract(epoch from done.created_at - lbl.created_at) / 3600.0 as hours_label_to_outcome,
       vd.id is not null                                           as voided,
       cx.id is not null                                           as cancelled,
       coalesce(cx.note, vd.note)                                  as undo_reason,
       exists (select 1 from label_refund r where r.label_id = lbl.id) as label_refund_requested,
       shipped.created_at                                          as shipped_at,
       coalesce(paid.created_at, case when done.price_paid is not null then done.created_at end) as paid_at,
       confirmed.created_at                                        as payment_confirmed_at,
       exists (select 1 from listing_event d where d.listing_id = l.id and d.type = 'DISPUTED') as disputed
from sale_listing l
         join card_offer o on o.id = l.offer_id
         left join listing_event acc on acc.listing_id = l.id and acc.type = 'ACCEPTED'
         left join listing_event wd on wd.listing_id = l.id and wd.type = 'WITHDRAWN'
         left join listing_event vd on vd.listing_id = l.id and vd.type = 'VOIDED'
         left join listing_event cx on cx.listing_id = l.id and cx.type = 'CANCELLED'
         left join listing_event done on done.listing_id = l.id and done.type in ('INSPECTED', 'RECEIVED', 'RETURNED')
         left join listing_event paid on paid.listing_id = l.id and paid.type = 'PAID'
         left join listing_event shipped on shipped.listing_id = l.id and shipped.type = 'SHIPPED'
         left join listing_event confirmed on confirmed.listing_id = l.id and confirmed.type = 'PAYMENT_CONFIRMED'
         left join shipping_label lbl on lbl.listing_id = l.id;

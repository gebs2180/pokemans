-- The instant-offer track record, one row per listing: the admin page (TrackRecordService) and the
-- analysis notebooks both read it, so they can't disagree. Repeatable (review finding 42): Flyway runs
-- it again whenever this file changes, after the versioned migrations, so the view lives in one place.
-- V15 to V20 built it; this is V20's definition, plus BACK (architecture review #26). New columns go
-- last (create or replace can only add). status is ListingStateMachine.status in SQL: change them
-- together (ListingStatusParityTest checks). A card that came back is BACK, not ACCEPTED; it was still
-- taken, so count both as accepted.
-- A versioned migration that changes a column this reads must drop the view first; this file then
-- recreates it.
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
           when exists (select 1 from listing_event bk where bk.listing_id = l.id and bk.type = 'CARD_BACK') then 'BACK'
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

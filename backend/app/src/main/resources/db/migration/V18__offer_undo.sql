-- Admin undo for instant offers (Zach, 2026-10-21), by appending, never editing: voiding an offer,
-- cancelling a deal and asking for a label's refund are each a new row saying who, when and why.
-- Before the closed beta, so a mistake (a wrong identification, a bad price) can be taken back.

-- A voided offer can't be posted again. Voiding one that is on the market takes its listing down too
-- (a VOIDED listing event); one a buyer has taken is undone by cancelling the deal instead.
create table card_offer_void (
    id         uuid primary key,
    offer_id   uuid        not null unique references card_offer (id),
    reason     text        not null check (length(btrim(reason)) > 0),
    voided_by  uuid        not null references app_user (id),
    created_at timestamptz not null
);

-- VOIDED: we took an open listing down (its offer was voided). CANCELLED: we undid a deal a buyer had
-- taken, before any arrival or return was recorded; the card is free for a new offer. Both are the
-- admin's, not an org's, so actor_org_id is null for them, and note holds the reason.
alter table listing_event alter column actor_org_id drop not null;
alter table listing_event drop constraint listing_event_type_check;
alter table listing_event add constraint listing_event_type_check
    check (type in ('ACCEPTED', 'WITHDRAWN', 'INSPECTED', 'RECEIVED', 'RETURNED', 'VOIDED', 'CANCELLED'));
alter table listing_event drop constraint listing_event_fields_check;
alter table listing_event add constraint listing_event_fields_check check (
    (type in ('ACCEPTED', 'WITHDRAWN') and inspected_grade is null and authentic is null and price_paid is null
        and declined_reason is null)
    or type = 'INSPECTED'
    or (type = 'RECEIVED' and price_paid is not null and declined_reason is null and inspected_grade is null)
    or (type = 'RETURNED' and declined_reason is not null and price_paid is null and inspected_grade is null)
    or (type in ('VOIDED', 'CANCELLED') and note is not null and length(btrim(note)) > 0 and inspected_grade is null and authentic is null
        and price_paid is null and declined_reason is null));
alter table listing_event add constraint listing_event_actor_check
    check (type in ('VOIDED', 'CANCELLED') or actor_org_id is not null);

-- A listing closes once: taken, withdrawn or voided, whichever comes first. A deal ends once: arrived,
-- returned or cancelled, so a cancel and a buyer's arrival can't both land.
drop index listing_event_closed_once;
create unique index listing_event_closed_once on listing_event (listing_id) where type in ('ACCEPTED', 'WITHDRAWN', 'VOIDED');
drop index listing_event_outcome_once;
create unique index listing_event_outcome_once on listing_event (listing_id)
    where type in ('INSPECTED', 'RECEIVED', 'RETURNED', 'CANCELLED');

-- A label marked to be refunded, for a cancelled deal. The refund is asked of the provider that sold
-- it; provider_status is its answer (EasyPost: submitted, refunded, rejected, not_applicable), or null
-- when it couldn't be asked (provider_error says why) and the admin can try again.
create table label_refund (
    id              uuid primary key,
    label_id        uuid        not null references shipping_label (id),
    listing_id      uuid        not null references sale_listing (id),
    reason          text        not null check (length(btrim(reason)) > 0),
    requested_by    uuid        not null references app_user (id),
    provider        text,
    provider_status text,
    provider_error  text,
    created_at      timestamptz not null,
    check (provider_status is null or provider_error is null)
);

create index label_refund_label_idx on label_refund (label_id, created_at);
create unique index label_refund_answered_once on label_refund (label_id) where provider_status is not null;

-- refuse_update() is from V9. Deletes stay possible, for data-deletion requests.
create trigger card_offer_void_append_only before update on card_offer_void
    for each row execute function refuse_update();
create trigger label_refund_append_only before update on label_refund
    for each row execute function refuse_update();

-- The track record: a voided listing and a cancelled deal get statuses of their own, and are left out
-- of the acceptance and delivery numbers (neither a buyer's no nor a delivery). New columns go last.
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
       done.price_paid,
       done.declined_reason                                        as returned_reason,
       extract(epoch from done.created_at - lbl.created_at) / 3600.0 as hours_label_to_outcome,
       vd.id is not null                                           as voided,
       cx.id is not null                                           as cancelled,
       coalesce(cx.note, vd.note)                                  as undo_reason,
       exists (select 1 from label_refund r where r.label_id = lbl.id) as label_refund_requested
from sale_listing l
         join card_offer o on o.id = l.offer_id
         left join listing_event acc on acc.listing_id = l.id and acc.type = 'ACCEPTED'
         left join listing_event wd on wd.listing_id = l.id and wd.type = 'WITHDRAWN'
         left join listing_event vd on vd.listing_id = l.id and vd.type = 'VOIDED'
         left join listing_event cx on cx.listing_id = l.id and cx.type = 'CANCELLED'
         left join listing_event done on done.listing_id = l.id and done.type in ('INSPECTED', 'RECEIVED', 'RETURNED')
         left join shipping_label lbl on lbl.listing_id = l.id;

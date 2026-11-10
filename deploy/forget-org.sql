-- Forget an org on request: the procedure in docs/data-deletion.md (review 2026-11, #44 / D12).
--
-- Deletes the org's cards and everything recorded about them, its people's access and its addresses, except what a
-- deal another org relies on still needs: a card a buyer took (it has a shipping label) keeps its deal rows and photos,
-- and the addresses on that label. The rest of the org's people and names are cut or renamed.
--
-- Dry run by default: it prints what it would do, then rolls back. Run from deploy/ on the server:
--   docker compose -f compose.prod.yml --env-file .env exec -T postgres \
--     psql -U pokemans -d pokemans -v org_id=<uuid> -f - < forget-org.sql
-- and once the output looks right, again with -v apply=true to commit.
--
-- The storage keys it prints are deleted from the photo bucket by hand (docs/data-deletion.md); the database can't
-- reach the bucket. Tested by ForgetOrgScriptTest, which also fails when a new table points at card_item, org,
-- photo or app_user and this script doesn't handle it.

\set ON_ERROR_STOP on
\if :{?org_id}
\else
  \echo 'Set the org: -v org_id=<uuid>'
  \quit
\endif
\if :{?apply}
\else
  \set apply false
\endif
\pset footer off

begin;

create temp table forget_target on commit drop as select id from org where id = :'org_id'::uuid;
select count(*) = 1 as org_found from forget_target \gset
\if :org_found
\else
  \echo 'No org with that id. Nothing done.'
  rollback;
  \quit
\endif

-- What happened, step by step, printed at the end.
create temp table forget_log (seq serial, step text, rows bigint) on commit drop;

-- A card a buyer took is part of that buyer's deal: kept, with its photos (the "matches its photos" check).
create temp table kept_card on commit drop as
    select distinct l.card_item_id as id
    from sale_listing l join shipping_label s on s.listing_id = l.id
    where l.org_id = (select id from forget_target);
create temp table gone_card on commit drop as
    select id from card_item
    where org_id = (select id from forget_target) and id not in (select id from kept_card);
create temp table gone_listing on commit drop as
    select id from sale_listing where card_item_id in (select id from gone_card);
create temp table gone_offer on commit drop as
    select id from card_offer where card_item_id in (select id from gone_card);

-- The people: everyone who was ever a member. Those still active in another org are left alone.
create temp table forget_user on commit drop as
    select distinct m.user_id as id, a.subject, a.email
    from membership m join app_user a on a.id = m.user_id
    where m.org_id = (select id from forget_target)
      and not exists (select 1 from membership o
                      where o.user_id = m.user_id and o.org_id <> m.org_id and o.ended_at is null);

-- Bucket keys to delete afterwards: each gone card's folder (original, crop, view and exact copies), and the reports.
create temp table forget_key on commit drop as
    select 'orgs/' || c.org_id || '/cards/' || c.id || '/' as key, 'photo folder (prefix)' as what
    from card_item c where c.id in (select id from gone_card)
    union all
    select storage_key, 'report' from report where org_id = (select id from forget_target);

-- Children first. Each step logs how many rows it removed.
with d as (delete from deal_outcome where card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'deal_outcome', count(*) from d;
with d as (delete from listing_event where listing_id in (select id from gone_listing) returning 1)
insert into forget_log (step, rows) select 'listing_event', count(*) from d;
with d as (delete from sale_listing where id in (select id from gone_listing) returning 1)
insert into forget_log (step, rows) select 'sale_listing', count(*) from d;
with d as (delete from card_offer_void where offer_id in (select id from gone_offer) returning 1)
insert into forget_log (step, rows) select 'card_offer_void', count(*) from d;

-- Valuation runs and reports cover the whole collection, kept cards included: all of the org's go.
with d as (delete from card_valuation
           where org_id = (select id from forget_target) or card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'card_valuation', count(*) from d;
with d as (delete from report where org_id = (select id from forget_target) returning 1)
insert into forget_log (step, rows) select 'report', count(*) from d;
with d as (delete from valuation_run where org_id = (select id from forget_target) returning 1)
insert into forget_log (step, rows) select 'valuation_run', count(*) from d;

with d as (delete from card_offer where id in (select id from gone_offer) returning 1)
insert into forget_log (step, rows) select 'card_offer', count(*) from d;
with d as (delete from outcome_verification
           where outcome_id in (select id from grade_outcome where card_item_id in (select id from gone_card)) returning 1)
insert into forget_log (step, rows) select 'outcome_verification', count(*) from d;
with d as (delete from grade_outcome where card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'grade_outcome', count(*) from d;
with d as (delete from centering_label where card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'centering_label', count(*) from d;
with d as (delete from authenticity_verification_photo
           where verification_id in (select id from authenticity_verification where card_item_id in (select id from gone_card))
           returning 1)
insert into forget_log (step, rows) select 'authenticity_verification_photo', count(*) from d;
with d as (delete from authenticity_verification where card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'authenticity_verification', count(*) from d;
-- Custody says where the collector keeps their cards: gone for kept cards too, since a taken card has left them.
with d as (delete from custody_record where org_id = (select id from forget_target) returning 1)
insert into forget_log (step, rows) select 'custody_record', count(*) from d;
with d as (delete from condition_assessment where card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'condition_assessment', count(*) from d;
with d as (delete from provider_prediction where card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'provider_prediction', count(*) from d;
with d as (delete from prediction_request where card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'prediction_request', count(*) from d;
with d as (delete from card_identification where card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'card_identification', count(*) from d;
with d as (delete from card_reading where card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'card_reading', count(*) from d;
with d as (delete from photo where card_item_id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'photo', count(*) from d;
with d as (delete from card_item where id in (select id from gone_card) returning 1)
insert into forget_log (step, rows) select 'card_item', count(*) from d;

-- Folders: delete the empty ones, rename the ones a kept card is still in (their names are the collector's words).
with d as (delete from collection c where c.org_id = (select id from forget_target)
           and not exists (select 1 from card_item i where i.collection_id = c.id) returning 1)
insert into forget_log (step, rows) select 'collection', count(*) from d;
with d as (delete from submission_batch b where b.org_id = (select id from forget_target)
           and not exists (select 1 from card_item i where i.batch_id = b.id) returning 1)
insert into forget_log (step, rows) select 'submission_batch', count(*) from d;
with u as (update collection set name = 'Deleted on request' where org_id = (select id from forget_target) returning 1)
insert into forget_log (step, rows) select 'collection renamed', count(*) from u;
with u as (update submission_batch set name = 'Deleted on request' where org_id = (select id from forget_target) returning 1)
insert into forget_log (step, rows) select 'submission_batch renamed', count(*) from u;

-- Addresses: a shipping label's addresses stay (the label, its insurance and any claim need them); the rest go.
with d as (delete from ship_address a where a.org_id = (select id from forget_target)
           and not exists (select 1 from shipping_label s where s.from_address_id = a.id or s.to_address_id = a.id) returning 1)
insert into forget_log (step, rows) select 'ship_address', count(*) from d;

-- Access: nobody can sign in to the org any more.
with d as (delete from invite where org_id = (select id from forget_target) returning 1)
insert into forget_log (step, rows) select 'invite', count(*) from d;
with d as (delete from membership where org_id = (select id from forget_target) returning 1)
insert into forget_log (step, rows) select 'membership', count(*) from d;

-- Consent stays only while the org stays: it shows the kept deals were made under the terms.
with d as (delete from consent_record c where c.org_id = (select id from forget_target)
           and not exists (select 1 from card_item i where i.org_id = c.org_id)
           and not exists (select 1 from ship_address a where a.org_id = c.org_id)
           and not exists (select 1 from shipping_label s where s.buyer_org_id = c.org_id) returning 1)
insert into forget_log (step, rows) select 'consent_record', count(*) from d;

-- The org and its people: deleted where nothing kept points at them, otherwise left as a nameless id.
do $$
declare
    target uuid := (select id from forget_target);
    u record;
begin
    begin
        delete from org where id = target;
        insert into forget_log (step, rows) values ('org', 1);
    exception when foreign_key_violation then
        update org set name = 'Deleted org ' || left(id::text, 8) where id = target;
        insert into forget_log (step, rows) values ('org renamed (kept deals point at it)', 1);
    end;
    for u in select id from forget_user loop
        begin
            delete from consent_record where user_id = u.id;
            delete from membership where user_id = u.id;
            delete from app_user where id = u.id;
            insert into forget_log (step, rows) values ('app_user', 1);
        exception when foreign_key_violation then
            -- Rolled back to before this user's deletes: their ended memberships and consents elsewhere stay.
            update app_user set subject = 'deleted|' || id, email = null where id = u.id;
            insert into forget_log (step, rows) values ('app_user redacted (kept rows point at them)', 1);
        end;
    end loop;
end
$$;

\echo
\echo 'Rows removed or changed:'
select step, rows from forget_log where rows > 0 order by seq;
\echo 'Cards kept because a buyer took them (their photos stay in the bucket):'
select id as kept_card from kept_card order by id;
\echo 'Delete these from the photo bucket (docs/data-deletion.md):'
select what, key from forget_key order by what, key;
\echo 'Logins to delete in Auth0 (their app_user rows were deleted or redacted here):'
select id as app_user_id, subject, email from forget_user order by id;

\if :apply
  commit;
  \echo 'Committed.'
\else
  rollback;
  \echo 'Dry run: rolled back. Run again with -v apply=true to commit.'
\endif

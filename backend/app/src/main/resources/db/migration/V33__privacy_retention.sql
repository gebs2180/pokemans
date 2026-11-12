-- Privacy review (Zach, 2026-11-12): location comes out of photo originals, and emails and addresses are kept only as
-- long as they're needed (docs/data-retention.md). The periods are settings (pokemans.retention.*), defaults to confirm.

-- Photo originals are rewritten once, at upload or by the one-off backfill, without their GPS and device metadata;
-- pixels, orientation and capture time stay. sha256 and size_bytes stay the upload's (duplicate checks and issued
-- reports use them); these describe the object stored after the rewrite, null when nothing needed removing.
alter table photo add column location_checked_at timestamptz;
alter table photo add column stored_sha256 text;
alter table photo add column stored_size_bytes bigint;
comment on column photo.location_checked_at is 'When the stored original was checked for location and device metadata (rewritten without it if it had any)';
create index photo_location_unchecked_idx on photo (created_at) where location_checked_at is null and status <> 'AWAITING_UPLOAD';

-- An email's content and address are cleared once it is done with and old enough; the row, template, status and
-- timestamps stay, as the record that it was sent (or why not).
alter table email_message alter column subject drop not null;
alter table email_message alter column html_body drop not null;
alter table email_message alter column text_body drop not null;
alter table email_message add column content_cleared_at timestamptz;
create index email_message_content_kept on email_message (created_at)
    where content_cleared_at is null and status in ('SENT', 'FAILED', 'SKIPPED', 'CANCELLED');

-- An address a label used is anonymised once every deal on it is long closed: name, street, city, phone and payment
-- handle go; state, zip and country stay (where a card shipped between, for claims and the track record). The row
-- stays because shipping_label points at it. An address replaced before any label used it is deleted instead.
alter table ship_address alter column name drop not null;
alter table ship_address alter column street1 drop not null;
alter table ship_address alter column city drop not null;
alter table ship_address add column anonymised_at timestamptz;

-- Still append-only, except for that one update: everything personal to null, anonymised_at set once, the rest as it was.
drop trigger ship_address_append_only on ship_address;
create trigger ship_address_append_only before update on ship_address
    for each row
    when (not (old.anonymised_at is null and new.anonymised_at is not null
        and new.name is null and new.company is null and new.street1 is null and new.street2 is null
        and new.city is null and new.phone is null and new.payment_method is null and new.payment_handle is null
        and (new.id, new.org_id, new.state, new.zip, new.country, new.recorded_by, new.created_at)
            is not distinct from (old.id, old.org_id, old.state, old.zip, old.country, old.recorded_by, old.created_at)))
    execute function refuse_update();

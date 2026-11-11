-- Notification emails (deep review C2, 2026-11-10). Every email is a row here before it goes out:
-- queued in the transaction that recorded what it's about, sent after commit by a worker, retried with
-- backoff. dedupe_key makes queueing the same email twice a no-op, and it goes to Resend as the
-- idempotency key, so a retry after a lost answer is never a second email. Not append-only: a row moves
-- PENDING -> SENDING -> SENT (or FAILED, SKIPPED, CANCELLED), and is never deleted.
create table email_message (
    id                uuid primary key,
    dedupe_key        text        not null unique,
    template          text        not null,
    recipient_user_id uuid        references app_user (id),
    recipient_email   text,
    org_id            uuid,
    listing_id        uuid,
    subject           text        not null,
    html_body         text        not null,
    text_body         text        not null,
    status            text        not null check (status in ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED', 'CANCELLED')),
    -- Why it was skipped, cancelled or failed for good, e.g. "no email on file".
    status_reason     text,
    attempts          integer     not null default 0,
    next_attempt_at   timestamptz not null,
    -- The provider's answer to the last failed try: status and message, never our request headers.
    last_error        text,
    provider_id       text,
    created_at        timestamptz not null,
    sent_at           timestamptz,
    updated_at        timestamptz not null
);

create index email_message_due on email_message (next_attempt_at) where status = 'PENDING';
create index email_message_stuck on email_message (updated_at) where status = 'SENDING';
create index email_message_listing on email_message (listing_id, template) where listing_id is not null;
create index email_message_recipient on email_message (recipient_user_id, template, org_id, created_at);

-- Who wants which optional email. No row = the defaults: the daily digest on. Transactional emails
-- about a user's own deals are always sent.
create table email_preference (
    user_id    uuid primary key references app_user (id),
    digest     boolean     not null,
    updated_at timestamptz not null
);

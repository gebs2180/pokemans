-- One login buys and sells (Zach, 2026-11-22; methodology doc Step 8, rev 81). A collector turns buying on from their
-- own login: they accept the buyer terms and add a ship-to address, which creates a buyer org they own, and buying
-- waits on the admin's approval. Buyer orgs the admin created and invited people to have no application and need none.

-- Each time someone asks to buy. A declined person may ask again, on the same buyer org: a new row, the next attempt.
-- The unique attempt per user is what stops a double click opening two buyer orgs.
create table buyer_application (
    id         uuid primary key,
    org_id     uuid        not null references org (id),
    user_id    uuid        not null references app_user (id),
    attempt    int         not null check (attempt >= 1),
    created_at timestamptz not null,
    unique (user_id, attempt)
);
create index buyer_application_org_idx on buyer_application (org_id, created_at);

-- The admin's answer: at most one per application, never changed. A decline carries the reason the person sees.
create table buyer_decision (
    application_id uuid primary key references buyer_application (id),
    approved       boolean     not null,
    decided_by     uuid        not null references app_user (id),
    decided_at     timestamptz not null,
    note           text,
    check (approved or (note is not null and length(btrim(note)) > 0))
);

-- refuse_update() is from V9. Deletes stay possible, for data-deletion requests (deploy/forget-org.sql).
create trigger buyer_application_append_only before update on buyer_application
    for each row execute function refuse_update();
create trigger buyer_decision_append_only before update on buyer_decision
    for each row execute function refuse_update();

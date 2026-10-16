-- Identity: orgs, users, memberships and invites.
-- Auth0 proves who a user is (and ADMIN, with MFA); PARTNER and COLLECTOR access comes from membership here.

create table org (
    id         uuid primary key,
    name       text        not null,
    type       text        not null check (type in ('PARTNER', 'COLLECTOR')),
    created_at timestamptz not null
);

create table app_user (
    id         uuid primary key,
    subject    text        not null unique, -- OIDC "sub", e.g. google-oauth2|1054...
    email      text,
    created_at timestamptz not null
);

create table membership (
    id         uuid primary key,
    org_id     uuid        not null references org (id),
    user_id    uuid        not null references app_user (id),
    role       text        not null check (role in ('PARTNER', 'COLLECTOR')),
    created_at timestamptz not null,
    unique (org_id, user_id)
);

create index membership_user_idx on membership (user_id);

create table invite (
    id          uuid primary key,
    org_id      uuid        not null references org (id),
    role        text        not null check (role in ('PARTNER', 'COLLECTOR')),
    token_hash  text        not null unique, -- SHA-256 of the token; the token itself is shown once
    email       text,
    created_by  uuid        not null references app_user (id),
    created_at  timestamptz not null,
    expires_at  timestamptz not null,
    accepted_by uuid references app_user (id),
    accepted_at timestamptz
);

create index invite_org_idx on invite (org_id);

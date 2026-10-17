-- Contributors (M2): collectors who share raw photos of cards they had graded, with the grade, for
-- training and calibration. Consent first; the grade counts once its cert is checked.

alter table org drop constraint org_type_check;
alter table org add constraint org_type_check check (type in ('PARTNER', 'COLLECTOR', 'CONTRIBUTOR'));
alter table membership drop constraint membership_role_check;
alter table membership add constraint membership_role_check check (role in ('PARTNER', 'COLLECTOR', 'CONTRIBUTOR'));
alter table invite drop constraint invite_role_check;
alter table invite add constraint invite_role_check check (role in ('PARTNER', 'COLLECTOR', 'CONTRIBUTOR'));

-- Who agreed to which version of the data-use terms, for which org. Append-only.
create table consent_record (
    id            uuid primary key,
    org_id        uuid        not null references org (id),
    user_id       uuid        not null references app_user (id),
    terms_version text        not null,
    terms_sha256  text        not null, -- hash of the exact text shown
    accepted_at   timestamptz not null
);

create index consent_record_org_user_idx on consent_record (org_id, user_id);

-- A grading company's result for a card. Immutable; checking it adds rows to outcome_verification.
create table grade_outcome (
    id           uuid primary key,
    org_id       uuid         not null references org (id),
    card_item_id uuid         not null references card_item (id),
    source       text         not null check (source in ('CONTRIBUTOR', 'PARTNER', 'ADMIN')),
    grader       text         not null check (grader in ('PSA', 'BGS', 'CGC', 'SGC', 'TAG')),
    cert_number  text         not null,
    grade        numeric(3, 1) check (grade between 1 and 10), -- null for "Authentic"/"Altered" results
    grade_label  text,                                          -- as printed, e.g. "GEM MT 10", "AUTHENTIC"
    graded_on    date,
    created_by   uuid         not null references app_user (id),
    created_at   timestamptz  not null
);

create index grade_outcome_card_idx on grade_outcome (card_item_id);
create unique index grade_outcome_cert_idx on grade_outcome (grader, cert_number);

-- Checking a cert against the grader's public lookup. The latest row is the outcome's status.
create table outcome_verification (
    id          uuid primary key,
    outcome_id  uuid        not null references grade_outcome (id),
    status      text        not null check (status in ('VERIFIED', 'MISMATCH')),
    note        text,
    verified_by uuid        not null references app_user (id),
    created_at  timestamptz not null
);

create index outcome_verification_outcome_idx on outcome_verification (outcome_id, created_at);

-- Condition (M2): pre-grading provider predictions and our grade distributions.

-- Work queue for provider calls. One row per (card, provider) request; retried with backoff.
create table prediction_request (
    id                  uuid primary key,
    org_id              uuid        not null references org (id),
    card_item_id        uuid        not null references card_item (id),
    provider            text        not null,
    status              text        not null check (status in ('PENDING', 'SUBMITTED', 'DONE', 'FAILED')),
    external_request_id text,        -- the provider's job id once submitted
    photo_ids           jsonb       not null, -- photos sent: [front, back]
    attempts            integer     not null default 0,
    next_attempt_at     timestamptz not null,
    last_error          text,
    requested_by        uuid references app_user (id), -- null when queued automatically
    created_at          timestamptz not null,
    finished_at         timestamptz
);

create index prediction_request_due_idx on prediction_request (status, next_attempt_at);
create index prediction_request_card_idx on prediction_request (card_item_id);

-- Append-only: every provider answer, right or wrong, as received plus our normalized reading.
create table provider_prediction (
    id                  uuid primary key,
    org_id              uuid             not null references org (id),
    card_item_id        uuid             not null references card_item (id),
    request_id          uuid             not null references prediction_request (id),
    provider            text             not null,
    model_version       text,
    external_request_id text,
    photo_ids           jsonb            not null,
    succeeded           boolean          not null,
    overall_grade       double precision, -- provider scale, 1–10, half grades kept
    psa_grade           double precision, -- the provider's PSA-specific prediction, when it gives one
    centering           double precision,
    corners             double precision,
    edges               double precision,
    surface             double precision,
    details             jsonb,            -- normalized extras: centering ratios, damage tag, limiting factor
    error               text,
    raw                 jsonb,            -- the provider's response, as received
    latency_ms          bigint,
    created_at          timestamptz      not null
);

create index provider_prediction_card_idx on provider_prediction (card_item_id, created_at);

-- Versioned: every change is a new version; exactly one is current per card.
create table condition_assessment (
    id            uuid primary key,
    org_id        uuid        not null references org (id),
    card_item_id  uuid        not null references card_item (id),
    version       integer     not null,
    distribution  jsonb       not null, -- {"U": p, "1": p, ..., "10": p}, sums to 1
    confidence    text        not null check (confidence in ('A', 'B', 'C', 'D')),
    author_type   text        not null check (author_type in ('SYSTEM', 'REVIEWER')),
    author_id     uuid references app_user (id),
    reason        text,
    adjustments   jsonb,                -- per-outcome change from the previous version
    basis         jsonb       not null, -- predictions and parameters it was built from
    is_current    boolean     not null,
    created_at    timestamptz not null,
    unique (card_item_id, version)
);

create unique index condition_assessment_current_idx on condition_assessment (card_item_id) where is_current;

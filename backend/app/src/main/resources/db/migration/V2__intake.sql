-- Intake: partner submission batches, collector collections, card items and photos.
-- Every org-owned row carries org_id; queries are scoped by it (row-level security to follow).

create table submission_batch (
    id         uuid primary key,
    org_id     uuid        not null references org (id),
    name       text        not null,
    created_by uuid        not null references app_user (id),
    created_at timestamptz not null
);

create index submission_batch_org_idx on submission_batch (org_id);

create table collection (
    id         uuid primary key,
    org_id     uuid        not null references org (id),
    name       text        not null,
    created_by uuid        not null references app_user (id),
    created_at timestamptz not null
);

create index collection_org_idx on collection (org_id);

create table card_item (
    id            uuid primary key,
    org_id        uuid        not null references org (id),
    batch_id      uuid references submission_batch (id),
    collection_id uuid references collection (id),
    label         text        not null,
    is_lot        boolean     not null,
    quantity      integer     not null check (quantity > 0),
    created_by    uuid        not null references app_user (id),
    created_at    timestamptz not null,
    check (num_nonnulls(batch_id, collection_id) = 1)
);

create index card_item_batch_idx on card_item (batch_id);
create index card_item_collection_idx on card_item (collection_id);

-- The stored object is written once (If-None-Match: *) and never replaced. A retake is a new photo row.
create table photo (
    id             uuid primary key,
    org_id         uuid        not null references org (id),
    card_item_id   uuid        not null references card_item (id),
    side           text        not null check (side in ('FRONT', 'BACK', 'DETAIL')),
    status         text        not null check (status in ('AWAITING_UPLOAD', 'PASSED', 'RETAKE')),
    storage_key    text        not null unique,
    content_type   text        not null,
    declared_size  bigint      not null,
    size_bytes     bigint,
    sha256         text,
    width          integer,
    height         integer,
    blur_score     double precision,
    glare_fraction double precision,
    retake_reasons jsonb       not null default '[]',
    captured_at    timestamptz, -- EXIF DateTimeOriginal, read as UTC (EXIF carries no zone)
    created_by     uuid        not null references app_user (id),
    created_at     timestamptz not null,
    uploaded_at    timestamptz,
    checked_at     timestamptz
);

create index photo_card_idx on photo (card_item_id);
create index photo_org_sha256_idx on photo (org_id, sha256);

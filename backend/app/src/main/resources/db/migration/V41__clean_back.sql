-- Clean back (2026-11-18): the surface view's "Clean back" mode shows a card back with the standard printed back design
-- subtracted, so only physical marks are left. A viewing aid only: nothing here feeds the condition distribution, any
-- price, eligibility or an authenticity flag (that would be a methodology decision not yet made).

-- The reference back: the per-pixel median of several aligned BACK surface crops of our own English cards, never a
-- downloaded scan. Each rebuild is a new version; older versions and their images are kept (we store everything).
create table back_reference (
    version        integer primary key check (version > 0),
    storage_key    text        not null,
    -- Photos offered as candidates, those used in the median, and why each other one was left out ({photoId: reason}).
    -- Plain ids, not foreign keys: forgetting a card must not be blocked by a reference built partly from it.
    candidate_ids  jsonb       not null,
    used_photo_ids jsonb       not null,
    rejected       jsonb       not null,
    seeded         boolean     not null,
    created_by     uuid references app_user (id),
    created_at     timestamptz not null
);

-- Per BACK photo: the clean image (null when the photo didn't align well enough: a Japanese back, another design, blur),
-- the reference version it was checked against, why it was refused, and the alignment and mismatch scores, kept for
-- later authenticity work. A photo is checked again when a newer reference is built.
alter table photo add column surface_clean_key text;
alter table photo add column clean_back_reference_version integer references back_reference (version);
alter table photo add column clean_back_matches integer;
alter table photo add column clean_back_inliers integer;
alter table photo add column clean_back_inlier_ratio double precision;
alter table photo add column clean_back_corner_shift double precision;
alter table photo add column clean_back_correlation double precision;
alter table photo add column clean_back_residual double precision;
alter table photo add column clean_back_failure text;
alter table photo add column clean_back_checked_at timestamptz;

create index photo_clean_back_pending_idx on photo (created_at)
    where side = 'BACK' and status = 'PASSED' and surface_key is not null;

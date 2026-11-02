-- A person's reading of a card's printed frame: the truth the centering measurer is scored against.
-- Append-only: labelling a card again adds a row and the newest counts, so two passes over the same
-- cards can be compared. Borders are physical, so a row applies to every photo of that face of the
-- card; photo_id is only the photo it was drawn on. A null width means "can't see it".
create table centering_label (
    id           uuid primary key,
    org_id       uuid             not null references org (id),
    card_item_id uuid             not null references card_item (id),
    face         text             not null check (face in ('FRONT', 'BACK')),
    photo_id     uuid             not null references photo (id),
    left_mm      double precision check (left_mm >= 0 and left_mm <= 15),
    right_mm     double precision check (right_mm >= 0 and right_mm <= 15),
    top_mm       double precision check (top_mm >= 0 and top_mm <= 15),
    bottom_mm    double precision check (bottom_mm >= 0 and bottom_mm <= 15),
    hard_card    boolean          not null, -- gold, foil, full art: scored apart from plain borders
    labelled_by  uuid             not null references app_user (id),
    created_at   timestamptz      not null
);

create index centering_label_card_idx on centering_label (card_item_id, face, created_at);

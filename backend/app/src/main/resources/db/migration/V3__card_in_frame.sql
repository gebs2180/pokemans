-- Whole-card-in-frame check (M2): where the card is in each front/back photo and how it measures.
-- Null on photos checked before card detection existed, on lots and on detail close-ups.
alter table photo
    add column card_corners        jsonb,            -- [{x, y}] TL, TR, BR, BL in display-orientation pixels
    add column card_coverage       double precision, -- card area / frame area
    add column card_aspect         double precision, -- measured width / height (a card is ~0.716)
    add column card_blur_score     double precision, -- Laplacian variance on the 1000 px card crop
    add column card_glare_fraction double precision,
    add column crop_key            text;             -- perspective-corrected card crop (JPEG), same bucket

-- The card crop with a little background past each edge, so people can see the card's edges and corners.
-- Null for photos checked before it was kept, until the admin's backfill makes one from the stored corners.
ALTER TABLE photo ADD COLUMN wide_crop_key text;

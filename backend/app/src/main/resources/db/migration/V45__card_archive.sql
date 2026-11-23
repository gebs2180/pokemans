-- A collector can archive a card that has no offer or listing: it leaves their list, and its photos and
-- predictions stay for calibration (methodology Step 8, "Removing a card"; Zach, 2026-11-23).
alter table card_item add column archived_at timestamptz;
alter table card_item add column archived_by uuid;

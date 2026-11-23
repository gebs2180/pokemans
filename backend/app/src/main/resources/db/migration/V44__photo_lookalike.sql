-- Duplicate photos (walkthrough 2026-11-23). The exact hash only catches a byte-for-byte copy; a photo saved again,
-- resized or sent through a messaging app gets past it. dhash is a 256-bit difference hash of the whole frame (64 hex
-- digits), kept for every photo checked from now on; photos checked before have none and are never compared.
-- lookalike_card_id is another card in the same org whose front photo hashes within a few bits of this one: a warning
-- shown to the seller and the reviewer, never a retake, since two copies of one card shot the same way look alike.
alter table photo add column dhash varchar(64);
-- No foreign key: deleting the other card for good must not wait on a warning about it.
alter table photo add column lookalike_card_id uuid;

create index photo_org_front_dhash_idx on photo (org_id) where side = 'FRONT' and dhash is not null;

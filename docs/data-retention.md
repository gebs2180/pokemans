# Data retention

How long we keep personal data, and what takes it out. Written 2026-11-12 after Zach's privacy review, which decided
three things: backups are encrypted to a public key ([hosting.md](hosting.md#backup-encryption-key)), photo originals
lose their location, and emails and addresses are kept only as long as they're needed. Columns are not encrypted one by
one. A deletion request is a separate procedure: [data-deletion.md](data-deletion.md).

**The periods are defaults to confirm.** Each is a setting under `pokemans.retention` in `application.yml`.

| What | Kept | Then | Setting |
|---|---|---|---|
| An email's address, subject and bodies (and the provider's last error, which can quote the address) | 90 days after it was queued, once it was sent, failed for good, skipped or cancelled | Cleared. The row stays with its template, status, reason and timestamps, as the record that it went out or why not. | `email-content-days: 90` |
| An address a shipping label used, with its payment handle | Until every deal on a label using it closed more than 12 months ago | Anonymised: name, company, street, city, phone and payment handle go; state, zip and country stay (where a card shipped between, for claims and the track record). The row stays because the labels point at it. | `label-address-days: 365` |
| An address replaced before any label used it, with its payment handle | 30 days after a newer one replaced it | Deleted. | `replaced-address-days: 30` |
| An org's newest address | While the org exists | Never touched by the job: it's the one the next label uses. | – |
| Location and device metadata in photo originals | Not kept | Taken out at upload (GPS, camera maker and model, software, comments). Pixels, orientation and the capture time (`DateTimeOriginal`, its offset and sub-seconds) stay: the capture time is pre-loss proof. | – |

The job runs daily at 04:15 UTC (`pokemans.retention.cron`) and logs what it did. `RETENTION_ENABLED=false` stops it.

## When a deal is closed

A label's deal is closed when it was **cancelled**, or when it was **settled** (payment confirmed, the card back with
the seller after a return, an admin resolution, or an older deal's recorded arrival or inspection) with no return or
dispute after the last payment confirmation or resolution. It closed at its last event.

Anything else keeps its addresses, on purpose: a deal stuck at "paid" that nobody confirmed, or a return or dispute the
admin hasn't resolved, never closes, so its addresses stay until the admin resolves it. An address is anonymised only
when **every** label that used it is closed and old enough.

## What the job can't reach

- **EasyPost** keeps its own copy of every address on a label it printed (and the label PDF). Its retention is
  EasyPost's; we don't delete from it.
- **The other side** has the address on the package.
- **Backups.** Database dumps age out after 35 days, so cleared emails and anonymised addresses leave the backups within
  35 days of the job. The photo copies in `photos-age/` are never deleted by the backup job.
- **Older backups still carry GPS.** Dumps and photo copies made before this change hold originals with their location
  (and the dumps hold the emails and addresses as they were). The plain-text ones are deleted by hand after 30 days
  ([hosting.md](hosting.md#deleting-the-plain-text-backups)).

## Photo originals uploaded before the change

Location is taken out at upload from this deploy on. For the originals uploaded before, the admin runs the one-off
backfill: **Admin → Photo location → Check 200 photos**, repeated until none remain (or
`POST /api/admin/photos/remove-location?limit=200`). Each photo is checked in its own transaction and logged, so it can
stop anywhere and carry on. It records on the photo row when it was checked (`location_checked_at`) and, when it
rewrote the original, the stored object's hash and size (`stored_sha256`, `stored_size_bytes`). The upload's own hash
(`sha256`) stays: duplicate checks and issued reports use it, so a report's photo hash is the hash of the upload, not of
the stored object.

An original is never rewritten if the copy would read a different capture time; that's logged and the original kept as
it is. A photo whose object is missing is logged and marked checked.

Run the backfill **before the first encrypted backup**, so the new `photos-age/` copies are made from stripped
originals: the backup copies a photo once and never again, and it skips objects under an hour old so a new upload is
copied after it was stripped.

R2 has no versioning, so a rewritten original's old bytes are gone from the photo bucket; only backups made before the
rewrite still hold them. This rewrite is the one time the app overwrites a photo.

## The `email_message` comment

V31's comment says email rows are "never deleted". That still holds: the row stays. Its content doesn't, after 90 days.

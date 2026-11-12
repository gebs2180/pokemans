# Data deletion

What we do when someone asks us to delete their data. Written 2026-11-09 for the architecture review's #44 / D12: the
migrations say "deletes stay possible, for data-deletion requests", and until now there was no tested way to do it.

The contributor terms (clause 5) promise: *you can ask us to delete what you shared; we will, except where it is already
part of a model or figure that has been trained or published.* The seller and buyer terms say nothing about deletion,
so we apply the same promise to everyone.

## What goes and what stays

"Store everything" is about our own calibration data while someone is a customer. A deletion request overrides it:
their photos, our predictions about their cards and their reviewers' adjustments all go. Three things stay, each for a
reason someone else relies on.

| | Deleted | Kept, and why |
|---|---|---|
| Cards nobody bought | The card, its photos (original, crop, view and exact copies), identifications, readings, provider predictions, condition assessments, centering labels, authenticity checks, offers, listings, grade outcomes | – |
| Cards a buyer took (a label was bought) | Custody records | The card, its offer, listing, label, events, deal outcome and photos. The buyer paid for it under "returns only if it doesn't match its photos", and the label's insurance claim may need them. |
| Valuation runs and reports | All of the org's, with the report PDFs | – |
| Addresses and payment handles | Every address not on a label | The two addresses on each kept label (the carrier, the insurer and the other party have them already). |
| The org | Memberships, invites; the org row if nothing kept points at it | Otherwise the org row, renamed "Deleted org …", and its folders, renamed "Deleted on request". Consent records stay only while the org does: they show the kept deals were made under the terms. |
| The people | Their user row, if nothing kept points at it; their Auth0 login | Otherwise the user row as a bare id: login subject replaced, email cleared. Someone still active in another org is left alone. |
| Emails we sent them | The address on each `email_message` row (`recipient_email`) | The rows themselves, with subject and body as sent until the retention job clears them 90 days after queueing ([data-retention.md](data-retention.md)): they are the audit trail of what we told whom and when, and the user row they point at stays as a bare id. |
| Already used | – | Models trained and figures published before the request (clause 5). Pre-grade providers keep what their terms let them keep from the photos we sent; we ask them to delete it where they offer that. |
| Backups | Photo copies, by hand once the 30-day bucket lock allows | Database dumps age out by themselves after 35 days. |
| Logs | – | Container logs rotate (5 × 20 MB per service); Sentry keeps errors for its plan's retention. Neither holds photos. |

The same procedure works for a buyer org: the cards it bought belong to the sellers' orgs and stay, with its address on
each label.

## How

Zach runs this; it changes the server. From `deploy/` on the server:

1. **Check who's asking.** The request comes from the email on the account, or from the person signed in. Find the org:
   the admin page, or `select id, name from org where name ilike '%…%';`.
2. **Dry run.** It prints what it would delete or keep, the bucket keys to remove and the logins to delete, then rolls
   back:

   ```bash
   docker compose -f compose.prod.yml --env-file .env exec -T postgres psql -U pokemans -d pokemans -v org_id=<org uuid> -f - < forget-org.sql
   ```

3. **Apply.** The same command with `-v apply=true`. Everything happens in one transaction: if any step fails, nothing
   changes. Keep the printed output; the keys and logins are gone from the database afterwards.
4. **Photo bucket.** Delete each printed `orgs/<org>/cards/<card>/` folder and each `reports/…pdf` key from the photo
   bucket, in the Cloudflare dashboard or with `rclone purge` on each folder.
   Kept cards' folders aren't printed: leave them.
5. **Auth0.** Delete each printed login (User Management → Users, search by the subject).
6. **Backup copies, after 30 days.** The backup bucket is locked for 30 days, so add a reminder and then delete the
   same folders under `pokemans-backups/photos-age/` (encrypted copies, `<key>.age`; and under `photos/` while the
   plain-text copies made before 2026-11-12 are still there), and from the off-site copy if one is set up. The nightly
   check only looks for every live photo in the backup, so removing them there is safe once they're gone from the live
   bucket.
7. **Pre-grade providers.** If the cards had predictions, ask each provider used to delete the photos, where its terms
   offer that.
8. **Reply.** Tell them what was deleted, what was kept and why (the table above), and that backups clear within 35
   days.

## Testing

`ForgetOrgScriptTest` runs `deploy/forget-org.sql` through psql against a real database, as above: a dry run changes
nothing; applying it deletes the untaken cards and keeps the taken one with its label and addresses; an org with
nothing kept is deleted outright. It also fails when a new table points at `card_item`, `org`, `photo`, `card_offer` or
`sale_listing` and the script doesn't handle it, so a new migration can't quietly break a deletion.

## Not covered yet

- **One person leaving a shared org.** Ending their membership (admin, in the app) stops their access; their user row
  and anything they recorded for the org stay. Forgetting one person while their org stays is a follow-up if it's ever
  asked for.
- **Deleting one card.** Not offered; a collector who wants one card gone can ask for the whole org, or we do it by hand
  with the script's steps.

# Backup runner

The `backup` service in `compose.prod.yml`. Full setup is in [docs/hosting.md](../../docs/hosting.md#backups); this covers
the off-site copy and the scheduled restore test added after the 2026-11-09 deep review (A1).

| Script | What it does |
|---|---|
| `schedule.sh` | The container's main process. Runs `backup.sh` daily at `BACKUP_AT` (UTC), then `restore-test.sh` on `RESTORE_TEST_DAYS` (default `Sun`) if the backup succeeded. |
| `backup.sh` | `pg_dump` and new photos to the R2 backup bucket, then to the off-site remote when it's set. Never deletes. |
| `restore-test.sh` | Restores the newest dump into a throwaway Postgres in the container, compares row counts with live, fails if a table with live rows (or `org`, `app_user`, `card_item`, `photo`) restored empty, and checks every photo is in the R2 copy, and off-site when it's on. |

Each pings its own healthchecks.io check when its URL is set: `BACKUP_HEALTHCHECK_URL` (daily) and
`RESTORE_TEST_HEALTHCHECK_URL` (weekly). Both send `/start`, then success or `/fail`.

## Off-site copy

Off unless `OFFSITE_REMOTE` and `OFFSITE_PATH` are both set in `.env`. Setting only one fails the backup and pings
`/fail`, so a half-done setup doesn't pass for a working one. Any provider rclone supports works: `OFFSITE_REMOTE` is
an rclone [connection string](https://rclone.org/docs/#connection-strings) ending in `:`, and `OFFSITE_PATH` is the
bucket or folder under it. The scripts wrap it in an alias remote called `offsite:`, so log lines don't show the key.

The copy uses `--immutable`: an object already there is never overwritten, and nothing is deleted. Old dumps pile
up, so give the provider its own expiry rule. Use an account that shares nothing with Cloudflare (other email, other
payment card if you can), and keep its login in Bitwarden.

### Example: Backblaze B2

1. Create a B2 account, then a private bucket, e.g. `rawmark-offsite`. Turn on Object Lock if you want deletes
   blocked even for a leaked key (set a default retention, e.g. 30 days).
2. Lifecycle settings: "Keep prior versions for this number of days" or a custom rule that deletes `postgres/`
   files after e.g. 60 days.
3. Application Keys → Add a New Application Key: access to `rawmark-offsite` only, type Read and Write.
   A key that can write can usually delete too; Object Lock is what stops that.
4. In `.env`:

   ```
   OFFSITE_REMOTE=:b2,account=<keyID>,key=<applicationKey>:
   OFFSITE_PATH=rawmark-offsite
   ```

### Example: Hetzner Storage Box

1. Order a Storage Box (BX11 is plenty). In its settings turn on SSH support and automatic snapshots (e.g. daily,
   keep 14): the snapshots are what protect the copy from a leaked password, since SFTP can delete.
2. Create a sub-account limited to a folder, e.g. `rawmark`, and note its user (`uNNNNNN-subN`) and password.
3. Obscure the password on any machine with rclone: `rclone obscure '<password>'`.
4. In `.env`:

   ```
   OFFSITE_REMOTE=:sftp,host=uNNNNNN-subN.your-storagebox.de,user=uNNNNNN-subN,port=23,pass=<obscured>:
   OFFSITE_PATH=rawmark
   ```

   With a folder-limited sub-account the path is relative to that folder; any name works, e.g. `rawmark`.

## Trying it

After changing `.env`, recreate the service and run one backup and restore test by hand:

```
docker compose -f compose.prod.yml --env-file .env up -d backup
docker compose -f compose.prod.yml run --rm backup bash -c "backup.sh && restore-test.sh"
```

The restore test needs free disk for one more copy of the database (it's removed afterwards).

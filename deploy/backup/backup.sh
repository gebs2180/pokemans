#!/usr/bin/env bash
# One backup run (docs/hosting.md#backups). Everything it writes is encrypted with age to the public key(s) in
# BACKUP_AGE_RECIPIENT; the private key is only in Zach's Bitwarden, never on the server
# (docs/hosting.md#backup-encryption-key). Without a valid key it refuses to run and pings /fail.
#   1. pg_dump (custom format), checked with pg_restore --list, encrypted, to
#      backups:<bucket>/postgres/pokemans-<UTC timestamp>.dump.age, with a plain-text row count per table
#      (pokemans-<stamp>.counts: table names and numbers only) for the restore test to compare against
#   2. each photo object not yet in backups:<bucket>/photos-age/, encrypted one by one to <key>.age
#      (copy, never sync: nothing is ever deleted from the copy). Objects under an hour old wait for the next
#      night, so a photo is copied after its location data has been stripped at upload
#      (docs/data-retention.md), not before
#   3. when OFFSITE_REMOTE and OFFSITE_PATH are set, the same encrypted dump, counts and photos to a second
#      provider in another account (deep review A1; deploy/backup/README.md)
# It never deletes: the bucket's lifecycle rule expires old dumps, and its lock rule stops
# anyone deleting a backup early, whoever holds this token (docs/hosting.md#3-r2-buckets-and-tokens).
# Run by hand: docker compose -f compose.prod.yml exec backup backup.sh
set -euo pipefail

: "${PHOTO_BUCKET:?}" "${BACKUP_BUCKET:?}"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

hc() { [[ -n "${HEALTHCHECK_URL:-}" ]] && curl -fsS -m 10 --retry 3 "${HEALTHCHECK_URL}$1" > /dev/null || true; }

# shellcheck source=age-recipients.sh
if ! source "$(dirname "$0")/age-recipients.sh"; then
  hc /fail
  exit 1
fi

# The off-site copy is off unless both are set. Half a setting fails the run rather than quietly
# skipping the copy someone meant to turn on.
offsite=false
if [[ -n "${OFFSITE_REMOTE:-}" || -n "${OFFSITE_PATH:-}" ]]; then
  if [[ -z "${OFFSITE_REMOTE:-}" || -z "${OFFSITE_PATH:-}" || "${OFFSITE_REMOTE}" != *: ]]; then
    echo "backup: set both OFFSITE_REMOTE (an rclone remote ending in ':') and OFFSITE_PATH, or neither" >&2
    hc /fail
    exit 1
  fi
  # An alias remote, so log lines and errors name "offsite:" and not the connection string's key.
  export RCLONE_CONFIG_OFFSITE_TYPE=alias
  export RCLONE_CONFIG_OFFSITE_REMOTE="${OFFSITE_REMOTE}${OFFSITE_PATH}"
  offsite=true
fi

trap 'hc /fail; rm -rf "$work"' ERR
hc /start

dump="pokemans-${stamp}.dump.age"
counts="pokemans-${stamp}.counts"
echo "backup ${stamp}: dumping postgres"
# Counted just before the dump, so the restore test can compare. Rows written in between make the dump a
# little bigger than the counts, never smaller.
psql -tA -c "select string_agg(format('select %L || '' '' || count(*) from %I', table_name, table_name), ' union all ' order by table_name)
    from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'" \
  | psql -tA > "$work/$counts"
pg_dump --format=custom --no-owner --file="$work/pokemans.dump"
pg_restore --list "$work/pokemans.dump" > /dev/null   # fails on a truncated or corrupt dump
age -e "${age_args[@]}" -o "$work/$dump" "$work/pokemans.dump"
size=$(du -h "$work/pokemans.dump" | cut -f1)
rm -f "$work/pokemans.dump"   # the plain dump never leaves this container
rclone copyto "$work/$dump" "backups:${BACKUP_BUCKET}/postgres/${dump}"
rclone copyto "$work/$counts" "backups:${BACKUP_BUCKET}/postgres/${counts}"

echo "backup ${stamp}: encrypting new photos"
# Names, not sizes: an encrypted copy is a little bigger than its photo, and a photo is never rewritten once
# it's an hour old (the location strip happens at upload; the one-off backfill runs before encryption starts).
rclone lsf -R --files-only --min-age 1h "photos:${PHOTO_BUCKET}" | sort > "$work/photos.txt"
rclone lsf -R --files-only "backups:${BACKUP_BUCKET}/photos-age" | sed -n 's/\.age$//p' | sort > "$work/copied.txt"
comm -23 "$work/photos.txt" "$work/copied.txt" > "$work/new.txt"
new=$(wc -l < "$work/new.txt")
# In batches of 200, so the container never holds more than a batch of plain photos at once.
split -l 200 "$work/new.txt" "$work/batch."
for batch in "$work"/batch.*; do
  [[ -e "$batch" ]] || continue
  mkdir -p "$work/plain" "$work/sealed"
  rclone copy "photos:${PHOTO_BUCKET}" "$work/plain" --files-from-raw "$batch" --transfers 8 --stats-one-line --stats 0
  while IFS= read -r key; do
    mkdir -p "$(dirname "$work/sealed/$key")"
    age -e "${age_args[@]}" -o "$work/sealed/$key.age" "$work/plain/$key"
  done < "$batch"
  rm -rf "$work/plain"
  rclone copy "$work/sealed" "backups:${BACKUP_BUCKET}/photos-age" --transfers 8 --stats-one-line --stats 0
  rm -rf "$work/sealed"
done

if $offsite; then
  # --immutable: an object already there is never overwritten, so a bad run can't replace a good copy.
  echo "backup ${stamp}: copying dump and photos off-site"
  rclone copyto "$work/$dump" "offsite:postgres/${dump}" --immutable
  rclone copyto "$work/$counts" "offsite:postgres/${counts}" --immutable
  rclone copy "backups:${BACKUP_BUCKET}/photos-age" "offsite:photos-age" --immutable --transfers 4 --stats-one-line --stats 0
fi

hc ""
echo "backup ${stamp}: done (${size} dump, encrypted; ${new} new photos$($offsite && echo "; copied off-site"))"

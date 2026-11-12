#!/usr/bin/env bash
# Restore test (docs/hosting.md#restore-test). Runs on Zach's own machine, never the server: it needs the backup
# private key, which is only in Bitwarden. It proves the newest encrypted backup can be decrypted and used:
#   1. downloads the newest dump, decrypts it with AGE_IDENTITY, and restores it into a throwaway Postgres inside
#      this container
#   2. compares row counts of every table with the counts taken at backup time (pokemans-<stamp>.counts), and
#      fails if a table that had rows restored empty, the key tables restored empty, or a migration failed
#   3. decrypts the newest RESTORE_TEST_PHOTO_SAMPLE photos (default 5) and checks each is a JPEG or PNG
#   4. when OFFSITE_REMOTE and OFFSITE_PATH are set, checks the off-site copy holds the same newest dump
# On success it pings RESTORE_TEST_HEALTHCHECK_URL (monthly check), on failure <url>/fail.
#
# AGE_IDENTITY is the private key's text (the AGE-SECRET-KEY-1... line). It is read from the environment and
# handed to age through a pipe, so it is never written to disk, in the container or on the host. From PowerShell,
# with the key copied from Bitwarden:
#   $env:AGE_IDENTITY = Get-Clipboard; Set-Clipboard -Value ' '
#   docker run --rm -e AGE_IDENTITY --env-file restore-test.env ghcr.io/gebs2180/pokemans-backup:main restore-test.sh
#   Remove-Item Env:AGE_IDENTITY
# restore-test.env holds the R2 endpoint and the backup write token's keys (docs/hosting.md#restore-test).
set -euo pipefail

: "${BACKUP_BUCKET:?}" "${AGE_IDENTITY:?set AGE_IDENTITY to the backup private key (AGE-SECRET-KEY-1...)}"
hc() { [[ -n "${RESTORE_TEST_HEALTHCHECK_URL:-}" ]] && curl -fsS -m 10 --retry 3 "${RESTORE_TEST_HEALTHCHECK_URL}$1" > /dev/null || true; }
fail() { echo "restore-test: $*" >&2; hc /fail; exit 1; }
decrypt() { age -d -i <(printf '%s\n' "$AGE_IDENTITY") "$@"; }

offsite=false
if [[ -n "${OFFSITE_REMOTE:-}" || -n "${OFFSITE_PATH:-}" ]]; then
  [[ -n "${OFFSITE_REMOTE:-}" && -n "${OFFSITE_PATH:-}" && "${OFFSITE_REMOTE}" == *: ]] \
    || fail "set both OFFSITE_REMOTE (an rclone remote ending in ':') and OFFSITE_PATH, or neither"
  export RCLONE_CONFIG_OFFSITE_TYPE=alias
  export RCLONE_CONFIG_OFFSITE_REMOTE="${OFFSITE_REMOTE}${OFFSITE_PATH}"
  offsite=true
fi

work=$(mktemp -d)
port=55432
# The image runs as postgres; a run started as root (`run --user root`) drops to it for the server.
if (( EUID == 0 )); then
  chown postgres "$work"
  as_pg() { gosu postgres "$@"; }
else
  as_pg() { "$@"; }
fi
trap 'as_pg pg_ctl --pgdata="$work/data" --mode=immediate stop > /dev/null 2>&1 || true; rm -rf "$work"' EXIT
trap 'hc /fail' ERR
hc /start

latest=$(rclone lsf "backups:${BACKUP_BUCKET}/postgres" --include 'pokemans-*.dump.age' | sort | tail -n 1)
[[ -n "$latest" ]] || fail "no encrypted dumps in ${BACKUP_BUCKET}/postgres"
stamp=${latest#pokemans-}
stamp=${stamp%.dump.age}
echo "restore-test: decrypting and restoring ${latest}"
rclone copyto "backups:${BACKUP_BUCKET}/postgres/${latest}" "$work/restore.dump.age"
rclone copyto "backups:${BACKUP_BUCKET}/postgres/pokemans-${stamp}.counts" "$work/counts" \
  || fail "pokemans-${stamp}.counts is missing"
decrypt -o "$work/restore.dump" "$work/restore.dump.age" || fail "couldn't decrypt ${latest}: wrong key?"
rm -f "$work/restore.dump.age"
if (( EUID == 0 )); then chown postgres "$work/restore.dump"; fi

as_pg initdb --pgdata="$work/data" --username=postgres --auth=trust > /dev/null
as_pg pg_ctl --pgdata="$work/data" --options="-p ${port} -k ${work}" --log="$work/pg.log" --wait start > /dev/null
as_pg createdb -h "$work" -p "$port" -U postgres pokemans
as_pg pg_restore -h "$work" -p "$port" -U postgres --dbname=pokemans --no-owner --exit-on-error "$work/restore.dump"
rm -f "$work/restore.dump"
restored_sql() { as_pg psql -h "$work" -p "$port" -U postgres -d pokemans -tAc "$1"; }

echo "restore-test: rows per table, restored vs counted at backup time"
printf '%-40s %10s %10s\n' table restored counted
failed=0
while read -r t counted; do
  [[ -n "$t" ]] || continue
  restored=$(restored_sql "select count(*) from \"$t\"" 2> /dev/null || echo missing)
  flag=""
  if [[ "$restored" == missing ]]; then flag="  <-- NOT in the restore"; failed=1
  # The counts are taken just before the dump, so the restore can have a few more, never fewer.
  elif (( restored < counted )); then flag="  <-- fewer than counted"; failed=1
  elif (( restored == 0 && counted > 0 )); then flag="  <-- EMPTY in the restore"; failed=1
  fi
  printf '%-40s %10s %10s%s\n' "$t" "$restored" "$counted" "$flag"
done < "$work/counts"

# The sanity query: the tables everything else hangs off can't restore empty.
for t in org app_user card_item photo flyway_schema_history; do
  (( $(restored_sql "select count(*) from \"$t\"") > 0 )) || { echo "restore-test: key table $t restored empty" >&2; failed=1; }
done
(( $(restored_sql "select count(*) from flyway_schema_history where not success") == 0 )) \
  || { echo "restore-test: restored schema has a failed migration" >&2; failed=1; }
migrations=$(restored_sql "select max(version::int) from flyway_schema_history where version is not null")
echo "restore-test: restored schema is at migration V${migrations}"

sample="${RESTORE_TEST_PHOTO_SAMPLE:-5}"
echo "restore-test: decrypting the newest ${sample} photos"
rclone lsf -R --files-only --format tp "backups:${BACKUP_BUCKET}/photos-age" | sort -r | head -n "$sample" | cut -d';' -f2 > "$work/sample.txt"
[[ -s "$work/sample.txt" ]] || { echo "restore-test: no encrypted photos in photos-age/" >&2; failed=1; }
while IFS= read -r key; do
  rclone copyto "backups:${BACKUP_BUCKET}/photos-age/${key}" "$work/photo.age"
  if ! decrypt -o "$work/photo" "$work/photo.age"; then
    echo "restore-test: couldn't decrypt photos-age/${key}" >&2; failed=1; continue
  fi
  magic=$(head -c 4 "$work/photo" | od -An -tx1 | tr -d ' \n')
  case "$magic" in
    ffd8ff*|89504e47) echo "restore-test: ${key%.age} OK ($(du -h "$work/photo" | cut -f1))" ;;
    *) echo "restore-test: ${key%.age} decrypted to something that isn't a JPEG or PNG (${magic})" >&2; failed=1 ;;
  esac
  rm -f "$work/photo" "$work/photo.age"
done < "$work/sample.txt"

if $offsite; then
  echo "restore-test: checking the off-site copy has ${latest}"
  primary_size=$(rclone lsf "backups:${BACKUP_BUCKET}/postgres/${latest}" --format s)
  offsite_size=$(rclone lsf "offsite:postgres/${latest}" --format s 2> /dev/null || true)
  if [[ "$offsite_size" != "$primary_size" ]]; then
    echo "restore-test: off-site copy of ${latest} is missing or a different size (${offsite_size:-none} vs ${primary_size})" >&2
    failed=1
  fi
fi

if (( failed )); then fail "FAILED, see above"; fi
hc ""
echo "restore-test: OK"

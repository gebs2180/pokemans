#!/usr/bin/env bash
# Restore test (docs/hosting.md#restore-test): proves last night's backup can be used, without touching production.
#   1. downloads the newest dump and restores it into a throwaway Postgres inside this container
#   2. compares row counts of every table with the live database (the dump may be up to a day older), and
#      fails if a table the live database has rows in restored empty, or the key tables restored empty
#   3. checks every photo object exists in the backup copy with the same size
#   4. when the off-site copy is on (OFFSITE_REMOTE, OFFSITE_PATH), checks it holds the same newest dump and
#      every photo too
# On success it pings RESTORE_TEST_HEALTHCHECK_URL, on failure <url>/fail (optional; deep review A1).
# schedule.sh runs it weekly (RESTORE_TEST_DAYS) straight after a backup. By hand, the same way:
#   docker compose -f compose.prod.yml run --rm backup bash -c "backup.sh && restore-test.sh"
set -euo pipefail

: "${PHOTO_BUCKET:?}" "${BACKUP_BUCKET:?}"
hc() { [[ -n "${RESTORE_TEST_HEALTHCHECK_URL:-}" ]] && curl -fsS -m 10 --retry 3 "${RESTORE_TEST_HEALTHCHECK_URL}$1" > /dev/null || true; }
fail() { echo "restore-test: $*" >&2; hc /fail; exit 1; }

offsite=false
if [[ -n "${OFFSITE_REMOTE:-}" || -n "${OFFSITE_PATH:-}" ]]; then
  [[ -n "${OFFSITE_REMOTE:-}" && -n "${OFFSITE_PATH:-}" && "${OFFSITE_REMOTE}" == *: ]] \
    || fail "set both OFFSITE_REMOTE (an rclone remote ending in ':') and OFFSITE_PATH, or neither"
  export RCLONE_CONFIG_OFFSITE_TYPE=alias
  export RCLONE_CONFIG_OFFSITE_REMOTE="${OFFSITE_REMOTE}${OFFSITE_PATH}"
  offsite=true
fi

work=$(mktemp -d)
chown postgres "$work"
port=55432
as_pg() { gosu postgres "$@"; }
trap 'as_pg pg_ctl --pgdata="$work/data" --mode=immediate stop > /dev/null 2>&1 || true; rm -rf "$work"' EXIT
trap 'hc /fail' ERR
hc /start

latest=$(rclone lsf "backups:${BACKUP_BUCKET}/postgres" --include 'pokemans-*.dump' | sort | tail -n 1)
[[ -n "$latest" ]] || fail "no dumps in ${BACKUP_BUCKET}/postgres"
echo "restore-test: restoring ${latest}"
rclone copyto "backups:${BACKUP_BUCKET}/postgres/${latest}" "$work/restore.dump"
chown postgres "$work/restore.dump"

as_pg initdb --pgdata="$work/data" --username=postgres --auth=trust > /dev/null
as_pg pg_ctl --pgdata="$work/data" --options="-p ${port} -k ${work}" --log="$work/pg.log" --wait start > /dev/null
as_pg createdb -h "$work" -p "$port" -U postgres pokemans
as_pg pg_restore -h "$work" -p "$port" -U postgres --dbname=pokemans --no-owner --exit-on-error "$work/restore.dump"
restored_sql() { as_pg psql -h "$work" -p "$port" -U postgres -d pokemans -tAc "$1"; }

tables=$(psql -tAc "select string_agg(table_name, ' ' order by table_name) from information_schema.tables
  where table_schema = 'public' and table_type = 'BASE TABLE'")
echo "restore-test: rows per table, restored vs live"
printf '%-40s %10s %10s\n' table restored live
failed=0
for t in $tables; do
  restored=$(restored_sql "select count(*) from \"$t\"")
  live=$(psql -tAc "select count(*) from \"$t\"")
  flag=""
  # The dump is up to a day old, so live usually has more. More in the restore is worth a look (deletes since).
  if (( restored > live )); then flag="  <-- more than live"; fi
  # Rows live but none restored: the table didn't come back. (A table created since the dump is also
  # empty in the restore, but then it isn't in it at all and the count above already failed the run.)
  if (( restored == 0 && live > 0 )); then flag="  <-- EMPTY in the restore"; failed=1; fi
  printf '%-40s %10s %10s%s\n' "$t" "$restored" "$live" "$flag"
done

# The sanity query: the tables everything else hangs off can't restore empty.
for t in org app_user card_item photo flyway_schema_history; do
  (( $(restored_sql "select count(*) from \"$t\"") > 0 )) || { echo "restore-test: key table $t restored empty" >&2; failed=1; }
done
(( $(restored_sql "select count(*) from flyway_schema_history where not success") == 0 )) \
  || { echo "restore-test: restored schema has a failed migration" >&2; failed=1; }
migrations=$(restored_sql "select max(version::int) from flyway_schema_history where version is not null")
echo "restore-test: restored schema is at migration V${migrations}"

echo "restore-test: checking every photo is in the backup copy"
if ! rclone check "photos:${PHOTO_BUCKET}" "backups:${BACKUP_BUCKET}/photos" --one-way --size-only; then
  echo "restore-test: photos missing from the backup copy" >&2
  failed=1
fi

if $offsite; then
  echo "restore-test: checking the off-site copy has ${latest} and every photo"
  primary_size=$(rclone lsf "backups:${BACKUP_BUCKET}/postgres/${latest}" --format s)
  offsite_size=$(rclone lsf "offsite:postgres/${latest}" --format s 2> /dev/null || true)
  if [[ "$offsite_size" != "$primary_size" ]]; then
    echo "restore-test: off-site copy of ${latest} is missing or a different size (${offsite_size:-none} vs ${primary_size})" >&2
    failed=1
  fi
  if ! rclone check "photos:${PHOTO_BUCKET}" "offsite:photos" --one-way --size-only; then
    echo "restore-test: photos missing from the off-site copy" >&2
    failed=1
  fi
fi

if (( failed )); then fail "FAILED, see above"; fi
hc ""
echo "restore-test: OK"

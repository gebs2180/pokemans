#!/usr/bin/env bash
# Restore test (docs/hosting.md#restore-test): proves last night's backup can be used, without touching production.
#   1. downloads the newest dump and restores it into a throwaway Postgres inside this container
#   2. compares row counts of the main tables with the live database (the dump may be up to a day older)
#   3. checks every photo object exists in the backup copy with the same size
# Run right after a backup, so photos added since last night don't count as missing:
#   docker compose -f compose.prod.yml run --rm backup bash -c "backup.sh && restore-test.sh"
set -euo pipefail

: "${PHOTO_BUCKET:?}" "${BACKUP_BUCKET:?}"
work=$(mktemp -d)
chown postgres "$work"
port=55432

latest=$(rclone lsf "backups:${BACKUP_BUCKET}/postgres" --include 'pokemans-*.dump' | sort | tail -n 1)
[[ -n "$latest" ]] || { echo "restore-test: no dumps in ${BACKUP_BUCKET}/postgres" >&2; exit 1; }
echo "restore-test: restoring ${latest}"
rclone copyto "backups:${BACKUP_BUCKET}/postgres/${latest}" "$work/restore.dump"
chown postgres "$work/restore.dump"

as_pg() { gosu postgres "$@"; }
as_pg initdb --pgdata="$work/data" --username=postgres --auth=trust > /dev/null
as_pg pg_ctl --pgdata="$work/data" --options="-p ${port} -k ${work}" --log="$work/pg.log" --wait start > /dev/null
trap 'as_pg pg_ctl --pgdata="$work/data" --mode=immediate stop > /dev/null 2>&1 || true; rm -rf "$work"' EXIT
as_pg createdb -h "$work" -p "$port" -U postgres pokemans
as_pg pg_restore -h "$work" -p "$port" -U postgres --dbname=pokemans --no-owner --exit-on-error "$work/restore.dump"

tables=$(psql -tAc "select string_agg(table_name, ' ' order by table_name) from information_schema.tables
  where table_schema = 'public' and table_type = 'BASE TABLE'")
echo "restore-test: rows per table, restored vs live"
printf '%-40s %10s %10s\n' table restored live
failed=0
for t in $tables; do
  restored=$(as_pg psql -h "$work" -p "$port" -U postgres -d pokemans -tAc "select count(*) from \"$t\"")
  live=$(psql -tAc "select count(*) from \"$t\"")
  flag=""
  # The dump is up to a day old, so live usually has more. More in the restore is worth a look (deletes since).
  if (( restored > live )); then flag="  <-- more than live"; fi
  printf '%-40s %10s %10s%s\n' "$t" "$restored" "$live" "$flag"
done
(( $(as_pg psql -h "$work" -p "$port" -U postgres -d pokemans -tAc "select count(*) from flyway_schema_history where not success") == 0 ))   || { echo "restore-test: restored schema has a failed migration" >&2; failed=1; }
migrations=$(as_pg psql -h "$work" -p "$port" -U postgres -d pokemans -tAc "select max(version::int) from flyway_schema_history where version is not null")
echo "restore-test: restored schema is at migration V${migrations}"

echo "restore-test: checking every photo is in the backup copy"
if ! rclone check "photos:${PHOTO_BUCKET}" "backups:${BACKUP_BUCKET}/photos" --one-way --size-only; then
  echo "restore-test: photos missing from the backup copy" >&2
  failed=1
fi

if (( failed )); then echo "restore-test: FAILED, see above" >&2; exit 1; fi
echo "restore-test: OK"

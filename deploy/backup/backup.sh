#!/usr/bin/env bash
# One backup run (docs/hosting.md#backups):
#   1. pg_dump (custom format) to backups:<bucket>/postgres/pokemans-<UTC timestamp>.dump
#   2. copy new photos to backups:<bucket>/photos/ (copy, never sync: nothing is ever deleted from the copy)
#   3. delete dumps older than BACKUP_KEEP_DAYS
# Run by hand: docker compose -f compose.prod.yml exec backup backup.sh
set -euo pipefail

: "${PHOTO_BUCKET:?}" "${BACKUP_BUCKET:?}"
keep="${BACKUP_KEEP_DAYS:-30}"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

hc() { [[ -n "${HEALTHCHECK_URL:-}" ]] && curl -fsS -m 10 --retry 3 "${HEALTHCHECK_URL}$1" > /dev/null || true; }
trap 'hc /fail; rm -rf "$work"' ERR
hc /start

echo "backup ${stamp}: dumping postgres"
pg_dump --format=custom --no-owner --file="$work/pokemans.dump"
pg_restore --list "$work/pokemans.dump" > /dev/null   # fails on a truncated or corrupt dump
rclone copyto "$work/pokemans.dump" "backups:${BACKUP_BUCKET}/postgres/pokemans-${stamp}.dump"

echo "backup ${stamp}: copying photos"
rclone copy "photos:${PHOTO_BUCKET}" "backups:${BACKUP_BUCKET}/photos" --transfers 8 --stats-one-line --stats 0

echo "backup ${stamp}: pruning dumps older than ${keep} days"
rclone delete "backups:${BACKUP_BUCKET}/postgres" --min-age "${keep}d"

hc ""
echo "backup ${stamp}: done ($(du -h "$work/pokemans.dump" | cut -f1) dump)"

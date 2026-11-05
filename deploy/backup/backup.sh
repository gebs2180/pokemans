#!/usr/bin/env bash
# One backup run (docs/hosting.md#backups):
#   1. pg_dump (custom format) to backups:<bucket>/postgres/pokemans-<UTC timestamp>.dump
#   2. copy new photos to backups:<bucket>/photos/ (copy, never sync: nothing is ever deleted from the copy)
# It never deletes: the bucket's lifecycle rule expires old dumps, and its lock rule stops
# anyone deleting a backup early, whoever holds this token (docs/hosting.md#3-r2-buckets-and-tokens).
# Run by hand: docker compose -f compose.prod.yml exec backup backup.sh
set -euo pipefail

: "${PHOTO_BUCKET:?}" "${BACKUP_BUCKET:?}"
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

hc ""
echo "backup ${stamp}: done ($(du -h "$work/pokemans.dump" | cut -f1) dump)"

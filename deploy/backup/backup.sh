#!/usr/bin/env bash
# One backup run (docs/hosting.md#backups):
#   1. pg_dump (custom format) to backups:<bucket>/postgres/pokemans-<UTC timestamp>.dump
#   2. copy new photos to backups:<bucket>/photos/ (copy, never sync: nothing is ever deleted from the copy)
#   3. when OFFSITE_REMOTE and OFFSITE_PATH are set, the same dump and photos to a second provider in
#      another account (deep review A1; deploy/backup/README.md), so losing the Cloudflare account
#      doesn't lose the backups with it
# It never deletes: the bucket's lifecycle rule expires old dumps, and its lock rule stops
# anyone deleting a backup early, whoever holds this token (docs/hosting.md#3-r2-buckets-and-tokens).
# Run by hand: docker compose -f compose.prod.yml exec backup backup.sh
set -euo pipefail

: "${PHOTO_BUCKET:?}" "${BACKUP_BUCKET:?}"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

hc() { [[ -n "${HEALTHCHECK_URL:-}" ]] && curl -fsS -m 10 --retry 3 "${HEALTHCHECK_URL}$1" > /dev/null || true; }

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

echo "backup ${stamp}: dumping postgres"
pg_dump --format=custom --no-owner --file="$work/pokemans.dump"
pg_restore --list "$work/pokemans.dump" > /dev/null   # fails on a truncated or corrupt dump
rclone copyto "$work/pokemans.dump" "backups:${BACKUP_BUCKET}/postgres/pokemans-${stamp}.dump"

echo "backup ${stamp}: copying photos"
rclone copy "photos:${PHOTO_BUCKET}" "backups:${BACKUP_BUCKET}/photos" --transfers 8 --stats-one-line --stats 0

if $offsite; then
  # --immutable: an object already there is never overwritten, so a bad run can't replace a good copy.
  echo "backup ${stamp}: copying dump and photos off-site"
  rclone copyto "$work/pokemans.dump" "offsite:postgres/pokemans-${stamp}.dump" --immutable
  rclone copy "photos:${PHOTO_BUCKET}" "offsite:photos" --immutable --transfers 4 --stats-one-line --stats 0
fi

hc ""
echo "backup ${stamp}: done ($(du -h "$work/pokemans.dump" | cut -f1) dump$($offsite && echo ", copied off-site"))"

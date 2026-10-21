#!/usr/bin/env bash
# Runs backup.sh once a day at BACKUP_AT (UTC, HH:MM). A failed run is logged and retried the next day;
# set BACKUP_HEALTHCHECK_URL to get an email when a night is missed.
set -uo pipefail
at="${BACKUP_AT:-03:30}"
echo "backup: scheduled daily at ${at} UTC"
while true; do
  now=$(date -u +%s)
  next=$(date -u -d "today ${at}" +%s)
  if (( next <= now )); then next=$(date -u -d "tomorrow ${at}" +%s); fi
  sleep $(( next - now ))
  backup.sh || echo "backup: FAILED at $(date -u +%FT%TZ)" >&2
done

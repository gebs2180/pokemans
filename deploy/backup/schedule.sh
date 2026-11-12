#!/usr/bin/env bash
# Runs backup.sh once a day at BACKUP_AT (UTC, HH:MM). A failed run is logged and retried the next day;
# set BACKUP_HEALTHCHECK_URL to get an email when a night is missed.
# After every successful backup, backup-check.sh checks the newest dump and photo copies are there, recent and
# encrypted (BACKUP_CHECK_HEALTHCHECK_URL). The full restore test needs the private key, which isn't on the server,
# so it runs on Zach's machine (restore-test.sh, docs/hosting.md#restore-test), not here.
set -uo pipefail
at="${BACKUP_AT:-03:30}"
echo "backup: scheduled daily at ${at} UTC, then backup-check.sh"

while true; do
  now=$(date -u +%s)
  next=$(date -u -d "today ${at}" +%s)
  if (( next <= now )); then next=$(date -u -d "tomorrow ${at}" +%s); fi
  sleep $(( next - now ))
  if backup.sh; then
    backup-check.sh || echo "backup-check: FAILED at $(date -u +%FT%TZ)" >&2
  else
    # No check after a failed backup: the missed ping already shows it.
    echo "backup: FAILED at $(date -u +%FT%TZ)" >&2
  fi
done

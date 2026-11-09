#!/usr/bin/env bash
# Runs backup.sh once a day at BACKUP_AT (UTC, HH:MM). A failed run is logged and retried the next day;
# set BACKUP_HEALTHCHECK_URL to get an email when a night is missed.
# On RESTORE_TEST_DAYS (UTC weekday names, e.g. "Sun" or "Sun,Wed", or "daily"; "off" turns it off),
# restore-test.sh runs straight after a successful backup, so photos added since aren't counted as
# missing. Set RESTORE_TEST_HEALTHCHECK_URL to get an email when a week's test fails or doesn't run.
set -uo pipefail
at="${BACKUP_AT:-03:30}"
restore_days="${RESTORE_TEST_DAYS:-Sun}"
echo "backup: scheduled daily at ${at} UTC; restore test on: ${restore_days}"

restore_test_today() {
  local today
  today=$(date -u +%a)
  case ",${restore_days,,}," in
    *,off,*) return 1 ;;
    *,daily,*) return 0 ;;
    *",${today,,},"*) return 0 ;;
    *) return 1 ;;
  esac
}

while true; do
  now=$(date -u +%s)
  next=$(date -u -d "today ${at}" +%s)
  if (( next <= now )); then next=$(date -u -d "tomorrow ${at}" +%s); fi
  sleep $(( next - now ))
  if backup.sh; then
    if restore_test_today; then
      restore-test.sh || echo "restore-test: FAILED at $(date -u +%FT%TZ)" >&2
    fi
  else
    # No restore test after a failed backup: it would test yesterday's dump. The missed ping shows it.
    echo "backup: FAILED at $(date -u +%FT%TZ)" >&2
  fi
done

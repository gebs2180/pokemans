#!/usr/bin/env bash
# Sourced by backup.sh: turns BACKUP_AGE_RECIPIENT into age's -r arguments, in the array age_args.
# The value is one public key (age1...), or several separated by spaces or commas (e.g. a second key kept
# somewhere else). The matching private keys are never on the server (docs/hosting.md#backup-encryption-key),
# so the server can write backups but can't read them. Without a valid key nothing is written: a backup
# in plain text is what this is here to stop.
age_args=()
_recipients=${BACKUP_AGE_RECIPIENT:-}
for r in ${_recipients//,/ }; do
  if [[ ! "$r" =~ ^age1[02-9ac-hj-np-z]{58}$ ]]; then
    echo "backup: BACKUP_AGE_RECIPIENT has '${r:0:12}...', which isn't an age public key (age1 and 58 more characters)" >&2
    age_args=()
    return 1
  fi
  age_args+=(-r "$r")
done
if (( ${#age_args[@]} == 0 )); then
  echo "backup: set BACKUP_AGE_RECIPIENT in .env to the backup public key (age1...); refusing to write an unencrypted backup" >&2
  return 1
fi

#!/usr/bin/env bash
# Disaster recovery for the database (docs/hosting.md#restore-after-a-disaster). Runs on Zach's own machine with
# the backup private key, like restore-test.sh: downloads the newest encrypted dump (or the one named as the
# second argument, e.g. a pre-deploy backup) and decrypts it to <dir>/restore.dump, normally a mounted folder:
#   docker run --rm -e AGE_IDENTITY --env-file restore-test.env -v "$HOME\rawmark\restore:/out" --user root
#     ghcr.io/gebs2180/pokemans-backup:main decrypt-dump.sh /out
# A script, not a bash -c one-liner: PowerShell 5.1 mangles double quotes inside arguments to docker.
set -euo pipefail

: "${BACKUP_BUCKET:?}" "${AGE_IDENTITY:?set AGE_IDENTITY to the backup private key (AGE-SECRET-KEY-1...)}"
out="${1:?usage: decrypt-dump.sh <dir> [pokemans-<stamp>.dump.age]}"
name="${2:-$(rclone lsf "backups:${BACKUP_BUCKET}/postgres" --include 'pokemans-*.dump.age' | sort | tail -n 1)}"
[[ -n "$name" ]] || { echo "decrypt-dump: no encrypted dumps in ${BACKUP_BUCKET}/postgres" >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
echo "decrypt-dump: ${name}"
rclone copyto "backups:${BACKUP_BUCKET}/postgres/${name}" "$work/dump.age"
age -d -i <(printf '%s\n' "$AGE_IDENTITY") -o "$out/restore.dump" "$work/dump.age"
pg_restore --list "$out/restore.dump" > /dev/null
echo "decrypt-dump: wrote $out/restore.dump ($(du -h "$out/restore.dump" | cut -f1)); delete it once restored"

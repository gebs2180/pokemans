#!/usr/bin/env bash
# Disaster recovery for photos (docs/hosting.md#restore-after-a-disaster). Runs on Zach's own machine with the
# backup private key, like restore-test.sh: decrypts every photo in backups:<bucket>/photos-age/ that's missing
# from the photo bucket and uploads it there under its original key. Never overwrites a photo that's there.
#   $env:AGE_IDENTITY = Get-Clipboard; Set-Clipboard -Value ' '
#   docker run --rm -e AGE_IDENTITY --env-file restore-photos.env ghcr.io/gebs2180/pokemans-backup:main restore-photos.sh
#   Remove-Item Env:AGE_IDENTITY
# restore-photos.env has the backup token's keys for "backups" and the app's token (it can write to the photo
# bucket; the backup read token can't) for "photos".
set -euo pipefail

: "${PHOTO_BUCKET:?}" "${BACKUP_BUCKET:?}" "${AGE_IDENTITY:?set AGE_IDENTITY to the backup private key (AGE-SECRET-KEY-1...)}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

rclone lsf -R --files-only "photos:${PHOTO_BUCKET}" | sort > "$work/present.txt"
rclone lsf -R --files-only "backups:${BACKUP_BUCKET}/photos-age" | sed -n 's/\.age$//p' | sort > "$work/sealed.txt"
comm -13 "$work/present.txt" "$work/sealed.txt" > "$work/missing.txt"
echo "restore-photos: $(wc -l < "$work/missing.txt") photos to restore"
split -l 200 "$work/missing.txt" "$work/batch."
for batch in "$work"/batch.*; do
  [[ -e "$batch" ]] || continue
  sed 's/$/.age/' "$batch" > "$batch.age"
  rclone copy "backups:${BACKUP_BUCKET}/photos-age" "$work/sealed" --files-from-raw "$batch.age" --transfers 8
  while IFS= read -r key; do
    mkdir -p "$(dirname "$work/plain/$key")"
    age -d -i <(printf '%s\n' "$AGE_IDENTITY") -o "$work/plain/$key" "$work/sealed/$key.age"
  done < "$batch"
  rclone copy "$work/plain" "photos:${PHOTO_BUCKET}" --immutable --transfers 8
  rm -rf "$work/plain" "$work/sealed"
done
echo "restore-photos: done"

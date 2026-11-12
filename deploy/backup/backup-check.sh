#!/usr/bin/env bash
# Server-side backup check (docs/hosting.md#7-backups-and-the-restore-test). The server can't decrypt the backups
# (the private key isn't on it), so this checks what it can see, and the full restore test runs on Zach's own
# machine with the key (restore-test.sh):
#   1. the newest dump exists, is under BACKUP_CHECK_MAX_AGE_HOURS old (default 26), starts with age's header,
#      and has its row-count file next to it
#   2. every photo over two hours old has an encrypted copy in photos-age/, and a sample of the newest copies
#      starts with age's header
#   3. when the off-site copy is on, it holds the same newest dump (same size) and every encrypted photo
#   4. counts the plain-text dumps and photos left from before encryption, which must be deleted by hand
#      (docs/hosting.md#deleting-the-plain-text-backups); it reports them but doesn't fail on them
# schedule.sh runs it after every successful backup. On success it pings BACKUP_CHECK_HEALTHCHECK_URL, on
# failure <url>/fail. By hand: docker compose -f compose.prod.yml exec backup backup-check.sh
set -euo pipefail

: "${PHOTO_BUCKET:?}" "${BACKUP_BUCKET:?}"
hc() { [[ -n "${BACKUP_CHECK_HEALTHCHECK_URL:-}" ]] && curl -fsS -m 10 --retry 3 "${BACKUP_CHECK_HEALTHCHECK_URL}$1" > /dev/null || true; }
fail() { echo "backup-check: $*" >&2; hc /fail; exit 1; }
max_age_hours="${BACKUP_CHECK_MAX_AGE_HOURS:-26}"
header='age-encryption.org/v1'

offsite=false
if [[ -n "${OFFSITE_REMOTE:-}" || -n "${OFFSITE_PATH:-}" ]]; then
  [[ -n "${OFFSITE_REMOTE:-}" && -n "${OFFSITE_PATH:-}" && "${OFFSITE_REMOTE}" == *: ]] \
    || fail "set both OFFSITE_REMOTE (an rclone remote ending in ':') and OFFSITE_PATH, or neither"
  export RCLONE_CONFIG_OFFSITE_TYPE=alias
  export RCLONE_CONFIG_OFFSITE_REMOTE="${OFFSITE_REMOTE}${OFFSITE_PATH}"
  offsite=true
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
trap 'hc /fail' ERR
hc /start
failed=0
problem() { echo "backup-check: $*" >&2; failed=1; }

# 1. The newest dump.
latest=$(rclone lsf "backups:${BACKUP_BUCKET}/postgres" --include 'pokemans-*.dump.age' | sort | tail -n 1)
[[ -n "$latest" ]] || fail "no encrypted dumps in ${BACKUP_BUCKET}/postgres"
stamp=${latest#pokemans-}
stamp=${stamp%.dump.age}
taken=$(date -u -d "${stamp:0:4}-${stamp:4:2}-${stamp:6:2}T${stamp:9:2}:${stamp:11:2}:${stamp:13:2}Z" +%s)
age_hours=$(( ($(date -u +%s) - taken) / 3600 ))
echo "backup-check: newest dump ${latest}, ${age_hours}h old"
(( age_hours < max_age_hours )) || problem "newest dump is ${age_hours}h old (limit ${max_age_hours}h)"
first=$(rclone cat "backups:${BACKUP_BUCKET}/postgres/${latest}" --count ${#header})
[[ "$first" == "$header" ]] || problem "${latest} doesn't start with the age header, so it isn't encrypted"
rclone lsf "backups:${BACKUP_BUCKET}/postgres/pokemans-${stamp}.counts" | grep -q . \
  || problem "pokemans-${stamp}.counts is missing, so the restore test has nothing to compare against"

# 2. Every photo has an encrypted copy. Two hours, not one: backup.sh copies objects over an hour old.
rclone lsf -R --files-only --min-age 2h "photos:${PHOTO_BUCKET}" | sort > "$work/photos.txt"
rclone lsf -R --files-only "backups:${BACKUP_BUCKET}/photos-age" | sort > "$work/sealed.txt"
sed -n 's/\.age$//p' "$work/sealed.txt" | sort > "$work/copied.txt"
missing=$(comm -23 "$work/photos.txt" "$work/copied.txt" | wc -l)
echo "backup-check: $(wc -l < "$work/photos.txt") photos, $(wc -l < "$work/copied.txt") encrypted copies, ${missing} missing"
(( missing == 0 )) || problem "${missing} photos have no encrypted copy, e.g. $(comm -23 "$work/photos.txt" "$work/copied.txt" | head -n 1)"
rclone lsf -R --files-only --format tp "backups:${BACKUP_BUCKET}/photos-age" | sort -r | head -n 5 | cut -d';' -f2 > "$work/sample.txt"
while IFS= read -r key; do
  [[ "$(rclone cat "backups:${BACKUP_BUCKET}/photos-age/${key}" --count ${#header})" == "$header" ]] \
    || problem "photos-age/${key} doesn't start with the age header"
done < "$work/sample.txt"

# 3. Off-site.
if $offsite; then
  primary_size=$(rclone lsf "backups:${BACKUP_BUCKET}/postgres/${latest}" --format s)
  offsite_size=$(rclone lsf "offsite:postgres/${latest}" --format s 2> /dev/null || true)
  [[ "$offsite_size" == "$primary_size" ]] \
    || problem "off-site copy of ${latest} is missing or a different size (${offsite_size:-none} vs ${primary_size})"
  rclone check "backups:${BACKUP_BUCKET}/photos-age" "offsite:photos-age" --one-way --size-only \
    || problem "encrypted photos missing from the off-site copy"
fi

# 4. Left over from before encryption: reported, not failed (the lock keeps them for 30 days).
plain_dumps=$(rclone lsf "backups:${BACKUP_BUCKET}/postgres" --include 'pokemans-*.dump' | wc -l)
plain_photos=$(rclone lsf -R --files-only "backups:${BACKUP_BUCKET}/photos" 2> /dev/null | wc -l || true)
if (( plain_dumps > 0 || plain_photos > 0 )); then
  echo "backup-check: still holding ${plain_dumps} plain-text dumps and ${plain_photos} plain-text photos from before encryption; delete them once allowed (docs/hosting.md#deleting-the-plain-text-backups)"
fi

if (( failed )); then fail "FAILED, see above"; fi
hc ""
echo "backup-check: OK"

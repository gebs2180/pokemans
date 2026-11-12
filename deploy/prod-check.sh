#!/usr/bin/env bash
# Read-only health report. Run it from a checkout, without deploying it first:
#   ssh deploy@beta.rawmark.app 'bash -s' < deploy/prod-check.sh
# Add a window as the first argument, for example: 'bash -s -- 6h'
set -uo pipefail

since="${1:-1h}"
cd ~/pokemans/deploy || exit 1
compose="docker compose -f compose.prod.yml"

echo "== commit"
git -C ~/pokemans log -1 --format='%h %ad %s' --date=iso
echo "== services"
$compose ps --format 'table {{.Service}}\t{{.Status}}'
echo "== health"
curl -s -o /dev/null -w 'site %{http_code}\n' https://beta.rawmark.app/
curl -s -w '\nhealth %{http_code}\n' https://beta.rawmark.app/actuator/health
echo "== backend errors since $since"
logs="$($compose logs --since "$since" --no-log-prefix backend 2>&1)"
printf '%s\n' "$logs" | grep -c ' ERROR ' || true
printf '%s\n' "$logs" | grep ' ERROR ' | tail -5 | cut -c1-240

# Read-only queries. </dev/null keeps exec from eating the rest of this script on stdin.
q() { $compose exec -T postgres psql -U pokemans -d pokemans -X -q -c "begin read only;" -c "$1" -c "rollback;" </dev/null; }
echo "== photo checks since $since (photos checked, then each retake reason)"
q "select count(*) as checked, count(*) filter (where status = 'RETAKE') as retake
     from photo where checked_at > now() - interval '$since';"
q "select r as reason, count(*) from photo, jsonb_array_elements_text(retake_reasons) r
     where checked_at > now() - interval '$since' group by r order by 2 desc;"
echo "== CARD_NOT_FOUND photos since $since (latest 20; storage keys for the detector test set)"
q "select checked_at::timestamp(0), side, width || 'x' || height as px, storage_key
     from photo where checked_at > now() - interval '$since' and retake_reasons ? 'CARD_NOT_FOUND'
     order by checked_at desc limit 20;"

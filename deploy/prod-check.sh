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

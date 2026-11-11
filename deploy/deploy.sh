#!/usr/bin/env bash
# Deploy one commit's images on the server (docs/hosting.md, "Day to day"). Start it with nohup so a dropped
# SSH connection can't stop it halfway.
#
#   deploy.sh                 the latest main, from the images CI pushed to GHCR (.github/workflows/images.yml)
#   deploy.sh <sha>           that commit's images: a rollback, or a redeploy
#   deploy.sh --build [sha]   fallback: build the images on this server instead of pulling them
#   --force                   allow a rollback across a database migration (read docs/hosting.md first)
#
# The checkout moves to the deployed commit too, so the compose file and Caddyfile match the images.
# Whatever is deployed is also tagged `deployed`, which is what compose.prod.yml runs by default.
set -euo pipefail

registry=ghcr.io/gebs2180
images=(backend web backup)
history_file=~/deploy-history
migrations=backend/app/src/main/resources/db/migration

# Everything runs from main(), so bash has read the whole script before `git checkout` replaces this file.
main() {
  local build=false force=false target="" arg
  for arg in "$@"; do
    case "$arg" in
      --build) build=true ;;
      --force) force=true ;;
      -*) echo "Unknown option $arg" >&2; exit 2 ;;
      *) target=$arg ;;
    esac
  done

  cd ~/pokemans
  git fetch --quiet origin
  local current sha
  current=$(git rev-parse HEAD)
  if [ -z "$target" ]; then
    sha=$(git rev-parse origin/main)
  else
    sha=$(git rev-parse --verify --quiet "$target^{commit}") || { echo "Unknown commit: $target" >&2; exit 1; }
  fi
  echo "Deploying $(git log -1 --format='%h %s' "$sha")"

  # Flyway only runs forward: going back past a migration leaves a database the older code may not expect.
  if [ "$sha" != "$current" ] && git merge-base --is-ancestor "$sha" "$current"; then
    local changed
    changed=$(git diff --name-only "$sha" "$current" -- "$migrations")
    if [ -n "$changed" ] && [ "$force" != true ]; then
      echo "Refusing to roll back across these migrations (they stay applied):" >&2
      echo "$changed" >&2
      echo "Read docs/hosting.md (Roll back) before rerunning with --force." >&2
      exit 1
    fi
  fi

  # Get the images first: if they aren't there, nothing has changed yet.
  local name
  if [ "$build" != true ]; then
    for name in "${images[@]}"; do
      if ! docker image inspect "$registry/pokemans-$name:$sha" > /dev/null 2>&1; then
        docker pull --quiet "$registry/pokemans-$name:$sha" || {
          echo "No $name image for $sha. CI may still be building it (GitHub → Actions → Images)," >&2
          echo "or the server isn't logged in to ghcr.io (docs/hosting.md). Fallback: deploy.sh --build $target" >&2
          exit 1
        }
      fi
    done
  fi

  if [ -z "$target" ]; then
    git checkout --quiet main
    git merge --ff-only --quiet "$sha"
  else
    git checkout --quiet --detach "$sha"
  fi
  cd deploy
  local compose="docker compose -f compose.prod.yml"

  if [ "$build" = true ]; then
    # Build before the backup and restart, so the site stays up during the build.
    IMAGE_TAG=$sha $compose build --pull backend caddy backup
  fi

  # Flyway migrations only run forward, so back up first: in the running backup container, or on a new
  # server (nothing running yet) with the new backup image.
  if [ -n "$($compose ps -q backup)" ]; then
    $compose exec -T backup backup.sh
  else
    IMAGE_TAG=$sha $compose run --rm backup backup.sh
  fi

  for name in "${images[@]}"; do
    docker tag "$registry/pokemans-$name:$sha" "$registry/pokemans-$name:deployed"
  done
  $compose up -d

  # The Caddyfile is mounted as a single file, so after a checkout that replaced it the container still
  # holds the old one, and `up -d` doesn't recreate caddy for a Caddyfile-only change. Recreate it
  # whenever what it runs differs from the repo.
  if ! $compose exec -T caddy cat /etc/caddy/Caddyfile | cmp -s - Caddyfile; then
    echo "Caddyfile changed: recreating caddy"
    $compose up -d --force-recreate --no-deps caddy
  fi

  local previous
  previous=$({ tail -n 1 "$history_file" 2> /dev/null || true; } | awk '{print $2}')
  previous=${previous:-$current}
  wait_healthy "$compose" || {
    $compose logs --tail 50 --no-log-prefix backend >&2
    echo "The backend isn't healthy. To roll back: deploy.sh $previous" >&2
    exit 1
  }

  echo "$(date -u +%FT%TZ) $sha" >> "$history_file"
  prune_images
  $compose ps
  echo "Deployed $(git log -1 --format='%h %s' "$sha"). Previous: ${previous:0:12}"
}

# The backend image has a health check (/actuator/health). Give it up to 5 minutes: Flyway runs first.
wait_healthy() {
  local compose=$1 cid status i
  cid=$($compose ps -q backend)
  for i in $(seq 60); do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2> /dev/null || echo missing)
    case "$status" in
      healthy) echo "Backend healthy"; return 0 ;;
      unhealthy) echo "Backend unhealthy" >&2; return 1 ;;
    esac
    sleep 5
  done
  echo "Backend not healthy after 5 minutes (status: $status)" >&2
  return 1
}

# Keep the images of the last three deploys, for rollback without a download; GHCR keeps every SHA anyway.
prune_images() {
  local keep img
  keep=$(awk '{print $2}' "$history_file" | tail -n 3)
  { docker image ls --format '{{.Repository}}:{{.Tag}}' | grep -E "^$registry/pokemans-[a-z]+:[0-9a-f]{40}$" || true; } |
    while read -r img; do
      grep -qx "${img##*:}" <<< "$keep" || docker image rm "$img" > /dev/null 2>&1 || true
    done
  docker image prune -f > /dev/null
}

main "$@"

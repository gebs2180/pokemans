#!/usr/bin/env bash
# Deploy the latest main on the server. Start it with nohup (see docs/hosting.md)
# so a dropped SSH connection can't stop the build halfway.
set -euo pipefail

cd ~/pokemans
git pull --ff-only
cd deploy

# Flyway migrations only run forward, so back up first.
docker compose -f compose.prod.yml exec -T backup backup.sh

# Build before restarting, so the site stays up during the build.
docker compose -f compose.prod.yml build --pull
docker compose -f compose.prod.yml up -d
docker compose -f compose.prod.yml ps
echo "Deployed $(git -C ~/pokemans log -1 --format='%h %s')"

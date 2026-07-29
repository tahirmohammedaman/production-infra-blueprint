#!/usr/bin/env bash
#
# Removes everything bootstrap.sh created, including volumes. Destructive by design:
# a teardown that leaves a stale database volume behind is how "works on my machine"
# starts, so the data goes with it.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

COMPOSE=$(compose_cmd)
COMPOSE_FILE="deploy/compose/docker-compose.yml"
OVERLAYS=$(compose_overlays)

log "stopping the stack and removing volumes"
# shellcheck disable=SC2086
$COMPOSE -f "$COMPOSE_FILE" $OVERLAYS down --volumes --remove-orphans || true
ok "stack removed"

if [ "${PRUNE_IMAGES:-false}" = "true" ]; then
  CONTAINER=$(container_cmd)
  log "removing built images"
  for service in api worker; do
    $CONTAINER image rm -f "blueprint-${service}:${APP_VERSION:-0.1.0-local}" >/dev/null 2>&1 || true
  done
  ok "images removed"
fi

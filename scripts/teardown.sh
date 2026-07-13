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

log "stopping the stack and removing volumes"
$COMPOSE -f "$COMPOSE_FILE" down --volumes --remove-orphans || true
ok "stack removed"

if [ "${PRUNE_IMAGES:-false}" = "true" ]; then
  CONTAINER=$(container_cmd)
  log "removing built images"
  $CONTAINER image rm -f "blueprint-api:${APP_VERSION:-0.1.0-local}" >/dev/null 2>&1 || true
  ok "images removed"
fi

#!/usr/bin/env bash
#
# Brings the local stack up from nothing and does not return until it is proven working.
# Intended to be the only command a new contributor has to run.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

COMPOSE=$(compose_cmd)
COMPOSE_FILE="deploy/compose/docker-compose.yml"
API_PORT="${API_PORT:-8080}"
MGMT_PORT="${MGMT_PORT:-9090}"

log "checking prerequisites"
command -v curl >/dev/null || die "curl is required"
command -v java >/dev/null || warn "no local JDK found; the container build supplies its own"
ok "using '$COMPOSE'"

if [ ! -f .env ]; then
  log "creating .env from .env.example"
  cp .env.example .env
  ok ".env created (edit it if you need non-default credentials)"
fi

log "building and starting the stack"
$COMPOSE -f "$COMPOSE_FILE" up -d --build

log "waiting for the API to report ready"
if ! wait_for_http "http://localhost:${MGMT_PORT}/actuator/health/readiness" 200 180; then
  warn "service did not become ready; recent logs follow"
  $COMPOSE -f "$COMPOSE_FILE" logs --tail 50 api || true
  die "bootstrap failed"
fi

log "running smoke tests"
scripts/smoke-test.sh "http://localhost:${API_PORT}" "http://localhost:${MGMT_PORT}"

cat <<SUMMARY

  API            http://localhost:${API_PORT}/api/v1/items
  OpenAPI UI     http://localhost:${API_PORT}/swagger-ui.html
  Health         http://localhost:${MGMT_PORT}/actuator/health
  Metrics        http://localhost:${MGMT_PORT}/actuator/prometheus

  Tear down with: make down

SUMMARY

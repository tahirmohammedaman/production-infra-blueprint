#!/usr/bin/env bash
#
# Brings the local stack up from nothing and does not return until it is proven working.
# Intended to be the only command a new contributor has to run.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

COMPOSE=$(compose_cmd)
COMPOSE_FILE="deploy/compose/docker-compose.yml"
OVERLAYS=$(compose_overlays)
SECRETS_DIR="deploy/compose/secrets"
GATEWAY_PORT="${GATEWAY_PORT:-8080}"
MGMT_PORT="${MGMT_PORT:-9090}"
WORKER_MGMT_PORT="${WORKER_MGMT_PORT:-9091}"

# Traefik discovers services through the container runtime's socket, which is in a
# different place under rootless podman than under Docker.
export CONTAINER_SOCKET="${CONTAINER_SOCKET:-$(detect_container_socket)}"

log "checking prerequisites"
command -v curl >/dev/null || die "curl is required"
command -v java >/dev/null || warn "no local JDK found; the container build supplies its own"
ok "using '$COMPOSE'"
if [ -n "$OVERLAYS" ]; then
  ok "applying runtime overlay: ${OVERLAYS#-f }"
fi

if [ ! -f .env ]; then
  log "creating .env from .env.example"
  cp .env.example .env
  ok ".env created (edit it if you need non-default credentials)"
fi

# Secret files, not environment variables, so the local stack exercises the same
# configtree code path that reads projected Kubernetes secrets in production.
# One directory per consumer, so each container mounts only the credentials it needs and
# the worker can be given none at all. The database password appears twice under two names
# because the Postgres entrypoint and Spring's configtree expect different file names for
# the same value.
log "generating local secret files"
mkdir -p "$SECRETS_DIR/postgres" "$SECRETS_DIR/app" "$SECRETS_DIR/grafana"
generate_secret "$SECRETS_DIR/postgres/db_password"
cp -f "$SECRETS_DIR/postgres/db_password" "$SECRETS_DIR/app/spring.datasource.password"
generate_secret "$SECRETS_DIR/grafana/admin_password"

# The two files need different modes, for a reason worth understanding rather than
# working around. Postgres runs as root in its container and drops privileges itself; with
# rootless containers the host user maps to container UID 0, so 0600 is readable there.
# Our own images run as UID 65532, which maps outside the host user's range and can read
# neither the file's owner nor its group — 0600 gives AccessDeniedException at startup.
#
# This is a local-development accommodation, not the production pattern. In Kubernetes the
# same files are projected from a SOPS-encrypted secret with `defaultMode: 0440` and an
# `fsGroup` matching the pod's user, so the credential is never world-readable anywhere.
chmod 600 "$SECRETS_DIR/postgres/db_password"
chmod 644 "$SECRETS_DIR/app/spring.datasource.password"
# Grafana runs as UID 472, outside the host user's range for the same reason as our images.
chmod 644 "$SECRETS_DIR/grafana/admin_password"
ok "secrets present in $SECRETS_DIR (gitignored, development values only)"

log "building and starting the stack"
# shellcheck disable=SC2086  # OVERLAYS is a deliberately unquoted flag list
compose_up "$COMPOSE" -f "$COMPOSE_FILE" $OVERLAYS

log "waiting for the API to report ready"
if ! wait_for_http "http://localhost:${MGMT_PORT}/actuator/health/readiness" 200 240; then
  warn "api did not become ready; recent logs follow"
  # shellcheck disable=SC2086
  $COMPOSE -f "$COMPOSE_FILE" $OVERLAYS logs --tail 50 api || true
  die "bootstrap failed"
fi

log "waiting for the worker to report ready"
if ! wait_for_http "http://localhost:${WORKER_MGMT_PORT}/actuator/health/readiness" 200 180; then
  warn "worker did not become ready; recent logs follow"
  # shellcheck disable=SC2086
  $COMPOSE -f "$COMPOSE_FILE" $OVERLAYS logs --tail 50 worker || true
  die "bootstrap failed"
fi

log "running smoke tests"
scripts/smoke-test.sh "http://localhost:${GATEWAY_PORT}" "http://localhost:${MGMT_PORT}" "http://localhost:${WORKER_MGMT_PORT}"

cat <<SUMMARY

  API (via gateway)   http://localhost:${GATEWAY_PORT}/api/v1/items
  Inventory summary   http://localhost:${GATEWAY_PORT}/api/v1/inventory/summary
  OpenAPI UI          http://localhost:${GATEWAY_PORT}/swagger-ui.html
  Gateway dashboard   http://localhost:${GATEWAY_DASHBOARD_PORT:-8081}/dashboard/
  API health          http://localhost:${MGMT_PORT}/actuator/health
  API metrics         http://localhost:${MGMT_PORT}/actuator/prometheus
  Worker health       http://localhost:${WORKER_MGMT_PORT}/actuator/health

  Grafana             http://localhost:${GRAFANA_PORT:-3000}   (admin password: $SECRETS_DIR/grafana/admin_password)
  Prometheus          http://localhost:${PROMETHEUS_PORT:-9095}
  Alertmanager        http://localhost:${ALERTMANAGER_PORT:-9093}

  Tear down with: make down

SUMMARY

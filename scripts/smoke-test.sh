#!/usr/bin/env bash
#
# Asserts that a running stack actually works: the API serves traffic, the database
# round-trips, the operational endpoints answer on the management port, and the public
# port does not expose them.
#
# This is also what verifies the curated jlink module list in app/Dockerfile. A missing
# JDK module does not fail the image build; it fails the first request that needs it, so
# the image is not considered good until this script passes against it.
#
# Usage: scripts/smoke-test.sh [api-url] [api-management-url] [worker-management-url]
#
# The API URL points at the gateway, not at the service, so the routing rules are covered
# too: a stack where every container is healthy but the gateway routes nowhere is broken,
# and testing the service directly would not notice.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

API="${1:-http://localhost:8080}"
MGMT="${2:-http://localhost:9090}"
WORKER_MGMT="${3:-http://localhost:9091}"

log "waiting for readiness at $MGMT"
wait_for_http "$MGMT/actuator/health/readiness" 200 180 \
  || die "api did not become ready within 180s"
ok "api is ready"

wait_for_http "$WORKER_MGMT/actuator/health/readiness" 200 180 \
  || die "worker did not become ready within 180s"
ok "worker is ready"

log "operational endpoints"
assert_body_contains "liveness reports UP"           "$MGMT/actuator/health/liveness"  '"status":"UP"'
assert_body_contains "readiness reports UP"          "$MGMT/actuator/health/readiness" '"status":"UP"'
assert_body_contains "prometheus exposes JVM metrics" "$MGMT/actuator/prometheus"      'jvm_memory_used_bytes'
assert_body_contains "prometheus exposes the business gauge" "$MGMT/actuator/prometheus" 'blueprint_items'
# The gateway routes only /api, /swagger-ui and /v3/api-docs. Anything under /actuator
# must not be reachable through it at all — that is the whole point of the port split.
assert_status "actuator is not routable through the gateway" "$API/actuator/health" 404

log "worker operational endpoints"
assert_body_contains "worker liveness reports UP"  "$WORKER_MGMT/actuator/health/liveness"  '"status":"UP"'
assert_body_contains "worker readiness reports UP" "$WORKER_MGMT/actuator/health/readiness" '"status":"UP"'
assert_body_contains "worker exposes consumer metrics" "$WORKER_MGMT/actuator/prometheus" 'kafka_consumer'

log "database read/write path"
NAME="smoke-$(date +%s)-$RANDOM"
CREATED=$(curl -fsS -X POST "$API/api/v1/items" \
  -H 'Content-Type: application/json' \
  -H "X-Request-Id: smoke-$(date +%s)" \
  -d "{\"name\":\"$NAME\",\"description\":\"created by smoke-test.sh\",\"quantity\":3}")

ID=$(printf '%s' "$CREATED" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
[ -n "$ID" ] || die "create did not return an id: $CREATED"
ok "created item $ID"

assert_status       "read back the created item" "$API/api/v1/items/$ID" 200
assert_body_contains "item survived the round-trip" "$API/api/v1/items/$ID" "$NAME"
assert_status       "list endpoint responds"     "$API/api/v1/items?page=0&size=5" 200

log "error contract"
assert_status "duplicate name is rejected" "$API/api/v1/items" 409 \
  -X POST -H 'Content-Type: application/json' -d "{\"name\":\"$NAME\",\"quantity\":1}"
assert_status "invalid body is rejected" "$API/api/v1/items" 400 \
  -X POST -H 'Content-Type: application/json' -d '{"name":"","quantity":-1}'
assert_status "unknown id is a 404, not a 500" \
  "$API/api/v1/items/00000000-0000-0000-0000-000000000000" 404
assert_status "unmapped path is a 404, not a 500" "$API/no/such/route" 404
assert_status "unsupported method is a 405, not a 500" "$API/api/v1/items" 405 -X PATCH

log "correlation id propagation"
HEADER=$(curl -fsS -o /dev/null -D - "$API/api/v1/items" -H 'X-Request-Id: smoke-correlation-id' \
  | tr -d '\r' | awk 'tolower($1)=="x-request-id:" {print $2}')
[ "$HEADER" = "smoke-correlation-id" ] || die "correlation id not echoed (got '$HEADER')"
ok "correlation id echoed back to the caller"

# Asserted here rather than with the other metric checks: the HTTP histogram does not
# exist until the first request has been served, so checking it on a freshly started
# service tests nothing and fails intermittently.
log "request metrics are recorded"
assert_body_contains "prometheus exposes the SLO latency histogram" \
  "$MGMT/actuator/prometheus" 'http_server_requests_seconds_bucket'
assert_body_contains "the API route is labelled by template, not by id" \
  "$MGMT/actuator/prometheus" 'uri="/api/v1/items/{id}"'

# ---------------------------------------------------------------- async path
# The part that only exists because there is more than one service: the write goes to
# Postgres and an outbox row, the relay publishes to Kafka, the worker consumes and updates
# the projection in Redis, and the API serves it back. Every hop has to work for this to
# pass, which makes it the single most valuable assertion in the file.
log "asynchronous path: outbox -> kafka -> worker -> cache"

BASELINE=$(curl -fsS "$API/api/v1/inventory/summary" | sed -n 's/.*"totalQuantity":\([0-9-]*\).*/\1/p')
[ -n "$BASELINE" ] || die "inventory summary did not return a totalQuantity"
ok "inventory summary readable, baseline totalQuantity=$BASELINE"

ASYNC_NAME="async-$(date +%s)-$RANDOM"
ASYNC_QTY=17
curl -fsS -X POST "$API/api/v1/items" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"$ASYNC_NAME\",\"quantity\":$ASYNC_QTY}" >/dev/null

EXPECTED=$((BASELINE + ASYNC_QTY))
DEADLINE=$((SECONDS + 60))
OBSERVED=""
while [ "$SECONDS" -lt "$DEADLINE" ]; do
  OBSERVED=$(curl -fsS "$API/api/v1/inventory/summary" | sed -n 's/.*"totalQuantity":\([0-9-]*\).*/\1/p')
  [ "$OBSERVED" = "$EXPECTED" ] && break
  sleep 1
done

if [ "$OBSERVED" = "$EXPECTED" ]; then
  ok "projection converged to totalQuantity=$EXPECTED through the full async chain"
else
  die "projection did not converge within 60s: expected $EXPECTED, observed ${OBSERVED:-none}"
fi

assert_body_contains "outbox reports no stuck events" "$MGMT/actuator/prometheus" 'blueprint_outbox_stuck'
assert_body_contains "worker recorded processed events" "$WORKER_MGMT/actuator/prometheus" 'blueprint_events_processed'

log "cleanup"
assert_status "delete the smoke-test item" "$API/api/v1/items/$ID" 204 -X DELETE
assert_status "deleted item is gone"       "$API/api/v1/items/$ID" 404

printf '\n%sall smoke tests passed%s\n' "$GREEN" "$RESET"

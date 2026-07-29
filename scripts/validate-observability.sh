#!/usr/bin/env bash
#
# Validates observability/ with the binaries that will actually run it, at the versions the
# stack runs — read from the Compose overlay, so a version is set in exactly one place:
#
#   prometheus    scrape configuration and every rule file, then the rule unit tests
#   alertmanager  configuration, then the routing tree against the routes it promises
#
# This is both `make obs-validate` and the `observability` job in CI.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

CONTAINER=$(container_cmd)
OVERLAY=deploy/compose/docker-compose.observability.yml
OBS="$PWD/observability"

# The image a service in the Compose overlay runs, digest included.
image_of() {
  sed -n "/^  $1:\$/,/^  [a-z]/{s/^    image: *//p;}" "$OVERLAY" | head -1
}

PROMETHEUS=$(image_of prometheus)
ALERTMANAGER=$(image_of alertmanager)
for image in "$PROMETHEUS" "$ALERTMANAGER"; do
  [ -n "$image" ] || die "could not read every image from $OVERLAY"
done

# Runs a check quietly, and prints everything it said only if it failed. Plain `>/dev/null`
# under `set -e` would exit on the first failing check with no explanation at all — a gate that
# tells you it is shut but not why.
quietly() {
  local output
  if ! output=$("$@" 2>&1); then
    printf '%s\n' "$output" >&2
    return 1
  fi
}

promtool() {
  "$CONTAINER" run --rm -v "$OBS/prometheus:/etc/prometheus:ro,z" -w /etc/prometheus/tests \
    --entrypoint /bin/promtool "$PROMETHEUS" "$@"
}

amtool() {
  "$CONTAINER" run --rm -v "$OBS/alertmanager:/etc/alertmanager:ro,z" \
    --entrypoint /bin/amtool "$ALERTMANAGER" "$@"
}

log "prometheus: scrape configuration and rule files"
quietly promtool check config /etc/prometheus/prometheus.yml \
  || die "promtool rejected the Prometheus configuration or a rule file"
ok "configuration and every rule file parse"

log "prometheus: rule unit tests"
quietly promtool test rules alerts.test.yml slo.test.yml \
  || die "a rule unit test failed; the expected and actual alerts are printed above"
ok "every alert fires when its tests say it must, and stays quiet when they say it must not"

log "alertmanager: configuration"
quietly amtool check-config /etc/alertmanager/alertmanager.yml \
  || die "amtool rejected observability/alertmanager/alertmanager.yml"
ok "configuration is valid"

# The routes everything else relies on. A page that routes to the blackhole is an outage
# nobody hears about; a laptop that routes to the pager is a colleague woken for nothing.
log "alertmanager: routing tree"
assert_route() {
  local expected="$1" actual
  shift
  actual=$(amtool config routes test --config.file=/etc/alertmanager/alertmanager.yml "$@" | tail -1)
  [ "$actual" = "$expected" ] || die "labels {$*} route to '$actual', expected '$expected'"
  ok "{$*} -> $expected"
}
assert_route blackhole       env=local severity=page
assert_route pager           env=prod severity=page
assert_route chat            env=prod severity=ticket
assert_route deadmans-switch env=prod severity=none alertname=Watchdog
assert_route blackhole       env=prod severity=none

printf '\n%sobservability configuration is valid%s\n' "$GREEN" "$RESET"

#!/usr/bin/env bash
#
# Validates observability/ with the binaries that will actually run it, at the versions the
# stack runs — read from the Compose overlay, so a version is set in exactly one place:
#
#   prometheus    scrape configuration and every rule file, then the rule unit tests
#   alertmanager  configuration, then the routing tree against the routes it promises
#   loki, tempo   configuration verification by the binary itself
#   alloy         validation, and canonical formatting
#   grafana       every dashboard parses, has a unique uid, and uses only provisioned datasources
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
LOKI=$(image_of loki)
TEMPO=$(image_of tempo)
ALLOY=$(image_of alloy)
for image in "$PROMETHEUS" "$ALERTMANAGER" "$LOKI" "$TEMPO" "$ALLOY"; do
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

alloy() {
  "$CONTAINER" run --rm -v "$OBS/alloy:/etc/alloy:ro,z" "$ALLOY" "$@"
}

log "prometheus: scrape configuration and rule files"
quietly promtool check config /etc/prometheus/prometheus.yml \
  || die "promtool rejected the Prometheus configuration or a rule file"
# Syntax only: the in-cluster file names the pod's service-account token, which exists in a
# pod and nowhere else. Its rule files are the ones checked above.
quietly promtool check config --syntax-only /etc/prometheus/kubernetes.yml \
  || die "promtool rejected observability/prometheus/kubernetes.yml"
ok "both configurations and every rule file parse"

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

log "loki: configuration"
quietly "$CONTAINER" run --rm -v "$OBS/loki:/etc/loki:ro,z" "$LOKI" \
  -config.file=/etc/loki/loki.yml -verify-config \
  || die "loki rejected observability/loki/loki.yml"
ok "configuration is valid"

log "tempo: configuration"
quietly "$CONTAINER" run --rm -v "$OBS/tempo:/etc/tempo:ro,z" "$TEMPO" \
  -config.file=/etc/tempo/tempo.yml -config.verify=true \
  || die "tempo rejected observability/tempo/tempo.yml"
ok "configuration is valid"

log "alloy: configuration and formatting"
for config in config.alloy kubernetes.alloy; do
  quietly alloy validate "/etc/alloy/$config" \
    || die "alloy rejected observability/alloy/$config"
done
for file in observability/alloy/*.alloy; do
  # `alloy fmt` is the canonical form, the way gofmt is for Go: a diff here is a formatting
  # change someone forgot to run, not a matter of taste.
  alloy fmt "/etc/alloy/$(basename "$file")" | diff -u "$file" - >/dev/null \
    || die "$file is not in canonical form; format it with 'alloy fmt'"
done
ok "valid and canonically formatted"

log "grafana: dashboards"
command -v jq >/dev/null || die "jq is required"
provisioned=$(sed -n 's/^    uid: //p' observability/grafana/provisioning/datasources/datasources.yml | jq -R . | jq -sc .)
uids=()
for dashboard in observability/grafana/dashboards/*.json; do
  jq -e . "$dashboard" >/dev/null 2>&1 || die "$dashboard is not valid JSON"
  uid=$(jq -r '.uid // empty' "$dashboard")
  [ -n "$uid" ] || die "$dashboard has no uid, so every reload would create a new copy"
  # Every datasource must be one Grafana was provisioned with, or the panel renders "datasource
  # not found" — a failure that only shows up when someone opens the dashboard mid-incident.
  unknown=$(jq -r --argjson known "$provisioned" '
    [.. | objects | select(has("datasource")) | .datasource | objects | .uid // empty]
    | unique | map(select(. as $u | ($known + ["-- Grafana --"]) | index($u) | not)) | .[]' "$dashboard")
  [ -z "$unknown" ] || die "$dashboard uses datasources that are not provisioned: $unknown"
  uids+=("$uid")
done
duplicates=$(printf '%s\n' "${uids[@]}" | sort | uniq -d)
[ -z "$duplicates" ] || die "dashboard uid used more than once: $duplicates"
ok "${#uids[@]} dashboards parse, have unique uids and use only provisioned datasources"

printf '\n%sobservability configuration is valid%s\n' "$GREEN" "$RESET"

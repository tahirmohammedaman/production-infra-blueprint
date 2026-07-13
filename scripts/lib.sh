#!/usr/bin/env bash
# Shared helpers. Sourced, never executed directly.

set -euo pipefail

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; RESET=$'\033[0m'

log()  { printf '%s==>%s %s\n' "$DIM" "$RESET" "$*"; }
ok()   { printf '%s  ok%s   %s\n' "$GREEN" "$RESET" "$*"; }
warn() { printf '%s  warn%s %s\n' "$YELLOW" "$RESET" "$*"; }
die()  { printf '%s  fail%s %s\n' "$RED" "$RESET" "$*" >&2; exit 1; }

# Resolve a Compose implementation once. Both are supported on purpose: contributors on
# Fedora and other rootless-container distributions have podman, not docker.
compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    echo "docker compose"
  elif command -v podman-compose >/dev/null 2>&1; then
    echo "podman-compose"
  elif command -v docker-compose >/dev/null 2>&1; then
    echo "docker-compose"
  else
    die "no compose implementation found (tried: docker compose, podman-compose, docker-compose)"
  fi
}

container_cmd() {
  if command -v docker >/dev/null 2>&1; then
    echo docker
  elif command -v podman >/dev/null 2>&1; then
    echo podman
  else
    die "neither docker nor podman is installed"
  fi
}

# Poll a URL until it returns the expected status, or give up. Used instead of `sleep`
# so the scripts are deterministic on a slow machine and fast on a warm one.
wait_for_http() {
  local url="$1" expected="${2:-200}" timeout="${3:-90}" elapsed=0
  while [ "$elapsed" -lt "$timeout" ]; do
    if [ "$(curl -fsS -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || echo 000)" = "$expected" ]; then
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  return 1
}

assert_status() {
  local description="$1" url="$2" expected="$3"; shift 3
  local actual
  actual=$(curl -sS -o /dev/null -w '%{http_code}' "$@" "$url" || echo 000)
  if [ "$actual" = "$expected" ]; then
    ok "$description (HTTP $actual)"
  else
    die "$description: expected HTTP $expected, got $actual"
  fi
}

assert_body_contains() {
  local description="$1" url="$2" needle="$3"; shift 3
  local body
  body=$(curl -sS "$@" "$url" || true)
  # A here-string, deliberately not `printf ... | grep -q`. Under `set -o pipefail` that
  # pipeline is a race: grep -q exits on the first match, printf gets EPIPE, and the
  # pipeline reports failure even though the needle was found. It only bites on bodies
  # larger than the pipe buffer, which is exactly what /actuator/prometheus returns.
  # -F because needles contain regex metacharacters such as uri="/api/v1/items/{id}".
  if grep -qF -- "$needle" <<<"$body"; then
    ok "$description"
  else
    die "$description: response did not contain '$needle'"
  fi
}

#!/usr/bin/env bash
#
# Renders every kustomization in the repository into one directory, a file each, with the
# digest-pinned kustomize image.
#
#   scripts/render-manifests.sh <output directory>
#
# This is the one list of what gets rendered. `make k8s-validate`, the `manifests` job in ci.yml
# and the checkov scan in security.yml all call this script, so a new kustomization is
# schema-checked and scanned as soon as it is added here — and is invisible to both until it is.
#
# security.yml scans this output rather than letting checkov render the tree itself. checkov's
# kustomize framework renders overlays in parallel into temporary paths named after each overlay's
# directory, and four overlays here are called `prod`. Their output lands in the same files, and
# the scan dies with "expected a single document in the stream" on some runs and then hangs. The
# rendered output of the kustomize this repository pins is also simply the better thing to scan:
# it is exactly what Flux applies.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

OUT="${1:-}"
case "$OUT" in
  "" | "/" | "." | "./" | "..")
    die "usage: scripts/render-manifests.sh <output directory>; it is emptied first, so not '$OUT'" ;;
esac

KUSTOMIZE_IMAGE=registry.k8s.io/kustomize/kustomize@sha256:899fcd3bc898160e62bcaf82932b0cb29ba38d16272353db2e7acbba82129429
KUSTOMIZATIONS=(
  deploy/k8s/base
  deploy/k8s/config/prod
  deploy/k8s/data/prod
  deploy/k8s/migrations/prod
  deploy/k8s/overlays/prod
  deploy/k8s/overlays/dev
  deploy/k8s/restore
  deploy/k8s/infrastructure/controllers
  deploy/k8s/infrastructure/configs
  deploy/k8s/infrastructure/observability
  observability
)

CONTAINER=$(container_cmd)
rm -rf "$OUT"
mkdir -p "$OUT"
for path in "${KUSTOMIZATIONS[@]}"; do
  "$CONTAINER" run --rm -v "$PWD:/k:ro,z" "$KUSTOMIZE_IMAGE" build "/k/$path" > "$OUT/${path//\//-}.yaml"
done
ok "rendered ${#KUSTOMIZATIONS[@]} kustomizations into $OUT"
